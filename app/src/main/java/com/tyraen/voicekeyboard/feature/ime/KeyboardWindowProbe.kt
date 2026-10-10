package com.tyraen.voicekeyboard.feature.ime

import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tyraen.voicekeyboard.BuildConfig
import com.tyraen.voicekeyboard.core.config.KeyboardLayoutDiagnostics
import java.util.UUID

/** Read-only, bounded frame capture. Never changes a window attribute, layout or IME state. */
internal class KeyboardWindowProbe(
    private val context: Context,
    private val input: View,
    private val window: Window?
) {
    private var probeId: String? = null
    private var capturedEpoch: Long? = null

    fun onModeRequested(camera: Boolean) {
        if (camera || probeId == null) {
            probeId = UUID.randomUUID().toString()
            capturedEpoch = null
            KeyboardLayoutDiagnostics.beginWindowProbe(context, requireNotNull(probeId))
        }
    }

    fun onMeasuredFrame(camera: Boolean, epoch: Long, phase: String, beforeModeChange: Boolean = false) {
        val id = probeId ?: return
        if (!input.isAttachedToWindow || input.width <= 0 || input.height <= 0 ||
            input.isLayoutRequested || (!beforeModeChange && epoch == capturedEpoch)) return
        val report = buildString {
            appendLine("Build: ${BuildConfig.VERSION_NAME}; target SDK: ${context.applicationInfo.targetSdkVersion}; Android API: ${Build.VERSION.SDK_INT}")
            appendLine("Probe ID: $id; request epoch: $epoch")
            appendLine("Capture: ${if (beforeModeChange) "last measured frame before mode reversion" else "first measured pre-draw after posted mode traversal"}; readiness phase: $phase")
            appendLine("Requested mode for this frame: ${if (camera) "ALWAYS" else "DEFAULT"}")
            section("Window attribute readback") {
                appendLine("Window attribute readback: ${if (Build.VERSION.SDK_INT >= 28) window?.attributes?.layoutInDisplayCutoutMode else "unsupported"}")
            }
            appendLine("Readback is a requested attribute, not proof of effective system policy.")
            section("Display identity/mode") {
                val display = input.display
                appendLine("Input display ID: ${display?.displayId}; rotation: ${display?.rotation}")
                appendLine("Display.Mode physical pixels (not placement coordinates): ${display?.mode?.physicalWidth}x${display?.mode?.physicalHeight}")
            }
            section("IME resource configuration") {
                val config = input.resources.configuration
                appendLine("IME resource configuration dp: ${config.screenWidthDp}x${config.screenHeightDp}; density: ${input.resources.displayMetrics.density}")
            }
            appendLine("Configuration describes this context's available area, not the physical panel.")
            section("Input bounds") { appendBounds("Input", input) }
            section("Decor bounds") { appendBounds("Decor", window?.decorView) }
            section("Input root insets") { appendInsets("Input root insets", ViewCompat.getRootWindowInsets(input)) }
            section("Decor root insets") { appendInsets("Decor root insets", window?.decorView?.let(ViewCompat::getRootWindowInsets)) }
            appendLine("WindowMetrics source: IME Window.windowManager (window/visual context)")
            section("WindowMetrics") { if (Build.VERSION.SDK_INT >= 30) {
                val manager = window?.windowManager
                if (manager == null) appendLine("WindowMetrics: unavailable (no window manager)")
                else {
                    appendMetrics("Current", manager, maximum = false)
                    appendMetrics("Maximum", manager, maximum = true)
                }
            } else appendLine("WindowMetrics: unsupported before API 30") }
            append("No editor contents, audio or provider settings are captured.")
        }
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, id, camera, report)
        capturedEpoch = epoch
    }

    private fun StringBuilder.appendBounds(label: String, view: View?) {
        if (view == null) { appendLine("$label bounds: unavailable"); return }
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        appendLine("$label screen bounds: ${origin[0]},${origin[1]} ${view.width}x${view.height}; attached: ${view.isAttachedToWindow}; display: ${view.display?.displayId}")
    }

    private inline fun StringBuilder.section(label: String, block: StringBuilder.() -> Unit) {
        try { block() } catch (error: RuntimeException) {
            appendLine("$label unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun StringBuilder.appendMetrics(label: String, manager: WindowManager, maximum: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val metrics = if (maximum) manager.maximumWindowMetrics else manager.currentWindowMetrics
            appendLine("$label WindowMetrics bounds: ${metrics.bounds}")
            appendInsets("$label WindowMetrics insets", WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets))
        } catch (error: RuntimeException) {
            // Class only: exception messages can contain unrelated application data.
            appendLine("$label WindowMetrics unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun StringBuilder.appendInsets(label: String, insets: WindowInsetsCompat?) {
        if (insets == null) { appendLine("$label: null (unavailable)"); return }
        appendLine("$label: present; consumed: ${insets.isConsumed}")
        val cutout = insets.displayCutout
        if (cutout == null) appendLine("  DisplayCutout: null")
        else {
            appendLine("  DisplayCutout: present; rectangles (window-relative): ${cutout.boundingRects}")
            appendLine("  Cutout safe L/T/R/B: ${cutout.safeInsetLeft}/${cutout.safeInsetTop}/${cutout.safeInsetRight}/${cutout.safeInsetBottom}; waterfall: ${cutout.waterfallInsets}")
        }
        appendLine("  displayCutout type insets: ${insets.getInsets(WindowInsetsCompat.Type.displayCutout())}")
        appendLine("  navigationBars insets: ${insets.getInsets(WindowInsetsCompat.Type.navigationBars())}")
        appendLine("  systemBars insets: ${insets.getInsets(WindowInsetsCompat.Type.systemBars())}")
    }
}
