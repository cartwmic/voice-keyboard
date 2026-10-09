package com.tyraen.voicekeyboard.feature.ime

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tyraen.voicekeyboard.R
import kotlin.math.roundToInt

/** Reuses the existing controls and their actions, without a second set of IME bindings. */
object CompactInputPanel {
    fun apply(
        root: ConstraintLayout,
        heightDp: Int,
        besideCameras: Boolean = false,
        onDiagnostics: (String) -> Unit = {},
        onMissingCameraGeometry: () -> Unit = {}
    ) {
        val density = root.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val previous = root.findViewById<ImageButton>(R.id.btnPreviousKeyboard)
        val mic = root.findViewById<FrameLayout>(R.id.micFrame)
        val backspace = root.findViewById<ImageButton>(R.id.btnBackspace)
        val enter = root.findViewById<ImageButton>(R.id.btnEnter)
        val status = root.findViewById<TextView>(R.id.statusText)
        val progress = root.findViewById<ProgressBar>(R.id.progressBar)
        val micButton = root.findViewById<ImageButton>(R.id.btnMic)
        val badge = root.findViewById<TextView>(R.id.queueBadge)

        fun detach(view: View) { (view.parent as? ViewGroup)?.removeView(view) }
        listOf(previous, mic, backspace, enter, status).forEach(::detach)
        // Keep the remaining controls bound for the common controller. A GONE ancestor means
        // queue, clipboard, language and post-processing updates can never add a second row.
        val hidden = FrameLayout(root.context).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        while (root.childCount > 0) {
            val child = root.getChildAt(0)
            root.removeViewAt(0)
            hidden.addView(child)
        }
        root.addView(hidden, ConstraintLayout.LayoutParams(0, 0))
        listOf<View>(micButton, progress, badge).forEach(::detach)
        while (mic.childCount > 0) {
            val child = mic.getChildAt(0)
            mic.removeViewAt(0)
            hidden.addView(child)
        }
        micButton.setImageDrawable(null)
        micButton.setPadding(0, 0, 0, 0)
        mic.addView(micButton, FrameLayout.LayoutParams(-1, -1))
        status.apply {
            setPadding(dp(24), 0, dp(24), 0)
            maxLines = 1
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(android.graphics.Color.rgb(20, 35, 28))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
        }
        mic.addView(status, FrameLayout.LayoutParams(-1, -1))
        mic.addView(progress, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(4)
        })
        mic.addView(badge, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END or Gravity.TOP))

        val row = LinearLayout(root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(previous, backspace, enter).forEach { key ->
            key.minimumWidth = dp(48)
            key.minimumHeight = dp(48)
            key.setPadding(dp(10), dp(10), dp(10), dp(10))
            key.setBackgroundResource(R.drawable.key_bg)
        }
        row.addView(previous, LinearLayout.LayoutParams(dp(48), -1))
        row.addView(mic, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        row.addView(backspace, LinearLayout.LayoutParams(dp(48), -1).apply { marginEnd = dp(4) })
        row.addView(enter, LinearLayout.LayoutParams(dp(48), -1))
        root.setPadding(dp(4), dp(4), dp(4), dp(4))
        root.addView(row, ConstraintLayout.LayoutParams(0, dp(heightDp.coerceIn(56, 72) - 8)).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        })
        // Navigation remains owned by the IME framework. Never pad by ime() (our own height),
        // and never add navigationBars() again on top of the framework's fitted window.
        var latestInsets: WindowInsetsCompat? = null
        var lastReport = ""
        var cameraModeActive = besideCameras
        var cameraFallbackReason: String? = null
        fun applyInsets() {
            val insets = latestInsets ?: return
            val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val rootInsets = ViewCompat.getRootWindowInsets(root) ?: insets
            val cutout = rootInsets.displayCutout
            val waterfall = cutout?.waterfallInsets ?: androidx.core.graphics.Insets.NONE
            if (cameraModeActive && root.isAttachedToWindow && root.width > 0 && root.height > 0 &&
                cutout?.boundingRects.isNullOrEmpty()
            ) {
                // Missing geometry is not proof that a physical camera is absent. Restore the
                // system-safe window rather than leave buttons in an unverified cutout region.
                cameraModeActive = false
                cameraFallbackReason = "missing_geometry_system_default"
                root.post { onMissingCameraGeometry() }
            }
            val location = IntArray(2)
            root.getLocationInWindow(location)
            // Normalize against the nominal bottom row, not the currently padded row. Otherwise
            // adding bottom padding moves the origin and oscillates between padded/unpadded.
            val nominalTop = location[1] + root.height - dp(heightDp.coerceIn(56, 72)) -
                if (cameraModeActive) waterfall.bottom else 0
            val bounds = cutout?.boundingRects.orEmpty().map { rect ->
                CompactCameraPlacement.Bounds(
                    rect.left - location[0] - dp(4), rect.top - nominalTop - dp(4),
                    rect.right - location[0] + dp(4), rect.bottom - nominalTop + dp(4)
                )
            }
            val placement = when {
                !cameraModeActive -> CompactCameraPlacement.Placement(safe.left, safe.right, safe.bottom, cameraFallbackReason ?: "safe_insets")
                root.width == 0 || root.height == 0 ->
                    CompactCameraPlacement.Placement(safe.left, safe.right, safe.bottom, "awaiting_layout")
                bounds.isEmpty() && (safe.left > 0 || safe.right > 0 || safe.bottom > 0) ->
                    CompactCameraPlacement.Placement(safe.left, safe.right, safe.bottom, "no_cutout_rectangles")
                else -> CompactCameraPlacement.choose(
                    (root.width - waterfall.left - waterfall.right).coerceAtLeast(0),
                    dp(heightDp.coerceIn(56, 72)), dp(260),
                    bounds.map { it.copy(left = it.left - waterfall.left, right = it.right - waterfall.left) }
                ).let { it.copy(leftPadding = it.leftPadding + waterfall.left, rightPadding = it.rightPadding + waterfall.right) }
            }
            val left = dp(4) + maxOf(placement.leftPadding, waterfall.left)
            val right = dp(4) + maxOf(placement.rightPadding, waterfall.right)
            val bottom = dp(4) + if (cameraModeActive && bounds.isNotEmpty()) {
                placement.bottomPadding + waterfall.bottom
            } else maxOf(placement.bottomPadding, waterfall.bottom)
            if (root.paddingLeft != left || root.paddingRight != right || root.paddingBottom != bottom) {
                root.setPadding(left, dp(4), right, bottom)
            }
            val screen = IntArray(2)
            root.getLocationOnScreen(screen)
            val report = buildString {
                appendLine("Input screen bounds: ${screen[0]},${screen[1]} ${root.width}x${root.height}")
                appendLine("Input window origin: ${location[0]},${location[1]}; nominal row top: $nominalTop")
                appendLine("Cutout rectangles (window): ${cutout?.boundingRects.orEmpty()}")
                appendLine("Delivered safe cutout insets: $safe")
                appendLine("Root navigation insets: ${rootInsets.getInsets(WindowInsetsCompat.Type.navigationBars())}")
                appendLine("Row: ${heightDp.coerceIn(56, 72)} dp; side-space minimum: 260 dp")
                appendLine("Placement: ${placement.reason}; padding px: $left,${dp(4)},$right,$bottom")
                if (cameraModeActive && placement.reason == "clear") {
                    appendLine("No camera overlaps this row. The phone may already be keeping the IME above it.")
                }
            }
            if (report != lastReport) { lastReport = report; onDiagnostics(report) }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            latestInsets = insets
            applyInsets()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyInsets() }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { ViewCompat.requestApplyInsets(view) }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }
}
