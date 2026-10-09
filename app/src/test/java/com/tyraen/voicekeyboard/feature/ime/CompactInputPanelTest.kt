package com.tyraen.voicekeyboard.feature.ime

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.config.KeyboardLayoutPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
class CompactInputPanelTest {
    private fun panel(height: Int): Pair<ConstraintLayout, InputPanelController> {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_VoiceKeyboard)
        val root = LayoutInflater.from(context).inflate(R.layout.input_panel, null) as ConstraintLayout
        CompactInputPanel.apply(root, height)
        return root to InputPanelController(root, compact = true).also { it.transitionTo(InputPhase.Ready) }
    }

    private fun measure(root: View, width: Int = 320): Int {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root.measuredHeight
    }

    @Test fun `layout settings save immediately and clamp height`() {
        val context = RuntimeEnvironment.getApplication<Application>()
        KeyboardLayoutPreferences.persist(context, compact = true, heightDp = 999)
        assertTrue(KeyboardLayoutPreferences.current(context).compact)
        assertEquals(72, KeyboardLayoutPreferences.current(context).heightDp)
        KeyboardLayoutPreferences.persist(context, compact = false, heightDp = -1)
        assertFalse(KeyboardLayoutPreferences.current(context).compact)
        assertEquals(56, KeyboardLayoutPreferences.current(context).heightDp)
    }

    @Test fun `all phases and hidden controls preserve row height and touch targets`() {
        for (height in listOf(56, 64, 72)) {
            val (root, controller) = panel(height)
            fun assertGeometry() {
                assertEquals(height, measure(root))
                for (id in listOf(R.id.btnPreviousKeyboard, R.id.btnMic, R.id.btnBackspace, R.id.btnEnter)) {
                    val key = root.findViewById<View>(id)
                    assertTrue("$id width", key.width >= 48)
                    assertTrue("$id height", key.height >= 48)
                }
            }
            assertGeometry()
            controller.transitionTo(InputPhase.Capturing())
            assertGeometry()
            controller.updateQueueCount(2)
            assertGeometry()
            controller.transitionTo(InputPhase.Ready)
            assertGeometry()
            controller.updateProcessingPhase(ProcessingQueue.ProcessingPhase.POST_PROCESSING)
            controller.updateClipboard("clipboard content that should never add a row")
            controller.showPostProcessingButtons(true)
            controller.updateLanguageKey("en", true)
            controller.updateFailedCount(3)
            assertGeometry()
            controller.updateQueueCount(0)
            controller.setVoiceBlocked(true)
            assertGeometry()
            assertTrue(root.findViewById<ImageButton>(R.id.btnMic).contentDescription.contains("password", true))
            controller.transitionTo(InputPhase.Failed(R.string.error_api_key_missing))
            assertGeometry()
        }
    }

    @Test fun `cutout reserve adds safe padding without changing key height`() {
        val (root, _) = panel(64)
        ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(8, 0, 12, 20)).build())
        assertEquals(84, measure(root))
        assertEquals(12, root.paddingLeft)
        assertEquals(16, root.paddingRight)
        assertEquals(24, root.paddingBottom)
        assertEquals(56, root.findViewById<View>(R.id.btnMic).height)
    }

    @Test fun `compact mode keeps backspace and enter in their requested order`() {
        val (root, _) = panel(64)
        measure(root)
        val previous = root.findViewById<View>(R.id.btnPreviousKeyboard)
        val mic = root.findViewById<View>(R.id.micFrame)
        val backspace = root.findViewById<View>(R.id.btnBackspace)
        val enter = root.findViewById<View>(R.id.btnEnter)
        assertTrue(previous.right <= mic.left)
        assertTrue(mic.right <= backspace.left)
        assertTrue(backspace.right <= enter.left)
    }
}
