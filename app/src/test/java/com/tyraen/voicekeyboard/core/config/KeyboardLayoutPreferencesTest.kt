package com.tyraen.voicekeyboard.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyboardLayoutPreferencesTest {

    @Test fun `full keyboard is the default with a 64 dp compact height`() {
        val layout = KeyboardLayoutConfig()
        assertFalse(layout.compact)
        assertFalse(layout.besideCameras)
        assertEquals(64, layout.heightDp)
    }

    @Test fun `compact height keeps every value in the supported range`() {
        for (height in 56..72) {
            assertEquals(height, KeyboardLayoutPreferences.clampHeightDp(height))
        }
    }

    @Test fun `compact height clamps invalid low and high values`() {
        assertEquals(56, KeyboardLayoutPreferences.clampHeightDp(Int.MIN_VALUE))
        assertEquals(56, KeyboardLayoutPreferences.clampHeightDp(55))
        assertEquals(72, KeyboardLayoutPreferences.clampHeightDp(73))
        assertEquals(72, KeyboardLayoutPreferences.clampHeightDp(Int.MAX_VALUE))
    }
}
