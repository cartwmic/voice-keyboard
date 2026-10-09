package com.tyraen.voicekeyboard.core.config

import android.content.Context

data class KeyboardLayoutConfig(
    val compact: Boolean = false,
    val heightDp: Int = KeyboardLayoutPreferences.DEFAULT_HEIGHT_DP,
    val besideCameras: Boolean = false
)

/** Local presentation settings, deliberately independent of provider setup and validation. */
object KeyboardLayoutPreferences {

    const val MIN_HEIGHT_DP = 56
    const val DEFAULT_HEIGHT_DP = 64
    const val MAX_HEIGHT_DP = 72

    private const val PREFS_NAME = "keyboard_layout_prefs"
    private const val KEY_COMPACT = "compact_terminal_layout"
    private const val KEY_HEIGHT_DP = "compact_height_dp"
    private const val KEY_BESIDE_CAMERAS = "compact_beside_cameras"

    fun current(context: Context): KeyboardLayoutConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return KeyboardLayoutConfig(
            compact = prefs.getBoolean(KEY_COMPACT, false),
            heightDp = clampHeightDp(prefs.getInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP)),
            besideCameras = prefs.getBoolean(KEY_BESIDE_CAMERAS, false)
        )
    }

    fun persist(
        context: Context,
        compact: Boolean,
        heightDp: Int,
        besideCameras: Boolean = current(context).besideCameras
    ) {
        // apply() changes the in-memory values synchronously, so the IME can read them on its
        // next appearance without waiting for a coroutine or any endpoint validation.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPACT, compact)
            .putInt(KEY_HEIGHT_DP, clampHeightDp(heightDp))
            .putBoolean(KEY_BESIDE_CAMERAS, besideCameras)
            .apply()
    }

    internal fun clampHeightDp(heightDp: Int): Int = heightDp.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
}
