package com.tyraen.voicekeyboard.core.config

import android.content.Context

/** Latest local layout measurement only. Never records editor text, audio, or provider settings. */
object KeyboardLayoutDiagnostics {
    private const val PREFS = "keyboard_layout_diagnostics"
    private const val KEY_REPORT = "latest_report"

    fun save(context: Context, report: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REPORT, report).apply()
    }

    fun read(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_REPORT, null)
        ?: "No compact keyboard measurement yet. Open the compact keyboard, then copy this report again."
}
