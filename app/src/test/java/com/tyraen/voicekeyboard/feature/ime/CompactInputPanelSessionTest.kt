package com.tyraen.voicekeyboard.feature.ime

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
class CompactInputPanelSessionTest {
    private val activityController = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity = activityController.get()
    private val holder = FrameLayout(activity)
    private var descendantInsetDispatches = 0
    private val root = object : ConstraintLayout(activity) {
        override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
            descendantInsetDispatches++
            return super.dispatchApplyWindowInsets(insets)
        }
    }
    private val row = View(activity)
    private var insets = WindowInsetsCompat.Builder().build()
    private var requestedMode = "DEFAULT"
    private val modes = mutableListOf<Boolean>()
    private val reports = mutableListOf<String>()
    private var session: CompactInputPanelSession? = null

    private fun start(besideCameras: Boolean = true): CompactInputPanelSession {
        root.addView(row, ConstraintLayout.LayoutParams(0, 56).apply {
            startToStart = 0; endToEnd = 0; topToTop = 0
        })
        holder.addView(root, FrameLayout.LayoutParams(-1, 64, Gravity.BOTTOM))
        activity.setContentView(holder)
        holder.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        holder.layout(0, 0, 320, 640)
        return CompactInputPanelSession(root, row, 64, besideCameras,
            onDiagnostics = { report, visible -> reports.add("mode=$requestedMode visible=$visible $report") },
            onCameraModeRequested = { camera ->
                modes.add(camera)
                requestedMode = if (camera) "ALWAYS" else "DEFAULT"
            },
            rootInsetsProvider = { insets }
        ).also { session = it; it.onShown() }
    }

    private fun deliver(hasCamera: Boolean) {
        val builder = WindowInsetsCompat.Builder()
        if (hasCamera) builder.setDisplayCutout(DisplayCutoutCompat(Rect(0, 0, 0, 80), listOf(Rect(280, 560, 320, 640))))
        insets = builder.build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
    }

    private fun preDraw(container: FrameLayout = holder) {
        shadowOf(Looper.getMainLooper()).idle()
        container.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        container.layout(0, 0, 320, 640)
        root.viewTreeObserver.dispatchOnPreDraw()
    }

    private fun consumeDescendantInsets(container: FrameLayout) {
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, _ -> WindowInsetsCompat.CONSUMED }
    }

    @After fun cleanup() {
        session?.dispose()
        activityController.pause().stop().destroy()
    }

    @Test fun `normal compact mode renders without descendant inset delivery`() {
        start(besideCameras = false)
        preDraw()
        assertEquals("DEFAULT", requestedMode)
        assertEquals(View.VISIBLE, row.visibility)
    }

    @Test fun `camera probe without descendant inset delivery returns to usable safe row`() {
        start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        preDraw()
        assertEquals("DEFAULT", requestedMode)
        assertEquals(View.VISIBLE, row.visibility)
    }

    @Test fun `consuming IME ancestor cannot hide normal compact controls`() {
        consumeDescendantInsets(holder)
        start(besideCameras = false)
        // Dispatch only to the ancestor. Its consumption deliberately prevents our panel's
        // OnApplyWindowInsetsListener from running, as the IME decor can do on-device.
        ViewCompat.dispatchApplyWindowInsets(holder, insets)
        preDraw()
        preDraw()
        assertEquals(0, descendantInsetDispatches)
        assertEquals("DEFAULT", requestedMode)
        assertEquals(View.VISIBLE, row.visibility)
        assertTrue(root.width > 0 && root.height > 0)
        assertTrue(reports.last().contains("visible=true"))
    }

    @Test fun `consuming IME ancestor still permits bounded camera fallback`() {
        consumeDescendantInsets(holder)
        start()
        ViewCompat.dispatchApplyWindowInsets(holder, insets)
        preDraw()
        assertEquals(View.INVISIBLE, row.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        preDraw()
        preDraw()
        assertEquals(0, descendantInsetDispatches)
        assertEquals(listOf(true, false), modes)
        assertEquals("DEFAULT", requestedMode)
        assertEquals(View.VISIBLE, row.visibility)
        assertTrue(reports.last().contains("FALLBACK"))
    }

    @Test fun `camera frame observer follows a reattachment to another window`() {
        consumeDescendantInsets(holder)
        start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        preDraw()
        preDraw()
        assertEquals(View.VISIBLE, row.visibility)

        val otherController = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val otherHolder = FrameLayout(otherController.get())
            consumeDescendantInsets(otherHolder)
            val params = root.layoutParams
            holder.removeView(root)
            insets = WindowInsetsCompat.Builder().setDisplayCutout(
                DisplayCutoutCompat(Rect(0, 0, 0, 80), listOf(Rect(280, 560, 320, 640)))
            ).build()
            otherHolder.addView(root, params)
            otherController.get().setContentView(otherHolder)
            ViewCompat.dispatchApplyWindowInsets(otherHolder, insets)
            preDraw(otherHolder)
            preDraw(otherHolder)
            assertEquals(0, descendantInsetDispatches)
            assertEquals("ALWAYS", requestedMode)
            assertEquals(View.VISIBLE, row.visibility)
            assertTrue(reports.last().contains("GEOMETRY"))
        } finally {
            otherController.pause().stop().destroy()
        }
    }

    @Test fun `first empty delivery waits and later geometry keeps camera mode`() {
        start()
        deliver(false)
        assertEquals(View.INVISIBLE, row.visibility)
        assertEquals(listOf(true), modes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        deliver(true)
        preDraw()
        assertEquals(View.VISIBLE, row.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals("ALWAYS", requestedMode)
        assertTrue(reports.last().contains("GEOMETRY"))
    }

    @Test fun `fallback records current default mode and waits for a safe traversal`() {
        start()
        deliver(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        assertEquals("DEFAULT", requestedMode)
        assertTrue(reports.last().startsWith("mode=DEFAULT"))
        assertTrue(reports.last().contains("FALLBACK"))
        deliver(false)
        preDraw()
        assertEquals(View.VISIBLE, row.visibility)
    }

    @Test fun `hide cancels pending attempt and next show retries`() {
        val session = start()
        deliver(false)
        session.onHidden()
        val count = modes.size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(count, modes.size)
        assertTrue(reports.last().contains("visible=false"))
        session.onShown()
        deliver(true)
        preDraw()
        assertEquals("ALWAYS", requestedMode)
        assertEquals(View.VISIBLE, row.visibility)
    }

    @Test fun `late geometry after fallback requests a fresh camera delivery`() {
        start()
        deliver(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        deliver(true)
        assertEquals("ALWAYS", requestedMode)
        assertEquals(View.INVISIBLE, row.visibility)
        deliver(true)
        preDraw()
        assertEquals(View.VISIBLE, row.visibility)
    }
    @Test fun `temporary reattachment resumes only while service requests shown`() {
        val session = start()
        deliver(true)
        preDraw()
        val params = root.layoutParams
        holder.removeView(root)
        assertEquals("DEFAULT", requestedMode)
        holder.addView(root, params)
        deliver(true)
        preDraw()
        assertEquals("ALWAYS", requestedMode)
        session.onHidden()
        val count = modes.count { it }
        holder.removeView(root)
        holder.addView(root, params)
        deliver(true)
        preDraw()
        assertEquals(count, modes.count { it })
        assertEquals("DEFAULT", requestedMode)
    }

}
