package com.tyraen.voicekeyboard.core.config

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeyboardLayoutDiagnosticsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearSavedState() {
        context.getSharedPreferences("keyboard_layout_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("keyboard_layout_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `visible snapshot includes capture time and explicit visibility without duplicate geometry`() {
        val before = System.currentTimeMillis()
        KeyboardLayoutDiagnostics.save(context, "visible_geometry", visible = true)
        val after = System.currentTimeMillis()

        val report = KeyboardLayoutDiagnostics.read(context)
        val timestamp = Regex("Captured epoch ms: (\\d+)").find(report)!!.groupValues[1].toLong()
        assertTrue(timestamp in before..after)
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))
        assertTrue(report.contains("Captured UTC: $utc"))
        assertTrue(report.contains("Keyboard visible: true"))
        assertEquals(1, Regex("visible_geometry").findAll(report).count())
        assertFalse(report.contains("Last visible keyboard snapshot"))
    }

    @Test fun `hiding repeatedly retains latest visible geometry and its original capture time`() {
        KeyboardLayoutDiagnostics.save(context, "visible_geometry", visible = true)
        val visibleReport = KeyboardLayoutDiagnostics.read(context)
        val visibleTimestamp = Regex("Captured epoch ms: (\\d+)").find(visibleReport)!!.value
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        KeyboardLayoutDiagnostics.save(context, "first_hidden_geometry", visible = false)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        KeyboardLayoutDiagnostics.save(context, "latest_hidden_geometry", visible = false)

        val report = KeyboardLayoutDiagnostics.read(context)
        val retained = report.substringAfter("Last visible keyboard snapshot")
        assertTrue(report.contains("Latest layout event"))
        assertTrue(report.contains("latest_hidden_geometry"))
        assertTrue(report.contains("Keyboard visible: false"))
        assertFalse(report.contains("first_hidden_geometry"))
        assertFalse(report.substringBefore("Last visible keyboard snapshot").contains(visibleTimestamp))
        assertTrue(retained.contains("visible_geometry"))
        assertTrue(retained.contains("Keyboard visible: true"))
        assertTrue(retained.contains(visibleTimestamp))
    }

    @Test fun `new visible event replaces the old visible snapshot`() {
        KeyboardLayoutDiagnostics.save(context, "old_visible_geometry", visible = true)
        KeyboardLayoutDiagnostics.save(context, "hidden_geometry", visible = false)
        KeyboardLayoutDiagnostics.save(context, "new_visible_geometry", visible = true)

        val report = KeyboardLayoutDiagnostics.read(context)
        assertTrue(report.contains("new_visible_geometry"))
        assertFalse(report.contains("old_visible_geometry"))
        assertFalse(report.contains("hidden_geometry"))
        assertFalse(report.contains("Last visible keyboard snapshot"))
    }

    @Test fun `changed preferences are distinguished from captured settings`() {
        KeyboardLayoutPreferences.persist(context, compact = true, heightDp = 64, besideCameras = false)
        KeyboardLayoutDiagnostics.save(context, "captured_geometry", visible = true)
        KeyboardLayoutPreferences.persist(context, compact = false, heightDp = 72, besideCameras = true)

        val report = KeyboardLayoutDiagnostics.read(context)
        assertTrue(report.contains("Current settings (read now): compact=false; heightDp=72; besideCameras=true"))
        assertTrue(report.contains("Settings at capture: compact=true; heightDp=64; besideCameras=false"))
        assertTrue(report.contains("Settings changed since this snapshot"))
        assertTrue(report.contains("not a live measurement"))
    }

    @Test fun `hidden event with new settings still flags the retained visible snapshot as stale`() {
        KeyboardLayoutPreferences.persist(context, compact = true, heightDp = 56, besideCameras = false)
        KeyboardLayoutDiagnostics.save(context, "old_visible_geometry", visible = true)
        KeyboardLayoutPreferences.persist(context, compact = true, heightDp = 72, besideCameras = true)
        KeyboardLayoutDiagnostics.save(context, "new_hidden_geometry", visible = false)

        val report = KeyboardLayoutDiagnostics.read(context)
        val latest = report.substringBefore("Last visible keyboard snapshot")
        val retained = report.substringAfter("Last visible keyboard snapshot")
        assertFalse(latest.contains("Settings changed since this snapshot"))
        assertTrue(retained.contains("Settings changed since this snapshot"))
        assertTrue(retained.contains("heightDp=56; besideCameras=false"))
    }

    @Test fun `hidden event without a visible measurement says so`() {
        KeyboardLayoutDiagnostics.save(context, "hidden_geometry", visible = false)

        val report = KeyboardLayoutDiagnostics.read(context)
        assertTrue(report.contains("Keyboard visible: false"))
        assertTrue(report.contains("No visible keyboard snapshot yet"))
        assertFalse(report.contains("Last visible keyboard snapshot"))
    }

    @Test fun `empty storage shows current preferences and requests a measurement`() {
        KeyboardLayoutPreferences.persist(context, compact = true, heightDp = 72, besideCameras = true)

        val report = KeyboardLayoutDiagnostics.read(context)
        assertTrue(report.contains("Current settings (read now): compact=true; heightDp=72; besideCameras=true"))
        assertTrue(report.contains("No compact keyboard measurement yet"))
        assertFalse(report.contains("Latest layout event"))
    }

    @Test fun `legacy snapshot keeps geometry but does not invent timestamp visibility or settings`() {
        context.getSharedPreferences("keyboard_layout_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("latest_report", "legacy_geometry").commit()

        val report = KeyboardLayoutDiagnostics.read(context)
        assertTrue(report.contains("legacy_geometry"))
        assertTrue(report.contains("Captured UTC: unknown (legacy snapshot)"))
        assertTrue(report.contains("Captured epoch ms: unknown"))
        assertTrue(report.contains("Keyboard visible: unknown (legacy snapshot)"))
        assertTrue(report.contains("Settings at capture: unknown (legacy snapshot)"))
    }
    @Test fun `bound view preferences can differ from newly saved settings`() {
        val context = RuntimeEnvironment.getApplication()
        val boundLayout = KeyboardLayoutConfig(compact = true, heightDp = 64, besideCameras = true)
        KeyboardLayoutPreferences.persist(context, compact = true, heightDp = 72, besideCameras = false)
        KeyboardLayoutDiagnostics.save(context, "old view geometry", visible = true, layout = boundLayout)
        val report = KeyboardLayoutDiagnostics.read(context)
        assertTrue(report.contains("Settings at capture: compact=true; heightDp=64; besideCameras=true"))
        assertTrue(report.contains("Settings changed since this snapshot"))
    }

}
