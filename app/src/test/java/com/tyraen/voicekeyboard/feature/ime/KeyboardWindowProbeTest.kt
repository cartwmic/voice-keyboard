package com.tyraen.voicekeyboard.feature.ime

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Insets
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.tyraen.voicekeyboard.core.config.KeyboardLayoutDiagnostics
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
class KeyboardWindowProbeTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity = controller.get()
    private var suppliedInsets: WindowInsets? = null
    private var failRootInsets = false
    private val input = object : View(activity) {
        override fun getRootWindowInsets(): WindowInsets? {
            if (failRootInsets) throw IllegalStateException("private unrelated detail")
            return suppliedInsets
        }
    }
    private val prefs get() = activity.getSharedPreferences("keyboard_layout_diagnostics", Context.MODE_PRIVATE)
    private val probe get() = KeyboardWindowProbe(activity, input, activity.window)

    @Before fun setUp() {
        prefs.edit().clear().commit()
        activity.setContentView(input)
        measured()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
    }

    private fun measured() {
        input.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY))
        input.layout(0, 576, 320, 640)
        assertTrue(input.isAttachedToWindow)
        assertFalse(input.isLayoutRequested)
    }

    private fun frame(camera: Boolean): String? =
        prefs.getString("window_probe_${if (camera) "always" else "default"}_report", null)

    @Test fun `no frame is saved before a probe begins`() {
        probe.onMeasuredFrame(true, 1L, "WAITING", false)
        assertNull(frame(true))
        assertFalse(prefs.contains("window_probe_id"))
    }

    @Test fun `unmeasured or detached input cannot be reported as a measured frame`() {
        val probe = probe
        probe.onModeRequested(true)
        input.layout(0, 0, 0, 0)
        probe.onMeasuredFrame(true, 1L, "WAITING", false)
        assertNull(frame(true))
        measured()
        input.requestLayout()
        probe.onMeasuredFrame(true, 1L, "WAITING", false)
        assertNull(frame(true))
        measured()
        activity.setContentView(View(activity))
        assertFalse(input.isAttachedToWindow)
        probe.onMeasuredFrame(true, 1L, "WAITING", false)
        assertNull(frame(true))
    }

    @Test fun `literal null root insets remain distinct from present zero insets`() {
        val probe = probe
        probe.onModeRequested(true)
        probe.onMeasuredFrame(true, 1L, "WAITING", false)
        assertTrue(frame(true)!!.contains("Input root insets: null (unavailable)"))

        suppliedInsets = WindowInsets.Builder().build()
        probe.onModeRequested(false)
        probe.onMeasuredFrame(false, 2L, "FALLBACK", false)
        val safe = frame(false)!!
        assertTrue(safe.contains("Input root insets: present"))
        assertTrue(safe.contains("DisplayCutout: null"))
        assertTrue(safe.contains("displayCutout type insets: Insets{left=0, top=0, right=0, bottom=0}"))
        assertTrue(frame(true)!!.contains("Input root insets: null (unavailable)"))
    }

    @Test fun `ordinary duplicate frames are bounded but a final pre-change sample can improve evidence`() {
        val probe = probe
        probe.onModeRequested(true)
        probe.onMeasuredFrame(true, 1L, "WAITING", false)
        val first = frame(true)!!
        suppliedInsets = WindowInsets.Builder().setDisplayCutout(DisplayCutout(
            Insets.of(0, 0, 0, 80), null, null, null, Rect(280, 560, 320, 640)
        )).build()
        repeat(20) { probe.onMeasuredFrame(true, 1L, "SETTLING", false) }
        assertEquals(first, frame(true))

        probe.onMeasuredFrame(true, 1L, "FALLBACK", true)
        val final = frame(true)!!
        assertNotEquals(first, final)
        assertTrue(final.contains("DisplayCutout: present"))
        assertTrue(final.contains("Rect(280, 560 - 320, 640)"))
        probe.onModeRequested(false)
        probe.onMeasuredFrame(false, 2L, "FALLBACK", false)
        assertEquals(final, frame(true))
        assertTrue(frame(false)!!.contains("Requested mode for this frame: DEFAULT"))
    }

    @Test fun `probe reports display config and all metric sources without changing layout or window`() {
        val params = activity.window.attributes
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        activity.window.attributes = params
        input.setPadding(1, 2, 3, 4)
        measured()
        val layoutParams = input.layoutParams
        val attrs = WindowManager.LayoutParams().also { it.copyFrom(activity.window.attributes) }
        val probe = probe
        probe.onModeRequested(true)
        probe.onMeasuredFrame(true, 1L, "WAITING", false)
        val report = frame(true)!!

        assertTrue(report.contains("Input screen bounds:"))
        assertTrue(report.contains("Decor screen bounds:"))
        assertTrue(report.contains("Display.Mode physical pixels (not placement coordinates):"))
        assertTrue(report.contains("IME resource configuration dp:"))
        assertTrue(report.contains("Current WindowMetrics"))
        assertTrue(report.contains("Maximum WindowMetrics"))
        assertTrue(report.contains("rotation:"))
        assertSame(layoutParams, input.layoutParams)
        assertEquals(listOf(1, 2, 3, 4), listOf(input.paddingLeft, input.paddingTop, input.paddingRight, input.paddingBottom))
        assertEquals(320, input.width)
        assertEquals(64, input.height)
        assertEquals(View.VISIBLE, input.visibility)
        assertFalse(input.isLayoutRequested)
        assertEquals(0, attrs.copyFrom(activity.window.attributes))
    }

    @Test fun `one failed read does not discard other measurements or leak exception detail`() {
        val probe = probe
        probe.onModeRequested(true)
        failRootInsets = true
        probe.onMeasuredFrame(true, 1L, "WAITING", false)
        val report = frame(true)!!
        assertTrue(report.contains("Input root insets"))
        assertTrue(report.contains("IllegalStateException"))
        assertFalse(report.contains("private unrelated detail"))
        assertTrue(report.contains("Input screen bounds:"))
        assertTrue(report.contains("Current WindowMetrics"))
        assertTrue(report.contains("Maximum WindowMetrics"))
    }

    @Test fun `missing window is reported without inventing window geometry`() {
        val probe = KeyboardWindowProbe(activity, input, null)
        probe.onModeRequested(false)
        probe.onMeasuredFrame(false, 1L, "DISABLED", false)
        val report = frame(false)!!
        assertTrue(report.contains("Decor bounds: unavailable"))
        assertTrue(report.contains("Decor root insets: null (unavailable)"))
        assertTrue(report.contains("WindowMetrics: unavailable (no window manager)"))
        assertTrue(KeyboardLayoutDiagnostics.read(activity).contains(report))
    }
}
