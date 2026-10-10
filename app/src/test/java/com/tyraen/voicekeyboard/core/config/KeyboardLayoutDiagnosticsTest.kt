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
        assertTrue(report.contains("No controls-visible frame was recorded"))
        assertFalse(report.contains("Last visible keyboard snapshot"))
    }

    @Test fun `empty storage shows current preferences and requests a measurement`() {
        KeyboardLayoutPreferences.persist(context, compact = true, heightDp = 72, besideCameras = true)

        val report = KeyboardLayoutDiagnostics.read(context)
        assertTrue(report.contains("Current settings (read now): compact=true; heightDp=72; besideCameras=true"))
        assertTrue(report.contains("No geometry snapshot was captured"))
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

    @Test fun `lifecycle checkpoint is durable without geometry and survives a different context`() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(KeyboardLayoutDiagnostics.recordLifecycle(context, "input_view_created", "Input view exists: true"))
        val file = java.io.File(context.applicationInfo.dataDir, "shared_prefs/keyboard_layout_diagnostics.xml")
        assertTrue(file.exists())
        assertTrue(file.readText().contains("input_view_created"))
        val otherContext = context.createConfigurationContext(android.content.res.Configuration())
        val report = KeyboardLayoutDiagnostics.read(otherContext)
        assertTrue(report.contains("Event: input_view_created"))
        assertTrue(report.contains("No geometry snapshot was captured"))
        assertFalse(report.contains("Open the compact keyboard"))
    }

    @Test fun `lifecycle history stays bounded and retains newest events`() {
        val context = RuntimeEnvironment.getApplication()
        repeat(12) { KeyboardLayoutDiagnostics.recordLifecycle(context, "event_$it", "safe layout metadata") }
        val report = KeyboardLayoutDiagnostics.read(context)
        assertFalse(report.contains("Event: event_3\n"))
        assertTrue(report.contains("Event: event_4\n"))
        assertTrue(report.contains("Event: event_11\n"))
    }

    @Test fun `window probe retains ALWAYS and DEFAULT frames with independent capture times`() {
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "show_1")
        val beforeAlways = System.currentTimeMillis()
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "show_1", cameraMode = true, report = "always_geometry")
        val afterAlways = System.currentTimeMillis()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        val beforeDefault = System.currentTimeMillis()
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "show_1", cameraMode = false, report = "default_geometry")
        val afterDefault = System.currentTimeMillis()
        KeyboardLayoutDiagnostics.save(context, "later_hidden_geometry", visible = false)

        val report = KeyboardLayoutDiagnostics.read(context)
        val always = report.substringAfter("ALWAYS window frame").substringBefore("DEFAULT window frame")
        val default = report.substringAfter("DEFAULT window frame")
        val alwaysTimestamp = Regex("Captured epoch ms: (\\d+)").find(always)!!.groupValues[1].toLong()
        val defaultTimestamp = Regex("Captured epoch ms: (\\d+)").find(default)!!.groupValues[1].toLong()
        assertTrue(report.contains("Probe ID: show_1"))
        assertTrue(report.contains("Probe started UTC:"))
        assertTrue(report.contains("Probe started epoch ms:"))
        assertTrue(always.contains("always_geometry"))
        assertFalse(always.contains("default_geometry"))
        assertTrue(default.contains("default_geometry"))
        assertFalse(default.contains("always_geometry"))
        assertTrue(alwaysTimestamp in beforeAlways..afterAlways)
        assertTrue(defaultTimestamp in beforeDefault..afterDefault)
        assertTrue(always.contains("Captured UTC:"))
        assertTrue(default.contains("Captured UTC:"))
        assertTrue(report.contains("later_hidden_geometry"))
    }

    @Test fun `updating one requested mode keeps the other frame and timestamp`() {
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "show_1")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "show_1", cameraMode = false, report = "default_geometry")
        val savedDefault = KeyboardLayoutDiagnostics.read(context).substringAfter("DEFAULT window frame")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "show_1", cameraMode = true, report = "first_always_geometry")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "show_1", cameraMode = true, report = "latest_always_geometry")

        val report = KeyboardLayoutDiagnostics.read(context)
        assertEquals(savedDefault, report.substringAfter("DEFAULT window frame"))
        assertTrue(report.contains("latest_always_geometry"))
        assertFalse(report.contains("first_always_geometry"))
    }

    @Test fun `beginning a new probe resets current frames and retains the completed predecessor`() {
        KeyboardLayoutDiagnostics.save(context, "visible_geometry")
        KeyboardLayoutDiagnostics.recordLifecycle(context, "input_view_started", "safe metadata")
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "old_probe")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "old_probe", cameraMode = true, report = "old_always_geometry")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "old_probe", cameraMode = false, report = "old_default_geometry")
        val originalPair = KeyboardLayoutDiagnostics.read(context)
            .substringAfter("Window probe (paired requested-mode captures)\n")
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        val beforeNewProbe = System.currentTimeMillis()
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "new_probe")
        val afterNewProbe = System.currentTimeMillis()

        val report = KeyboardLayoutDiagnostics.read(context)
        val current = report.substringBefore("Previous retained window probe (comparison)")
        val previous = report.substringAfter("Previous retained window probe (comparison)\n")
        val startedAt = Regex("Probe started epoch ms: (\\d+)").find(report)!!.groupValues[1].toLong()
        assertTrue(report.contains("Probe ID: new_probe"))
        assertTrue(startedAt in beforeNewProbe..afterNewProbe)
        assertFalse(current.contains("old_probe"))
        assertFalse(current.contains("old_always_geometry"))
        assertFalse(current.contains("old_default_geometry"))
        assertTrue(current.contains("No ALWAYS frame was captured for this probe"))
        assertTrue(current.contains("No DEFAULT frame was captured for this probe"))
        assertFalse(current.substringAfter("ALWAYS window frame").contains("Captured epoch ms:"))
        assertEquals(originalPair, previous)
        assertTrue(report.contains("visible_geometry"))
        assertTrue(report.contains("Event: input_view_started"))
    }

    @Test fun `incomplete new probes preserve the previous completed pair until a newer complete pair replaces it`() {
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "first_complete")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "first_complete", cameraMode = true, report = "first_always")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "first_complete", cameraMode = false, report = "first_default")
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "incomplete_recovery")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "incomplete_recovery", cameraMode = true, report = "recovery_always")
        val originalPrevious = KeyboardLayoutDiagnostics.read(context)
            .substringAfter("Previous retained window probe (comparison)\n")

        KeyboardLayoutDiagnostics.beginWindowProbe(context, "second_complete")
        assertEquals(originalPrevious, KeyboardLayoutDiagnostics.read(context)
            .substringAfter("Previous retained window probe (comparison)\n"))
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "second_complete", cameraMode = true, report = "second_always")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "second_complete", cameraMode = false, report = "second_default")
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "latest_incomplete")

        val report = KeyboardLayoutDiagnostics.read(context)
        val previous = report.substringAfter("Previous retained window probe (comparison)\n")
        assertTrue(report.contains("Probe ID: latest_incomplete"))
        assertTrue(previous.contains("Probe ID: second_complete"))
        assertTrue(previous.contains("second_always"))
        assertTrue(previous.contains("second_default"))
        assertFalse(report.contains("first_complete"))
        assertFalse(report.contains("first_always"))
        assertFalse(report.contains("first_default"))
        assertFalse(report.contains("incomplete_recovery"))
        assertFalse(report.contains("recovery_always"))
        assertEquals(1, Regex("Previous retained window probe").findAll(report).count())
        val beforeStale = report
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "second_complete", cameraMode = true, report = "stale_previous_update")
        assertEquals(beforeStale, KeyboardLayoutDiagnostics.read(context))
    }

    @Test fun `recovery before DEFAULT capture retains measured ALWAYS with explicit missing DEFAULT`() {
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "interrupted_probe")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "interrupted_probe", cameraMode = true, report = "measured_always")
        val originalPair = KeyboardLayoutDiagnostics.read(context)
            .substringAfter("Window probe (paired requested-mode captures)\n")

        KeyboardLayoutDiagnostics.beginWindowProbe(context, "recovery_probe")
        val report = KeyboardLayoutDiagnostics.read(context)
        val current = report.substringBefore("Previous retained window probe (comparison)")
        val previous = report.substringAfter("Previous retained window probe (comparison)\n")
        assertTrue(current.contains("Probe ID: recovery_probe"))
        assertFalse(current.contains("measured_always"))
        assertEquals(originalPair, previous)
        assertTrue(previous.contains("Probe ID: interrupted_probe"))
        assertTrue(previous.contains("measured_always"))
        assertTrue(previous.contains("No DEFAULT frame was captured for this probe"))
        assertFalse(previous.contains("No ALWAYS frame was captured"))

        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "interrupted_probe", cameraMode = false, report = "late_default")
        assertEquals(report, KeyboardLayoutDiagnostics.read(context))
    }

    @Test fun `new incomplete ALWAYS replaces an incomplete predecessor when no complete pair exists`() {
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "first_incomplete")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "first_incomplete", cameraMode = true, report = "first_always")
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "second_incomplete")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "second_incomplete", cameraMode = true, report = "second_always")
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "latest_probe")

        val report = KeyboardLayoutDiagnostics.read(context)
        val previous = report.substringAfter("Previous retained window probe (comparison)\n")
        assertTrue(previous.contains("Probe ID: second_incomplete"))
        assertTrue(previous.contains("second_always"))
        assertTrue(previous.contains("No DEFAULT frame was captured for this probe"))
        assertFalse(report.contains("first_incomplete"))
        assertFalse(report.contains("first_always"))
        assertEquals(1, Regex("Previous retained window probe").findAll(report).count())
    }

    @Test fun `stale older probe callbacks cannot fill or replace the current pair`() {
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "old_probe")
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "current_probe")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "current_probe", cameraMode = true, report = "current_always_geometry")
        val beforeStaleCallbacks = KeyboardLayoutDiagnostics.read(context)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "old_probe", cameraMode = true, report = "stale_always_geometry")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "old_probe", cameraMode = false, report = "stale_default_geometry")

        assertEquals(beforeStaleCallbacks, KeyboardLayoutDiagnostics.read(context))
    }

    @Test fun `window probe preserves zero geometry and literal null measurements exactly`() {
        val rawReport = "Bounds: [0,0][0,0]\nHeight: 0\nRoot insets: null\nCutout: null\n   "
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "unmeasured_probe")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "unmeasured_probe", cameraMode = true, report = rawReport)

        val report = KeyboardLayoutDiagnostics.read(context)
        assertTrue(report.contains(rawReport))
        assertFalse(report.contains("No ALWAYS frame was captured"))
        assertTrue(report.contains("No DEFAULT frame was captured for this probe"))
    }

    @Test fun `empty captured report is distinct from a missing window frame`() {
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "empty_report_probe")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "empty_report_probe", cameraMode = false, report = "")

        val report = KeyboardLayoutDiagnostics.read(context)
        assertTrue(report.contains("No ALWAYS frame was captured for this probe"))
        assertFalse(report.contains("No DEFAULT frame was captured"))
        assertTrue(report.substringAfter("DEFAULT window frame").contains("Captured epoch ms:"))
    }

    @Test fun `reading without a probe is graceful and unsolicited frames are ignored`() {
        KeyboardLayoutDiagnostics.save(context, "legacy_layout_geometry")
        val beforeUnsolicitedFrame = KeyboardLayoutDiagnostics.read(context)
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "never_started_probe", cameraMode = true, report = "unsolicited_geometry")

        val report = KeyboardLayoutDiagnostics.read(context)
        assertEquals(beforeUnsolicitedFrame, report)
        assertTrue(report.contains("No window probe has been started by this build"))
        assertTrue(report.contains("legacy_layout_geometry"))
        assertFalse(report.contains("Probe ID:"))
    }

    @Test fun `lifecycle checkpoint also flushes asynchronous window probe pair to disk`() {
        KeyboardLayoutDiagnostics.beginWindowProbe(context, "durable_probe")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "durable_probe", cameraMode = true, report = "durable_always_geometry")
        KeyboardLayoutDiagnostics.saveWindowProbeFrame(context, "durable_probe", cameraMode = false, report = "durable_default_geometry")
        assertTrue(KeyboardLayoutDiagnostics.recordLifecycle(context, "input_view_finished", "safe metadata"))

        val file = java.io.File(context.applicationInfo.dataDir, "shared_prefs/keyboard_layout_diagnostics.xml")
        val persisted = file.readText()
        assertTrue(persisted.contains("durable_probe"))
        assertTrue(persisted.contains("durable_always_geometry"))
        assertTrue(persisted.contains("durable_default_geometry"))
        val otherContext = context.createConfigurationContext(android.content.res.Configuration())
        assertEquals(KeyboardLayoutDiagnostics.read(context), KeyboardLayoutDiagnostics.read(otherContext))
    }

}
