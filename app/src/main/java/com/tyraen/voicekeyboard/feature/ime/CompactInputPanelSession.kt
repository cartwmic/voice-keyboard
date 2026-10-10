package com.tyraen.voicekeyboard.feature.ime

import android.view.View
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/** Owns one compact view's window-readiness cycle, safe placement and diagnostic events. */
class CompactInputPanelSession(
    private val root: ConstraintLayout,
    private val row: View,
    heightDp: Int,
    besideCameras: Boolean,
    private val onDiagnostics: (String, Boolean) -> Unit,
    private val onCameraModeRequested: (Boolean) -> Unit,
    private val rootInsetsProvider: () -> WindowInsetsCompat? = { ViewCompat.getRootWindowInsets(root) }
) {
    private val heightDp = heightDp.coerceIn(56, 72)
    private val readiness = CameraGeometryReadiness(besideCameras)
    private var latestInsets: WindowInsetsCompat? = null
    private var timeout: Runnable? = null
    private var shown = false
    private var requestedShown = false
    private var disposed = false
    private var cameraModeRequested = false
    private var insetsForRequestedMode = false
    private var revealEpoch = 0L
    private var lastReport = ""
    private var lastGeometry = "No inset measurement yet."
    private var pendingReveal: ViewTreeObserver.OnPreDrawListener? = null
    private val density get() = root.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (requestedShown) resumeShown()
            ViewCompat.requestApplyInsets(view)
            update()
        }
        override fun onViewDetachedFromWindow(view: View) {
            // The service may rebuild this input view during a fold/configuration change.
            pauseShown()
        }
    }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            latestInsets = insets
            insetsForRequestedMode = true
            update()
            insets
        }
        root.addOnLayoutChangeListener(layoutListener)
        root.addOnAttachStateChangeListener(attachListener)
    }

    fun onShown() {
        if (disposed) return
        requestedShown = true
        resumeShown()
    }

    private fun resumeShown() {
        if (disposed || shown) return
        shown = true
        latestInsets = rootInsetsProvider() ?: latestInsets
        handle(readiness.onShown())
        ViewCompat.requestApplyInsets(root)
        update(forceReport = true)
    }

    fun onHidden() {
        requestedShown = false
        pauseShown()
    }

    private fun pauseShown() {
        if (disposed || !shown) return
        shown = false
        revealEpoch++
        removePendingReveal()
        handle(readiness.onHidden())
        emit(force = true)
    }

    fun dispose() {
        if (disposed) return
        onHidden()
        disposed = true
        timeout?.let(root::removeCallbacks)
        timeout = null
        removePendingReveal()
        ViewCompat.setOnApplyWindowInsetsListener(root, null)
        root.removeOnLayoutChangeListener(layoutListener)
        root.removeOnAttachStateChangeListener(attachListener)
    }

    private fun handle(effects: List<CameraGeometryReadiness.Effect>) {
        for (effect in effects) when (effect) {
            CameraGeometryReadiness.Effect.RequestCameraMode -> requestMode(true)
            CameraGeometryReadiness.Effect.RequestSafeMode -> requestMode(false)
            CameraGeometryReadiness.Effect.CancelTimeout -> {
                timeout?.let(root::removeCallbacks)
                timeout = null
            }
            is CameraGeometryReadiness.Effect.ScheduleTimeout -> {
                timeout?.let(root::removeCallbacks)
                timeout = Runnable {
                    timeout = null
                    if (!disposed && shown) {
                        handle(readiness.onTimeout(effect.token))
                        update(forceReport = true)
                    }
                }.also { root.postDelayed(it, effect.delayMs) }
            }
        }
    }

    private fun requestMode(camera: Boolean) {
        revealEpoch++
        removePendingReveal()
        // A newly extended window may temporarily lack camera bounds. Never expose controls
        // in that unverified area; reveal them after safe placement and the next traversal.
        if (shown) row.visibility = View.INVISIBLE
        cameraModeRequested = camera
        insetsForRequestedMode = false
        onCameraModeRequested(camera) // Synchronous: diagnostics below see the new attribute.
        root.requestLayout()
        ViewCompat.requestApplyInsets(root)
    }

    private fun update(forceReport: Boolean = false) {
        if (disposed) return
        val delivered = latestInsets
        val rootInsets = rootInsetsProvider() ?: delivered
        val cutout = rootInsets?.displayCutout
        if (shown) {
            handle(readiness.observe(CameraGeometryReadiness.Observation(
                displayId = root.display?.displayId,
                widthPx = root.width,
                heightPx = root.height,
                attached = root.isAttachedToWindow,
                insetsReady = rootInsets != null && insetsForRequestedMode,
                hasGeometry = !cutout?.boundingRects.isNullOrEmpty(),
                displayGeometryKey = root.display?.let { "${it.mode.physicalWidth}x${it.mode.physicalHeight}@${it.rotation}" }
            )))
        }
        val safe = delivered?.getInsets(WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
        val waterfall = cutout?.waterfallInsets ?: Insets.NONE
        val location = IntArray(2)
        root.getLocationInWindow(location)
        val useGeometry = readiness.useGeometry && shown
        val nominalTop = location[1] + root.height - dp(heightDp) - if (useGeometry) waterfall.bottom else 0
        val bounds = cutout?.boundingRects.orEmpty().map { rect ->
            CompactCameraPlacement.Bounds(
                rect.left - location[0] - dp(4), rect.top - nominalTop - dp(4),
                rect.right - location[0] + dp(4), rect.bottom - nominalTop + dp(4)
            )
        }
        val geometryWidth = (root.width - waterfall.left - waterfall.right).coerceAtLeast(0)
        val adjustedBounds = bounds.map { it.copy(left = it.left - waterfall.left, right = it.right - waterfall.left) }
        val placement = if (useGeometry && root.width > 0 && root.height > 0) {
            CompactCameraPlacement.choose(geometryWidth, dp(heightDp), dp(260), adjustedBounds).let {
                it.copy(leftPadding = it.leftPadding + waterfall.left, rightPadding = it.rightPadding + waterfall.right)
            }
        } else {
            val reason = when (readiness.phase) {
                CameraGeometryReadiness.Phase.FALLBACK -> "missing_geometry_system_default"
                CameraGeometryReadiness.Phase.WAITING, CameraGeometryReadiness.Phase.SETTLING -> "awaiting_geometry"
                else -> "safe_insets"
            }
            CompactCameraPlacement.Placement(safe.left, safe.right, safe.bottom, reason)
        }
        val left = dp(4) + maxOf(placement.leftPadding, waterfall.left)
        val right = dp(4) + maxOf(placement.rightPadding, waterfall.right)
        val bottom = dp(4) + if (useGeometry) placement.bottomPadding + waterfall.bottom
            else maxOf(placement.bottomPadding, waterfall.bottom)
        if (root.paddingLeft != left || root.paddingRight != right || root.paddingBottom != bottom) {
            root.setPadding(left, dp(4), right, bottom)
        }
        if (shown) {
            val canReveal = insetsForRequestedMode && (!cameraModeRequested || useGeometry)
            if (canReveal) revealAfterTraversal() else row.visibility = View.INVISIBLE
        }
        val screen = IntArray(2)
        root.getLocationOnScreen(screen)
        lastGeometry = buildString {
            appendLine("Input screen bounds: ${screen[0]},${screen[1]} ${root.width}x${root.height}")
            appendLine("Input window origin: ${location[0]},${location[1]}; nominal row top: $nominalTop")
            appendLine("Density: $density; display dp: ${root.resources.configuration.screenWidthDp}x${root.resources.configuration.screenHeightDp}")
            appendLine("Cutout rectangles (window): ${cutout?.boundingRects.orEmpty()}")
            appendLine("Delivered safe cutout insets: $safe; waterfall: $waterfall")
            appendLine("Root navigation insets: ${rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())}")
            appendLine("Row: $heightDp dp; side-space minimum: 260 dp (${dp(260)} px)")
            if (bounds.isNotEmpty()) {
                val width = widestSpan(geometryWidth, dp(heightDp), adjustedBounds)
                appendLine("Largest camera-free span: $width px (${width / density} dp)")
            } else appendLine("Largest camera-free span: unknown (no camera rectangles)")
            appendLine("Placement: ${placement.reason}; padding px: $left,${dp(4)},$right,$bottom")
            if (useGeometry && placement.reason == "clear") {
                appendLine("Reported cameras do not overlap this row; this does not establish an OS restriction.")
            }
        }
        emit(forceReport)
    }

    private fun revealAfterTraversal() {
        if (row.visibility == View.VISIBLE || pendingReveal != null) return
        val epoch = revealEpoch
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                removePendingReveal()
                if (!disposed && shown && epoch == revealEpoch &&
                    insetsForRequestedMode && (!cameraModeRequested || readiness.useGeometry)
                ) {
                    row.visibility = View.VISIBLE
                    emit(force = true)
                }
                return true
            }
        }
        pendingReveal = listener
        root.viewTreeObserver.addOnPreDrawListener(listener)
        root.invalidate()
    }

    private fun removePendingReveal() {
        pendingReveal?.let { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(it) }
        pendingReveal = null
    }

    private fun emit(force: Boolean = false) {
        val report = "Camera readiness: ${readiness.phase}; panel shown: $shown; controls visible: ${row.visibility == View.VISIBLE}\n" + lastGeometry
        if (force || report != lastReport) {
            lastReport = report
            onDiagnostics(report, shown && row.visibility == View.VISIBLE)
        }
    }

    private fun widestSpan(width: Int, height: Int, bounds: List<CompactCameraPlacement.Bounds>): Int {
        var cursor = 0
        var best = 0
        for (b in bounds.filter { it.top < height && it.bottom > 0 && it.left < it.right && it.top < it.bottom }
            .sortedBy { it.left }) {
            val left = b.left.coerceIn(0, width)
            val right = b.right.coerceIn(0, width)
            best = maxOf(best, left - cursor)
            cursor = maxOf(cursor, right)
        }
        return maxOf(best, width - cursor)
    }
}
