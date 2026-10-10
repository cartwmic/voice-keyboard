package com.tyraen.voicekeyboard.feature.ime

/** Pure lifecycle for a bounded attempt to obtain usable camera geometry. */
class CameraGeometryReadiness(
    val enabled: Boolean,
    private val settleDelayMs: Long = 500L
) {
    enum class Phase { DISABLED, HIDDEN, WAITING, SETTLING, GEOMETRY, FALLBACK }

    data class Observation(
        val displayId: Int?,
        val widthPx: Int,
        val heightPx: Int,
        val attached: Boolean,
        val insetsReady: Boolean,
        val hasGeometry: Boolean,
        val displayGeometryKey: String? = null
    )

    sealed class Effect {
        object RequestCameraMode : Effect()
        object RequestSafeMode : Effect()
        data class ScheduleTimeout(val token: Long, val delayMs: Long) : Effect()
        object CancelTimeout : Effect()
    }

    var phase: Phase = if (enabled) Phase.HIDDEN else Phase.DISABLED
        private set

    /** Advances when window-mode requests or hide invalidate an adapter's pending callbacks. */
    var generation: Long = 0L
        private set

    val useGeometry: Boolean get() = phase == Phase.GEOMETRY

    private data class DisplayIdentity(val displayId: Int?, val geometryKey: String?, val widthPx: Int?)
    private var displayIdentity: DisplayIdentity? = null
    private var shown = false
    private var lateRecoveryUsed = false
    private var nextTimerToken = 0L
    private var pendingTimerToken: Long? = null

    init {
        require(settleDelayMs > 0L) { "settleDelayMs must be positive" }
    }

    /** Duplicate show callbacks do not restart an active attempt or its settling deadline. */
    fun onShown(): List<Effect> {
        if (shown) return emptyList()
        shown = true
        displayIdentity = null
        return buildList {
            if (enabled) startAttempt(this)
            else {
                generation++
                add(Effect.RequestSafeMode)
            }
        }
    }

    /** No observation or old timer can request camera mode again until the next show. */
    fun onHidden(): List<Effect> {
        if (!shown) return emptyList()
        shown = false
        displayIdentity = null
        generation++
        phase = if (enabled) Phase.HIDDEN else Phase.DISABLED
        return buildList {
            cancelTimer(this)
            add(Effect.RequestSafeMode)
        }
    }

    /**
     * insetsReady means current window insets sampled after a mode's completed traversal,
     * or a fresh descendant inset delivery for the currently requested window mode.
     * The Android adapter clears that flag whenever it handles RequestCameraMode/RequestSafeMode.
     * It also keeps the row hidden until the corresponding geometry or safe traversal is applied.
     *
     * Height is only a readiness gate. In particular, padding-induced height changes must not
     * retry a failed attempt, reset its timeout, or request a different window mode.
     * Supply a stable physical-display geometry key to also avoid retries from window-width
     * changes caused by our own cutout mode. Without a key, width is the retry identity.
     */
    fun observe(observation: Observation): List<Effect> {
        if (!shown || !enabled) return emptyList()
        val effects = mutableListOf<Effect>()

        if (!observation.attached) {
            suspendReadiness(effects)
            return effects
        }

        if (observation.widthPx > 0) {
            val newIdentity = DisplayIdentity(
                observation.displayId,
                observation.displayGeometryKey,
                if (observation.displayGeometryKey == null) observation.widthPx else null
            )
            val previous = displayIdentity
            displayIdentity = newIdentity
            if (previous != null && previous != newIdentity) {
                startAttempt(effects)
                // Geometry from before the new mode request is not ready evidence for it.
                return effects
            }
        }

        if (observation.widthPx <= 0 || observation.heightPx <= 0) {
            suspendReadiness(effects)
            return effects
        }

        // A consumed descendant inset dispatch must not leave a measured keyboard hidden
        // forever. Bound the whole attached probe, including the wait for its first insets.
        if (!observation.insetsReady) {
            if (phase == Phase.GEOMETRY) phase = Phase.WAITING
            if (phase != Phase.FALLBACK) scheduleTimer(effects)
            return effects
        }

        if (observation.hasGeometry) {
            if (phase == Phase.FALLBACK) {
                // Late geometry can recover this visible session. Wait for a fresh delivery
                // after requesting camera mode rather than accepting DEFAULT-mode coordinates.
                // Some devices report rectangles only in DEFAULT mode. One recovery per
                // external show/display cycle prevents those devices from endlessly toggling.
                if (!lateRecoveryUsed) {
                    lateRecoveryUsed = true
                    startAttempt(effects, freshCycle = false)
                }
            } else {
                cancelTimer(effects)
                phase = Phase.GEOMETRY
            }
            return effects
        }

        if (phase != Phase.FALLBACK && phase != Phase.SETTLING) {
            phase = Phase.SETTLING
            scheduleTimer(effects)
        }
        return effects
    }

    fun onTimeout(token: Long): List<Effect> {
        if (!shown || !enabled || phase !in listOf(Phase.WAITING, Phase.SETTLING) || pendingTimerToken != token) {
            return emptyList()
        }
        pendingTimerToken = null
        phase = Phase.FALLBACK
        generation++
        return listOf(Effect.RequestSafeMode)
    }

    private fun startAttempt(effects: MutableList<Effect>, freshCycle: Boolean = true) {
        cancelTimer(effects)
        if (freshCycle) lateRecoveryUsed = false
        generation++
        phase = Phase.WAITING
        effects.add(Effect.RequestCameraMode)
    }

    private fun cancelTimer(effects: MutableList<Effect>) {
        if (pendingTimerToken != null) {
            pendingTimerToken = null
            effects.add(Effect.CancelTimeout)
        }
    }

    private fun scheduleTimer(effects: MutableList<Effect>) {
        if (pendingTimerToken != null) return
        val token = ++nextTimerToken
        pendingTimerToken = token
        effects.add(Effect.ScheduleTimeout(token, settleDelayMs))
    }

    private fun suspendReadiness(effects: MutableList<Effect>) {
        cancelTimer(effects)
        if (phase == Phase.SETTLING || phase == Phase.GEOMETRY) phase = Phase.WAITING
    }
}
