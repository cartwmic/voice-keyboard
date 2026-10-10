package com.tyraen.voicekeyboard.feature.ime

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Binder
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.Button
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.DictationApp
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.KeyboardLayoutDiagnostics
import com.tyraen.voicekeyboard.core.config.KeyboardLayoutPreferences
import com.tyraen.voicekeyboard.feature.setup.SetupActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercises the real application, IME callbacks and settings copy button, without a fake report. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DictationApp::class, qualifiers = "w320dp-h640dp-mdpi")
class DictationInputMethodDiagnosticsLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication() as DictationApp
    private var serviceController: ServiceController<DictationInputMethod>? = null
    private var settingsController: ActivityController<SetupActivity>? = null

    @Before fun configureCompactKeyboard() {
        assertTrue(RuntimeEnvironment.getApplication() is DictationApp)
        app.getSharedPreferences("keyboard_layout_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        KeyboardLayoutPreferences.persist(app, compact = true, heightDp = 64, besideCameras = true)
        runBlocking {
            ServiceLocator.preferenceStore.setMicDisclosureAccepted()
            ServiceLocator.preferenceStore.setUpdateCheckEnabled(false)
        }
    }

    @After fun cleanup() {
        settingsController?.pause()?.stop()?.destroy()
        serviceController?.destroy()
    }

    private fun createService(): DictationInputMethod =
        Robolectric.buildService(DictationInputMethod::class.java).create().also {
            serviceController = it
        }.get()

    private fun editor() = EditorInfo().apply {
        packageName = "example.editor"
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_NONE
    }

    private fun assertRealSnapshot(report: String) {
        assertFalse(report, report.contains("No compact keyboard measurement yet"))
        assertTrue(report, report.contains("Latest layout event"))
        assertTrue(report, report.contains("Voice Keyboard compact layout"))
        assertTrue(report, report.contains("Camera readiness:"))
    }

    private fun copyFromActualSettings(): String {
        val controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        settingsController = controller
        val activity = controller.get()
        assertTrue(activity.findViewById<Button>(R.id.btnCopyLayoutDiagnostics).performClick())
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return requireNotNull(clipboard.primaryClip).getItemAt(0).coerceToText(activity).toString()
    }

    @Test fun `input creation alone leaves a durable checkpoint that settings can copy`() {
        val service = createService()
        service.onCreateInputView()
        // No setInputView, attach, show callback, manual layout, or inset dispatch follows.
        // This is a lifecycle coverage regression, not a claim about the reported phone path.
        val copied = copyFromActualSettings()
        assertTrue(copied, copied.contains("IME lifecycle checkpoints"))
        assertTrue(copied, copied.contains("Event: input_view_created"))
    }

    @Test fun `start input view records availability even without a window shown callback`() {
        val service = createService()
        service.onCreateInputView()
        service.onStartInput(editor(), false)
        service.onStartInputView(editor(), false)
        val copied = copyFromActualSettings()
        assertTrue(copied, copied.contains("Event: input_view_started"))
        assertRealSnapshot(copied)
        assertTrue(copied, copied.contains("IME service shown: true"))
    }

    @Test fun `create before window shown saves a latest event even without any insets`() {
        val service = createService()
        val input = service.onCreateInputView()
        service.setInputView(input)
        service.onStartInput(editor(), false)
        service.onStartInputView(editor(), false)
        service.onWindowShown()
        val report = KeyboardLayoutDiagnostics.read(service)
        assertRealSnapshot(report)
        assertTrue(report, report.contains("IME service shown: true"))
        assertTrue(report, report.contains("WAITING"))
        assertRealSnapshot(copyFromActualSettings())
    }

    @Test fun `window shown before replacement input creation preserves a real latest event`() {
        val service = createService()
        service.onWindowShown()
        assertRealSnapshot(KeyboardLayoutDiagnostics.read(service))
        service.setInputView(service.onCreateInputView())
        service.onStartInputView(editor(), true)
        assertRealSnapshot(copyFromActualSettings())
    }

    @Test fun `framework token startInput and showWindow invoke the diagnostic wiring`() {
        val service = createService()
        // Robolectric 4.12's system-server stub does not attach WindowProviderService to a
        // DisplayArea. Supply that framework-only attachment state before driving the real IME
        // interface; no application lifecycle method or diagnostic callback is mocked.
        val windowContextController = ReflectionHelpers.getField<Any>(service, "mController")
        ReflectionHelpers.setField(windowContextController, "mAttachedToDisplayArea", 1)
        val inputMethod = service.onCreateInputMethodInterface() as InputMethodService.InputMethodImpl
        inputMethod.attachToken(Binder())
        inputMethod.startInput(BaseInputConnection(View(app), true), editor())
        service.showWindow(true)
        val report = KeyboardLayoutDiagnostics.read(service)
        assertRealSnapshot(report)
        assertTrue(report, report.contains("IME service shown: true"))
        assertRealSnapshot(copyFromActualSettings())
    }

    @Test fun `hide and service teardown do not erase the event copied by settings`() {
        val service = createService()
        service.setInputView(service.onCreateInputView())
        service.onWindowShown()
        assertRealSnapshot(KeyboardLayoutDiagnostics.read(service))
        service.onWindowHidden()
        serviceController?.destroy()
        serviceController = null
        val copied = copyFromActualSettings()
        assertRealSnapshot(copied)
        assertTrue(copied, copied.contains("Keyboard visible: false"))
    }

    @Test fun `missing insets still copy geometry events rather than the no measurement message`() {
        val service = createService()
        val input = service.onCreateInputView()
        service.setInputView(input)
        service.onWindowShown()
        input.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        input.layout(0, 0, 320, input.measuredHeight)
        val copied = copyFromActualSettings()
        assertRealSnapshot(copied)
        assertTrue(copied, copied.contains("Input screen bounds:"))
        assertTrue(copied, copied.contains("Latest layout event"))
        // A missing visible traversal must not bury the captured WAITING geometry below an
        // instruction to repeat an already-completed keyboard-open action.
        assertFalse(copied, copied.contains("Open the keyboard"))
        assertFalse(copied, copied.contains("Open the compact keyboard"))
    }
}
