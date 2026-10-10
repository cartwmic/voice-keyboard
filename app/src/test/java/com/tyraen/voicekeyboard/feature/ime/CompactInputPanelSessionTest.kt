package com.tyraen.voicekeyboard.feature.ime

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.Gravity
import android.view.View
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
    private val root = ConstraintLayout(activity)
    private val row = View(activity)
    private var insets = WindowInsetsCompat.Builder().build()
    private var requestedMode = "DEFAULT"
    private val modes = mutableListOf<Boolean>()
    private val reports = mutableListOf<String>()
    private var session: CompactInputPanelSession? = null

    private fun start(): CompactInputPanelSession {
        root.addView(row, ConstraintLayout.LayoutParams(0, 56).apply {
            startToStart = 0; endToEnd = 0; topToTop = 0
        })
        holder.addView(root, FrameLayout.LayoutParams(-1, 64, Gravity.BOTTOM))
        activity.setContentView(holder)
        holder.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        holder.layout(0, 0, 320, 640)
        return CompactInputPanelSession(root, row, 64, true,
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

    private fun preDraw() { root.viewTreeObserver.dispatchOnPreDraw() }

    @After fun cleanup() {
        session?.dispose()
        activityController.pause().stop().destroy()
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
