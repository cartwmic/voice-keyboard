package com.tyraen.voicekeyboard.core.config

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Local layout snapshots only. Callers must not include editor text, audio, or provider settings. */
object KeyboardLayoutDiagnostics {
    private const val PREFS = "keyboard_layout_diagnostics"
    private const val LATEST = "latest"
    private const val LAST_VISIBLE = "last_visible"

    fun save(
        context: Context,
        report: String,
        visible: Boolean = true,
        layout: KeyboardLayoutConfig = KeyboardLayoutPreferences.current(context)
    ) {
        val snapshot = Snapshot(
            report = report,
            capturedAtMs = System.currentTimeMillis(),
            visible = visible,
            layout = layout
        )
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        editor.putSnapshot(LATEST, snapshot)
        // Hiding the IME or opening settings can trigger another geometry callback. Keep the
        // useful visible measurement instead of replacing it with the hidden-window bounds.
        if (visible) editor.putSnapshot(LAST_VISIBLE, snapshot)
        editor.apply()
    }

    fun read(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = KeyboardLayoutPreferences.current(context)
        val latest = prefs.snapshot(LATEST)
        val lastVisible = prefs.snapshot(LAST_VISIBLE)
        return buildString {
            appendLine("Keyboard layout diagnostics (local snapshots)")
            appendLine("Current settings (read now): ${current.description()}")
            appendLine("Snapshots describe a previously captured layout, not a live measurement.")
            appendLine()
            if (latest == null) {
                append("No compact keyboard measurement yet. Open the compact keyboard, then copy this report again.")
                return@buildString
            }
            appendSnapshot("Latest layout event", latest, current)
            // A visible latest event already contains the same measurement; show it only once.
            if (latest.visible != true && lastVisible != null) {
                appendLine()
                appendLine()
                appendSnapshot("Last visible keyboard snapshot", lastVisible, current)
            } else if (latest.visible == false) {
                appendLine()
                appendLine()
                append("No visible keyboard snapshot yet. Open the keyboard, then copy this report again.")
            }
        }
    }

    private data class Snapshot(
        val report: String,
        val capturedAtMs: Long?,
        val visible: Boolean?,
        val layout: KeyboardLayoutConfig?
    )

    private fun SharedPreferences.Editor.putSnapshot(prefix: String, snapshot: Snapshot) {
        putString("${prefix}_report", snapshot.report)
        putLong("${prefix}_captured_at_ms", requireNotNull(snapshot.capturedAtMs))
        putBoolean("${prefix}_visible", requireNotNull(snapshot.visible))
        val layout = requireNotNull(snapshot.layout)
        putBoolean("${prefix}_compact", layout.compact)
        putInt("${prefix}_height_dp", layout.heightDp)
        putBoolean("${prefix}_beside_cameras", layout.besideCameras)
    }

    private fun SharedPreferences.snapshot(prefix: String): Snapshot? {
        // The original implementation stored just latest_report. Keep it readable, but do not
        // invent a capture time, visibility, or settings for an older app version's snapshot.
        val report = getString("${prefix}_report", null) ?: return null
        val capturedAt = if (contains("${prefix}_captured_at_ms")) getLong("${prefix}_captured_at_ms", 0L) else null
        val visible = if (contains("${prefix}_visible")) getBoolean("${prefix}_visible", false) else null
        val layout = if (contains("${prefix}_compact") && contains("${prefix}_height_dp") &&
            contains("${prefix}_beside_cameras")
        ) KeyboardLayoutConfig(
            compact = getBoolean("${prefix}_compact", false),
            heightDp = getInt("${prefix}_height_dp", KeyboardLayoutPreferences.DEFAULT_HEIGHT_DP),
            besideCameras = getBoolean("${prefix}_beside_cameras", false)
        ) else null
        return Snapshot(report, capturedAt, visible, layout)
    }

    private fun StringBuilder.appendSnapshot(title: String, snapshot: Snapshot, current: KeyboardLayoutConfig) {
        appendLine(title)
        val capturedAt = snapshot.capturedAtMs
        if (capturedAt != null) {
            val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(capturedAt))
            appendLine("Captured UTC: $utc")
            appendLine("Captured epoch ms: $capturedAt")
        } else {
            appendLine("Captured UTC: unknown (legacy snapshot)")
            appendLine("Captured epoch ms: unknown")
        }
        appendLine("Keyboard visible: ${snapshot.visible ?: "unknown (legacy snapshot)"}")
        if (snapshot.layout == null) {
            appendLine("Settings at capture: unknown (legacy snapshot)")
        } else {
            appendLine("Settings at capture: ${snapshot.layout.description()}")
            if (snapshot.layout != current) {
                appendLine("Settings changed since this snapshot. Open the keyboard to capture the new layout.")
            }
        }
        append(snapshot.report.trimEnd())
    }

    private fun KeyboardLayoutConfig.description(): String =
        "compact=$compact; heightDp=$heightDp; besideCameras=$besideCameras"
}
