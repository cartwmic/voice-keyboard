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
import com.tyraen.voicekeyboard.R
import kotlin.math.roundToInt

/** Reuses the existing controls and their actions, without a second set of IME bindings. */
object CompactInputPanel {
    fun apply(
        root: ConstraintLayout,
        heightDp: Int,
        besideCameras: Boolean = false,
        onDiagnostics: (String, Boolean) -> Unit = { _, _ -> },
        onCameraModeRequested: (Boolean) -> Unit = {},
        onMeasuredWindowFrame: (Boolean, Long, String, Boolean) -> Unit = { _, _, _, _ -> }
    ): CompactInputPanelSession {
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
        return CompactInputPanelSession(root, row, heightDp, besideCameras, onDiagnostics, onCameraModeRequested,
            onMeasuredWindowFrame = onMeasuredWindowFrame)
    }
}
