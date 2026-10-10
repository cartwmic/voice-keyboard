package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.feature.ime.CameraGeometryReadiness.Effect
import com.tyraen.voicekeyboard.feature.ime.CameraGeometryReadiness.Observation
import com.tyraen.voicekeyboard.feature.ime.CameraGeometryReadiness.Phase
import org.junit.Assert.*
import org.junit.Test

class CameraGeometryReadinessTest {
    private fun observation(
        geometry: Boolean = false,
        displayId: Int? = 1,
        width: Int = 400,
        height: Int = 64,
        attached: Boolean = true,
        insetsReady: Boolean = true,
        displayGeometryKey: String? = null
    ) = Observation(displayId, width, height, attached, insetsReady, geometry, displayGeometryKey)

    private fun shown() = CameraGeometryReadiness(enabled = true).also {
        assertEquals(listOf(Effect.RequestCameraMode), it.onShown())
    }

    private fun settling(machine: CameraGeometryReadiness): Effect.ScheduleTimeout =
        machine.observe(observation()).single() as Effect.ScheduleTimeout

    private fun fallback(machine: CameraGeometryReadiness) {
        val timer = settling(machine)
        assertEquals(listOf(Effect.RequestSafeMode), machine.onTimeout(timer.token))
        assertEquals(Phase.FALLBACK, machine.phase)
    }

    @Test fun `first null geometry settles and later geometry is accepted`() {
        val machine = shown()
        val timer = settling(machine)
        assertEquals(500L, timer.delayMs)
        assertEquals(Phase.SETTLING, machine.phase)
        assertFalse(machine.useGeometry)
        assertEquals(listOf(Effect.CancelTimeout), machine.observe(observation(geometry = true)))
        assertEquals(Phase.GEOMETRY, machine.phase)
        assertTrue(machine.useGeometry)
        assertTrue(machine.onTimeout(timer.token).isEmpty())
    }

    @Test fun `geometry available on the first ready observation needs no timer`() {
        val machine = shown()
        assertTrue(machine.observe(observation(geometry = true)).isEmpty())
        assertTrue(machine.useGeometry)
    }

    @Test fun `not attached means no timer even with dimensions and old geometry`() {
        val machine = shown()
        assertTrue(machine.observe(observation(geometry = true, attached = false)).isEmpty())
        assertEquals(Phase.WAITING, machine.phase)
        assertFalse(machine.useGeometry)
    }

    @Test fun `zero dimensions and undelivered insets cannot start the timer`() {
        val machine = shown()
        assertTrue(machine.observe(observation(width = 0)).isEmpty())
        assertTrue(machine.observe(observation(height = 0)).isEmpty())
        assertTrue(machine.observe(observation(insetsReady = false)).isEmpty())
        assertEquals(Phase.WAITING, machine.phase)
        assertEquals(1L, settling(machine).token)
    }

    @Test fun `a valid deadline falls back once and does not permanently disable the option`() {
        val machine = shown()
        val timer = settling(machine)
        assertEquals(listOf(Effect.RequestSafeMode), machine.onTimeout(timer.token))
        assertEquals(Phase.FALLBACK, machine.phase)
        assertFalse(machine.useGeometry)
        assertTrue(machine.onTimeout(timer.token).isEmpty())
        assertTrue(machine.observe(observation()).isEmpty())
    }

    @Test fun `repeated missing observations do not extend the deadline`() {
        val machine = shown()
        val timer = settling(machine)
        repeat(10) { assertTrue(machine.observe(observation()).isEmpty()) }
        assertEquals(listOf(Effect.RequestSafeMode), machine.onTimeout(timer.token))
    }

    @Test fun `late geometry retries camera mode then waits for its fresh matching insets`() {
        val machine = shown()
        fallback(machine)
        assertEquals(listOf(Effect.RequestCameraMode), machine.observe(observation(geometry = true)))
        assertEquals(Phase.WAITING, machine.phase)
        assertFalse(machine.useGeometry)
        assertTrue(machine.observe(observation(geometry = true, insetsReady = false)).isEmpty())
        assertEquals(Phase.WAITING, machine.phase)
        assertTrue(machine.observe(observation(geometry = true)).isEmpty())
        assertTrue(machine.useGeometry)
    }

    @Test fun `DEFAULT only geometry permits one recovery and cannot endlessly switch modes`() {
        val machine = shown()
        fallback(machine)
        assertEquals(listOf(Effect.RequestCameraMode), machine.observe(observation(geometry = true)))
        fallback(machine)
        val generation = machine.generation
        repeat(5) {
            assertTrue(machine.observe(observation(geometry = true)).isEmpty())
            assertEquals(Phase.FALLBACK, machine.phase)
            assertEquals(generation, machine.generation)
        }
    }

    @Test fun `fresh show restores the one late recovery budget`() {
        val machine = shown()
        fallback(machine)
        machine.observe(observation(geometry = true))
        fallback(machine)
        machine.onHidden()
        machine.onShown()
        fallback(machine)
        assertEquals(listOf(Effect.RequestCameraMode), machine.observe(observation(geometry = true)))
    }

    @Test fun `fresh display identity restores the one late recovery budget`() {
        val machine = shown()
        fallback(machine)
        machine.observe(observation(geometry = true))
        fallback(machine)
        assertEquals(listOf(Effect.RequestCameraMode), machine.observe(observation(displayId = 2)))
        val timer = machine.observe(observation(displayId = 2)).single() as Effect.ScheduleTimeout
        machine.onTimeout(timer.token)
        assertEquals(listOf(Effect.RequestCameraMode), machine.observe(observation(displayId = 2, geometry = true)))
    }

    @Test fun `hide cancels the active timer and requests safe mode`() {
        val machine = shown()
        val timer = settling(machine)
        assertEquals(listOf(Effect.CancelTimeout, Effect.RequestSafeMode), machine.onHidden())
        assertEquals(Phase.HIDDEN, machine.phase)
        assertTrue(machine.onTimeout(timer.token).isEmpty())
        assertTrue(machine.observe(observation(geometry = true, displayId = 2, width = 500)).isEmpty())
        assertFalse(machine.useGeometry)
    }

    @Test fun `hide prevents late fallback geometry from reopening camera mode`() {
        val machine = shown()
        fallback(machine)
        assertEquals(listOf(Effect.RequestSafeMode), machine.onHidden())
        assertTrue(machine.observe(observation(geometry = true)).isEmpty())
        assertEquals(Phase.HIDDEN, machine.phase)
    }

    @Test fun `fresh show retries after fallback while duplicate show is idempotent`() {
        val machine = shown()
        fallback(machine)
        val oldGeneration = machine.generation
        assertTrue(machine.onShown().isEmpty())
        assertEquals(oldGeneration, machine.generation)
        assertEquals(Phase.FALLBACK, machine.phase)
        machine.onHidden()
        assertEquals(listOf(Effect.RequestCameraMode), machine.onShown())
        assertEquals(Phase.WAITING, machine.phase)
        assertTrue(machine.generation > oldGeneration)
    }

    @Test fun `old timer cannot affect a later shown attempt`() {
        val machine = shown()
        val oldTimer = settling(machine)
        machine.onHidden()
        machine.onShown()
        val newTimer = settling(machine)
        assertNotEquals(oldTimer.token, newTimer.token)
        assertTrue(machine.onTimeout(oldTimer.token).isEmpty())
        assertEquals(Phase.SETTLING, machine.phase)
        assertEquals(listOf(Effect.RequestSafeMode), machine.onTimeout(newTimer.token))
    }

    @Test fun `display change retries fallback even when width is unchanged`() {
        val machine = shown()
        fallback(machine)
        assertEquals(listOf(Effect.RequestCameraMode), machine.observe(observation(displayId = 2)))
        assertEquals(Phase.WAITING, machine.phase)
        assertTrue(machine.observe(observation(displayId = 2, geometry = true)).isEmpty())
        assertTrue(machine.useGeometry)
    }

    @Test fun `width change invalidates old geometry and requires a fresh delivery`() {
        val machine = shown()
        machine.observe(observation(geometry = true))
        assertEquals(listOf(Effect.RequestCameraMode), machine.observe(observation(width = 600, geometry = true)))
        assertEquals(Phase.WAITING, machine.phase)
        assertFalse(machine.useGeometry)
        assertTrue(machine.observe(observation(width = 600, geometry = true)).isEmpty())
        assertTrue(machine.useGeometry)
    }

    @Test fun `fold width change cancels its old settling deadline`() {
        val machine = shown()
        val oldTimer = settling(machine)
        assertEquals(
            listOf(Effect.CancelTimeout, Effect.RequestCameraMode),
            machine.observe(observation(width = 600))
        )
        assertTrue(machine.onTimeout(oldTimer.token).isEmpty())
        val newTimer = machine.observe(observation(width = 600)).single() as Effect.ScheduleTimeout
        assertNotEquals(oldTimer.token, newTimer.token)
    }

    @Test fun `stable display geometry key ignores root width changes from our window mode`() {
        val machine = shown()
        val key = "1080x2400 rotation=0"
        val timer = machine.observe(observation(displayGeometryKey = key)).single() as Effect.ScheduleTimeout
        machine.onTimeout(timer.token)
        val generation = machine.generation
        for (width in listOf(420, 400, 420)) {
            assertTrue(machine.observe(observation(width = width, displayGeometryKey = key)).isEmpty())
            assertEquals(Phase.FALLBACK, machine.phase)
            assertEquals(generation, machine.generation)
        }
    }

    @Test fun `display geometry key change retries even with the same root width`() {
        val machine = shown()
        machine.observe(observation(geometry = true, displayGeometryKey = "1080x2400 rotation=0"))
        assertEquals(
            listOf(Effect.RequestCameraMode),
            machine.observe(observation(geometry = true, displayGeometryKey = "2400x1080 rotation=1"))
        )
        assertEquals(Phase.WAITING, machine.phase)
        assertFalse(machine.useGeometry)
    }

    @Test fun `mode width changes cannot restore an exhausted late recovery budget`() {
        val machine = shown()
        val key = "1080x2400 rotation=0"
        val firstTimer = machine.observe(observation(displayGeometryKey = key)).single() as Effect.ScheduleTimeout
        machine.onTimeout(firstTimer.token)
        machine.observe(observation(geometry = true, width = 420, displayGeometryKey = key))
        val secondTimer = machine.observe(observation(width = 400, displayGeometryKey = key)).single() as Effect.ScheduleTimeout
        machine.onTimeout(secondTimer.token)
        assertTrue(machine.observe(observation(geometry = true, width = 420, displayGeometryKey = key)).isEmpty())
        assertEquals(Phase.FALLBACK, machine.phase)
    }

    @Test fun `height changes do not retry fallback or reset generation`() {
        val machine = shown()
        fallback(machine)
        val generation = machine.generation
        for (height in listOf(164, 64, 0, 164)) {
            assertTrue(machine.observe(observation(height = height)).isEmpty())
            assertEquals(Phase.FALLBACK, machine.phase)
            assertEquals(generation, machine.generation)
        }
    }

    @Test fun `padding height changes do not extend a settling deadline`() {
        val machine = shown()
        val timer = settling(machine)
        assertTrue(machine.observe(observation(height = 164)).isEmpty())
        assertEquals(listOf(Effect.RequestSafeMode), machine.onTimeout(timer.token))
    }

    @Test fun `padding height changes preserve accepted geometry`() {
        val machine = shown()
        machine.observe(observation(geometry = true))
        val generation = machine.generation
        assertTrue(machine.observe(observation(geometry = true, height = 164)).isEmpty())
        assertTrue(machine.useGeometry)
        assertEquals(generation, machine.generation)
    }

    @Test fun `detachment suspends a timer without requesting camera mode offscreen`() {
        val machine = shown()
        val oldTimer = settling(machine)
        assertEquals(
            listOf(Effect.CancelTimeout),
            machine.observe(observation(attached = false, width = 600, displayId = 2))
        )
        assertEquals(Phase.WAITING, machine.phase)
        assertTrue(machine.onTimeout(oldTimer.token).isEmpty())
        assertEquals(listOf(Effect.RequestCameraMode), machine.observe(observation(width = 600, displayId = 2)))
    }

    @Test fun `new insets readiness gets a unique timer after an interrupted traversal`() {
        val machine = shown()
        val oldTimer = settling(machine)
        assertEquals(listOf(Effect.CancelTimeout), machine.observe(observation(insetsReady = false)))
        val newTimer = settling(machine)
        assertNotEquals(oldTimer.token, newTimer.token)
        assertTrue(machine.onTimeout(oldTimer.token).isEmpty())
        assertEquals(listOf(Effect.RequestSafeMode), machine.onTimeout(newTimer.token))
    }

    @Test fun `geometry disappearing gets a bounded new settling interval`() {
        val machine = shown()
        machine.observe(observation(geometry = true))
        val timer = settling(machine)
        assertFalse(machine.useGeometry)
        assertEquals(listOf(Effect.RequestSafeMode), machine.onTimeout(timer.token))
    }

    @Test fun `disabled mode never requests cameras or a timer`() {
        val machine = CameraGeometryReadiness(enabled = false)
        assertEquals(Phase.DISABLED, machine.phase)
        assertEquals(listOf(Effect.RequestSafeMode), machine.onShown())
        assertTrue(machine.onShown().isEmpty())
        assertTrue(machine.observe(observation(geometry = true)).isEmpty())
        assertTrue(machine.observe(observation(width = 600, displayId = 2)).isEmpty())
        assertTrue(machine.onTimeout(1L).isEmpty())
        assertEquals(listOf(Effect.RequestSafeMode), machine.onHidden())
        assertEquals(Phase.DISABLED, machine.phase)
        assertFalse(machine.useGeometry)
    }

    @Test fun `observations before show and repeated hide have no effects`() {
        val machine = CameraGeometryReadiness(enabled = true)
        assertTrue(machine.observe(observation(geometry = true)).isEmpty())
        assertTrue(machine.onHidden().isEmpty())
        machine.onShown()
        machine.onHidden()
        val generation = machine.generation
        assertTrue(machine.onHidden().isEmpty())
        assertEquals(generation, machine.generation)
    }

    @Test fun `custom settling delay is passed to the scheduler`() {
        val machine = CameraGeometryReadiness(enabled = true, settleDelayMs = 750L)
        machine.onShown()
        assertEquals(750L, settling(machine).delayMs)
    }
}
