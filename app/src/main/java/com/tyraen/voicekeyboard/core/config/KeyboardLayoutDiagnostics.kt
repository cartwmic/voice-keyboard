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
    private const val WINDOW_PROBE_ID = "window_probe_id"
    private const val WINDOW_PROBE_STARTED_AT_MS = "window_probe_started_at_ms"
    private const val WINDOW_PROBE_ALWAYS = "window_probe_always"
    private const val WINDOW_PROBE_DEFAULT = "window_probe_default"
    private const val PREVIOUS_WINDOW_PROBE_PREFIX = "previous_"

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

    /** Retain one comparison across recovery, preferring a complete pair over a partial one. */
    @Synchronized
    fun beginWindowProbe(context: Context, probeId: String) {
        val prefs = preferences(context)
        val editor = prefs.edit()
        val previousComplete = prefs.getString("$PREVIOUS_WINDOW_PROBE_PREFIX$WINDOW_PROBE_ID", null) != null &&
            prefs.getString("$PREVIOUS_WINDOW_PROBE_PREFIX${WINDOW_PROBE_ALWAYS}_report", null) != null &&
            prefs.getString("$PREVIOUS_WINDOW_PROBE_PREFIX${WINDOW_PROBE_DEFAULT}_report", null) != null
        if (prefs.getString(WINDOW_PROBE_ID, null) != null &&
            prefs.getString("${WINDOW_PROBE_ALWAYS}_report", null) != null &&
            (prefs.getString("${WINDOW_PROBE_DEFAULT}_report", null) != null || !previousComplete)
        ) {
            // Recovery can request ALWAYS before DEFAULT reaches pre-draw. Keep that first
            // measured ALWAYS frame if there is no complete predecessor to preserve instead.
            // Only one predecessor is retained, with its original ID and capture times.
            for (key in listOf(WINDOW_PROBE_ID, "${WINDOW_PROBE_ALWAYS}_report", "${WINDOW_PROBE_DEFAULT}_report")) {
                editor.putString("$PREVIOUS_WINDOW_PROBE_PREFIX$key", prefs.getString(key, null))
            }
            for (key in listOf(WINDOW_PROBE_STARTED_AT_MS, "${WINDOW_PROBE_ALWAYS}_captured_at_ms", "${WINDOW_PROBE_DEFAULT}_captured_at_ms")) {
                val previousKey = "$PREVIOUS_WINDOW_PROBE_PREFIX$key"
                if (prefs.contains(key)) editor.putLong(previousKey, prefs.getLong(key, 0L))
                else editor.remove(previousKey)
            }
        }
        editor
            .putString(WINDOW_PROBE_ID, probeId)
            .putLong(WINDOW_PROBE_STARTED_AT_MS, System.currentTimeMillis())
            .remove("${WINDOW_PROBE_ALWAYS}_report")
            .remove("${WINDOW_PROBE_ALWAYS}_captured_at_ms")
            .remove("${WINDOW_PROBE_DEFAULT}_report")
            .remove("${WINDOW_PROBE_DEFAULT}_captured_at_ms")
            .apply()
    }

    /**
     * Keep each requested mode independently, including zero bounds and literal null readings.
     * Callers capture at most once per requested-mode frame, not on every layout/inset callback.
     * A delayed callback must not overwrite a newer probe. This uses the same preferences file
     * as the lifecycle checkpoints, so those rare commits also flush these asynchronous writes.
     */
    @Synchronized
    fun saveWindowProbeFrame(context: Context, probeId: String, cameraMode: Boolean, report: String) {
        val prefs = preferences(context)
        if (prefs.getString(WINDOW_PROBE_ID, null) != probeId) return
        val prefix = if (cameraMode) WINDOW_PROBE_ALWAYS else WINDOW_PROBE_DEFAULT
        prefs.edit()
            .putString("${prefix}_report", report)
            .putLong("${prefix}_captured_at_ms", System.currentTimeMillis())
            .apply()
    }

    @Synchronized
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
            appendLine()
            appendLine()
            appendWindowProbe(prefs)
            if (prefs.getString("$PREVIOUS_WINDOW_PROBE_PREFIX$WINDOW_PROBE_ID", null) != null) {
                appendLine()
                appendLine()
                appendWindowProbe(prefs, previous = true)
            }
        }
    }

    private fun StringBuilder.appendWindowProbe(prefs: SharedPreferences, previous: Boolean = false) {
        val keyPrefix = if (previous) PREVIOUS_WINDOW_PROBE_PREFIX else ""
        appendLine(if (previous) "Previous retained window probe (comparison)"
            else "Window probe (paired requested-mode captures)")
        val probeId = prefs.getString("$keyPrefix$WINDOW_PROBE_ID", null)
        if (probeId == null) {
            append("No window probe has been started by this build. Existing layout and lifecycle snapshots remain available above.")
            return
        }
        appendLine("Probe ID: $probeId")
        if (prefs.contains("$keyPrefix$WINDOW_PROBE_STARTED_AT_MS")) {
            val startedAt = prefs.getLong("$keyPrefix$WINDOW_PROBE_STARTED_AT_MS", 0L)
            appendLine("Probe started UTC: ${utc(startedAt)}")
            appendLine("Probe started epoch ms: $startedAt")
        } else {
            appendLine("Probe started UTC: unknown")
            appendLine("Probe started epoch ms: unknown")
        }
        appendLine("Requested cutout modes do not establish the bounds Android granted.")
        appendLine()
        appendWindowProbeFrame(prefs, "$keyPrefix$WINDOW_PROBE_ALWAYS", "ALWAYS")
        appendLine()
        appendLine()
        appendWindowProbeFrame(prefs, "$keyPrefix$WINDOW_PROBE_DEFAULT", "DEFAULT")
    }

    private fun StringBuilder.appendWindowProbeFrame(prefs: SharedPreferences, prefix: String, mode: String) {
        appendLine("$mode window frame")
        val report = prefs.getString("${prefix}_report", null)
        if (report == null) {
            append("No $mode frame was captured for this probe. This mode may not have been requested, or its scheduled capture has not arrived. No measurement is inferred.")
            return
        }
        if (prefs.contains("${prefix}_captured_at_ms")) {
            val capturedAt = prefs.getLong("${prefix}_captured_at_ms", 0L)
            appendLine("Captured UTC: ${utc(capturedAt)}")
            appendLine("Captured epoch ms: $capturedAt")
        } else {
            appendLine("Captured UTC: unknown")
            appendLine("Captured epoch ms: unknown")
        }
        // Do not discard an all-zero, null-valued, or empty report: it is diagnostic evidence.
        append(report)
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
