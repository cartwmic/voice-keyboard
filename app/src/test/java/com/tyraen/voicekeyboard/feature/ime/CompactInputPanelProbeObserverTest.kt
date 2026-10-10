package com.tyraen.voicekeyboard.feature.ime

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
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

/** Separate from the layout behavior regression suite: the observer must stay optional. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp-mdpi")
class CompactInputPanelProbeObserverTest {
    private val controller = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity = controller.get()
    private val holder = FrameLayout(activity)
    private var forceLayoutPending = false
    private val root = object : ConstraintLayout(activity) {
        override fun isLayoutRequested(): Boolean = forceLayoutPending || super.isLayoutRequested()
    }
    private val row = View(activity)
    private val modes = mutableListOf<Boolean>()
    private var requestedCamera = false
    private var session: CompactInputPanelSession? = null
    private data class Frame(val camera: Boolean, val epoch: Long, val phase: String,
        val beforeChange: Boolean, val windowCamera: Boolean)
    private val frames = mutableListOf<Frame>()

    private fun start(besideCameras: Boolean = true, failObserver: Boolean = false): CompactInputPanelSession {
        root.addView(row, ConstraintLayout.LayoutParams(0, 56).apply {
            startToStart = 0; endToEnd = 0; topToTop = 0
        })
        holder.addView(root, FrameLayout.LayoutParams(-1, 64, Gravity.BOTTOM))
        activity.setContentView(holder)
        measure()
        return CompactInputPanelSession(root, row, 64, besideCameras,
            onDiagnostics = { _, _ -> },
            onCameraModeRequested = {
                requestedCamera = it
                modes.add(it)
            },
            rootInsetsProvider = { WindowInsetsCompat.Builder().build() },
            onMeasuredWindowFrame = { camera, epoch, phase, beforeChange ->
                frames.add(Frame(camera, epoch, phase, beforeChange, requestedCamera))
                if (failObserver) throw IllegalStateException("measurement failed")
            }
        ).also { session = it; it.onShown() }
    }

    private fun measure() {
        holder.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        holder.layout(0, 0, 320, 640)
    }

    private fun measuredFrame() {
        shadowOf(Looper.getMainLooper()).idle()
        measure()
        root.viewTreeObserver.dispatchOnPreDraw()
    }

    @After fun cleanup() {
        session?.dispose()
        controller.pause().stop().destroy()
    }

    @Test fun `observer cannot accept the old traversal before the posted mode barrier`() {
        start()
        measure()
        root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(frames.isEmpty())
        assertEquals(listOf(true), modes)
        measuredFrame()
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.camera && it.windowCamera && !it.beforeChange })
        assertEquals(1, frames.map { it.epoch }.distinct().size)
        assertEquals(View.INVISIBLE, row.visibility)
    }

    @Test fun `fallback captures final ALWAYS before mode change then measures DEFAULT after a new barrier`() {
        start()
        measuredFrame()
        val alwaysEpoch = frames.first().epoch
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        val final = frames.single { it.beforeChange }
        assertTrue(final.camera)
        assertTrue(final.windowCamera)
        assertEquals(alwaysEpoch, final.epoch)
        assertEquals("FALLBACK", final.phase)
        assertEquals(listOf(true, false), modes)
        measuredFrame()
        measuredFrame()
        val safe = frames.first { !it.camera }
        assertTrue(frames.indexOf(final) < frames.indexOf(safe))
        assertFalse(safe.beforeChange)
        assertFalse(safe.windowCamera)
        assertTrue(safe.epoch > alwaysEpoch)
        assertEquals(View.VISIBLE, row.visibility)
    }

    @Test fun `a diagnostic exception cannot prevent camera fallback or normal controls`() {
        start(failObserver = true)
        measuredFrame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        measuredFrame()
        measuredFrame()
        assertEquals(listOf(true, false), modes)
        assertTrue(frames.any { it.beforeChange })
        assertEquals(View.VISIBLE, row.visibility)
        assertFalse(requestedCamera)
    }

    @Test fun `hidden or disposed session makes no new diagnostic measurements`() {
        val session = start(besideCameras = false)
        measuredFrame()
        assertEquals(View.VISIBLE, row.visibility)
        assertTrue(frames.isNotEmpty())
        session.onHidden()
        val count = frames.size
        measuredFrame()
        session.dispose()
        measuredFrame()
        assertEquals(count, frames.size)
    }

    @Test fun `unsettled frame is never promoted to final measurement at fallback`() {
        start()
        measuredFrame()
        // Keep the completed-layout gate false even if Robolectric schedules a traversal
        // while advancing its looper to the timeout.
        forceLayoutPending = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(501))
        assertFalse(frames.any { it.beforeChange })
        assertEquals(listOf(true, false), modes)
        forceLayoutPending = false
        measuredFrame()
        measuredFrame()
        assertEquals(View.VISIBLE, row.visibility)
    }
}
