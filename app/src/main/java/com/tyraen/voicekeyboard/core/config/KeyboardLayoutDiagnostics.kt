package com.tyraen.voicekeyboard.core.config

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.tyraen.voicekeyboard.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Local layout snapshots only. Callers must not include editor text, audio, or provider settings. */
object KeyboardLayoutDiagnostics {
    private const val PREFS = "keyboard_layout_diagnostics"
    private const val LATEST = "latest"
    private const val LAST_VISIBLE = "last_visible"
    private const val LIFECYCLE_HISTORY = "lifecycle_history"
    private const val EVENT_SEPARATOR = "\n---\n"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Rare service boundaries are durable checkpoints, even if no view/inset callback arrives. */
    @SuppressLint("ApplySharedPref")
    fun recordLifecycle(
        context: Context,
        event: String,
        details: String,
        layout: KeyboardLayoutConfig = KeyboardLayoutPreferences.current(context)
    ): Boolean {
        val prefs = preferences(context)
        val entry = buildString {
            appendLine("Captured UTC: ${utc(System.currentTimeMillis())}")
            appendLine("Producer build: ${BuildConfig.VERSION_NAME}; package: ${context.packageName}")
            appendLine("Event: $event")
            appendLine("Bound layout: ${layout.description()}")
            append(details.trimEnd())
        }
        val history = (prefs.getString(LIFECYCLE_HISTORY, "").orEmpty()
            .split(EVENT_SEPARATOR).filter { it.isNotBlank() } + entry).takeLast(8)
        // commit also waits for pending apply writes to this preferences file. These infrequent
        // checkpoints preserve the latest geometry before settings/IME handoff or service death.
        return prefs.edit().putString(LIFECYCLE_HISTORY, history.joinToString(EVENT_SEPARATOR)).commit()
    }

    private fun utc(timeMs: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timeMs))

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
        val editor = preferences(context).edit()
        editor.putSnapshot(LATEST, snapshot)
        // Hiding the IME or opening settings can trigger another geometry callback. Keep the
        // useful visible measurement instead of replacing it with the hidden-window bounds.
        if (visible) editor.putSnapshot(LAST_VISIBLE, snapshot)
        editor.apply()
    }

    fun read(context: Context): String {
        val prefs = preferences(context)
        val current = KeyboardLayoutPreferences.current(context)
        val latest = prefs.snapshot(LATEST)
        val lastVisible = prefs.snapshot(LAST_VISIBLE)
        return buildString {
            appendLine("Keyboard layout diagnostics (local snapshots)")
            appendLine("Reader build: ${BuildConfig.VERSION_NAME}; package: ${context.packageName}")
            appendLine("Current settings (read now): ${current.description()}")
            appendLine("Snapshots describe a previously captured layout, not a live measurement.")
            appendLine()
            if (latest == null) {
                appendLine("No geometry snapshot was captured; lifecycle and build details are still available.")
            } else {
                appendSnapshot("Latest layout event", latest, current)
                // Keep a hidden/unmeasured latest event: it is useful evidence, not 'no diagnostics'.
                if (latest.visible != true && lastVisible != null) {
                    appendLine()
                    appendLine()
                    appendSnapshot("Last visible keyboard snapshot", lastVisible, current)
                } else if (latest.visible == false) {
                    appendLine()
                    appendLine("No controls-visible frame was recorded; the latest event above remains available.")
                }
            }
            appendLine()
            appendLine()
            appendLine("IME lifecycle checkpoints (most recent 8)")
            append(prefs.getString(LIFECYCLE_HISTORY, null)
                ?: "No IME lifecycle checkpoint has been recorded by this build. Geometry and reader context, if present above, remain available.")
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
            val utc = utc(capturedAt)
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
                appendLine("Settings changed since this snapshot. Captured and current settings are shown separately.")
            }
        }
        append(snapshot.report.trimEnd())
    }

    private fun KeyboardLayoutConfig.description(): String =
        "compact=$compact; heightDp=$heightDp; besideCameras=$besideCameras"
}
