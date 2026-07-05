package com.colorwalk.app.ui.home

/**
 * Settle decision for the hub's clamped one-page drags — pure Kotlin so the commit
 * rules are JVM-testable.
 *
 * Two ways a gesture commits to the neighbor instead of springing back:
 *  - **Distance:** the drag traveled ≥ [DISTANCE_COMMIT_FRACTION] of a page — a
 *    deliberate pull, regardless of speed.
 *  - **Flick:** release velocity beyond [FLICK_VELOCITY_PX_PER_S] *and* at least
 *    [FLICK_MIN_DISTANCE_FRACTION] of travel in the same direction. The distance
 *    floor is what separates a real flick from the accidental grazes that made
 *    earlier velocity-sensitive tuning feel jumpy: a twitch can be fast, but it
 *    can't be fast AND travel 8% of the screen.
 *
 * A flick opposing its own net travel (drag up, flick down before release) resolves
 * by distance only — mixed signals never commit.
 */
object SwipePhysics {

    /** Travel (fraction of one page) that commits on its own. */
    const val DISTANCE_COMMIT_FRACTION = 0.3f

    /** Release speed that counts as a flick. */
    const val FLICK_VELOCITY_PX_PER_S = 1600f

    /** Minimum same-direction travel (fraction of a page) for a flick to count. */
    const val FLICK_MIN_DISTANCE_FRACTION = 0.08f

    /**
     * Where the drag value should settle.
     *
     * @param start        drag value where this gesture began (a settled page)
     * @param clampMin     gesture's lower bound (one page below/left of [start])
     * @param clampMax     gesture's upper bound (one page above/right of [start])
     * @param totalDelta   net finger travel this gesture (+ toward [clampMax])
     * @param velocity     release velocity in px/s (+ toward [clampMax])
     * @param viewportPx   one page's extent on this axis
     */
    fun settleTarget(
        start: Float,
        clampMin: Float,
        clampMax: Float,
        totalDelta: Float,
        velocity: Float,
        viewportPx: Int
    ): Float {
        val commitDistance = viewportPx * DISTANCE_COMMIT_FRACTION
        val flickFloor = viewportPx * FLICK_MIN_DISTANCE_FRACTION
        val forward = totalDelta > commitDistance ||
                (totalDelta > flickFloor && velocity > FLICK_VELOCITY_PX_PER_S)
        val backward = totalDelta < -commitDistance ||
                (totalDelta < -flickFloor && velocity < -FLICK_VELOCITY_PX_PER_S)
        return when {
            forward  -> clampMax
            backward -> clampMin
            else     -> start
        }
    }

    /**
     * Exponentially-smoothed release-velocity estimator. Call [update] per drag
     * event; read [value] at release. Smoothing suppresses the single-frame spikes
     * touch samplers produce, so one noisy event can't fabricate a flick.
     */
    class VelocityEstimator {
        var value = 0f
            private set
        private var lastUptimeMillis = 0L

        fun reset() {
            value = 0f
            lastUptimeMillis = 0L
        }

        fun update(delta: Float, uptimeMillis: Long) {
            val last = lastUptimeMillis
            lastUptimeMillis = uptimeMillis
            if (last == 0L || uptimeMillis <= last) return
            val instantaneous = delta * 1000f / (uptimeMillis - last)
            // 0.15 gain: a genuine flick (several fast frames) still converges past
            // the flick threshold within ~5 events, but ONE spiked frame after a slow
            // drag tops out well below it — verified by test.
            value = 0.85f * value + 0.15f * instantaneous
        }
    }
}
