package com.colorwalk.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Commit/settle rules for the hub's swipe gestures. These lock in the behavior the
 * gesture system was rebuilt around: exactly one page per gesture, deliberate drags
 * or genuine flicks commit, twitches and grazes never do.
 */
class SwipePhysicsTest {

    private val VIEWPORT = 1000
    private val START = 0f
    private val MIN = -1000f
    private val MAX = 1000f

    private fun settle(delta: Float, velocity: Float): Float =
        SwipePhysics.settleTarget(START, MIN, MAX, delta, velocity, VIEWPORT)

    @Test
    fun bigSlowDrag_commits() {
        assertEquals(MAX, settle(delta = 400f, velocity = 0f), 0f)
        assertEquals(MIN, settle(delta = -400f, velocity = 0f), 0f)
    }

    @Test
    fun smallSlowDrag_springsBack() {
        assertEquals(START, settle(delta = 150f, velocity = 0f), 0f)
        assertEquals(START, settle(delta = -150f, velocity = 0f), 0f)
    }

    @Test
    fun exactThreshold_isNotACommit() {
        // Strictly-greater: landing exactly on the threshold returns home.
        assertEquals(START, settle(delta = VIEWPORT * SwipePhysics.DISTANCE_COMMIT_FRACTION, velocity = 0f), 0f)
    }

    @Test
    fun genuineFlick_commitsWithoutFullDrag() {
        // 12% travel + fast release = flick → commit.
        assertEquals(MAX, settle(delta = 120f, velocity = 2500f), 0f)
        assertEquals(MIN, settle(delta = -120f, velocity = -2500f), 0f)
    }

    @Test
    fun fastTwitchWithTinyTravel_springsBack() {
        // The oversensitivity bug this design guards against: high velocity but
        // barely any travel (a graze) must NOT commit.
        assertEquals(START, settle(delta = 40f, velocity = 5000f), 0f)
    }

    @Test
    fun flickOpposingTravel_springsBack() {
        // Dragged forward 12% but flung backward at release: mixed signals → home.
        assertEquals(START, settle(delta = 120f, velocity = -3000f), 0f)
    }

    @Test
    fun slowReleaseAfterMediumDrag_springsBack() {
        // 12% travel below flick speed: neither rule fires.
        assertEquals(START, settle(delta = 120f, velocity = 800f), 0f)
    }

    @Test
    fun midGestureStart_respectsAsymmetricClamps() {
        // Gesture began mid-flight (caught a settling page at +200): clamps are
        // asymmetric, commits still land on the clamp bounds, never beyond.
        val target = SwipePhysics.settleTarget(
            start = 200f, clampMin = -800f, clampMax = 1000f,
            totalDelta = 400f, velocity = 0f, viewportPx = VIEWPORT
        )
        assertEquals(1000f, target, 0f)
    }

    // ── velocity estimator ────────────────────────────────────────────────────

    @Test
    fun estimator_steadyDrag_convergesToTrueVelocity() {
        val v = SwipePhysics.VelocityEstimator()
        v.reset()
        // 8 px every 8 ms = 1000 px/s, fed for 30 events.
        var t = 100L
        v.update(0f, t) // primes the clock
        repeat(30) {
            t += 8
            v.update(8f, t)
        }
        assertTrue("expected ~1000, was ${v.value}", v.value in 900f..1100f)
    }

    @Test
    fun estimator_singleSpike_isSuppressed() {
        val v = SwipePhysics.VelocityEstimator()
        v.reset()
        var t = 100L
        v.update(0f, t)
        repeat(10) { t += 8; v.update(2f, t) }   // slow drag ~250 px/s
        t += 8
        v.update(60f, t)                          // one 7500 px/s spike frame
        assertTrue("one spike must not read as a flick, was ${v.value}",
            v.value < SwipePhysics.FLICK_VELOCITY_PX_PER_S)
    }

    @Test
    fun estimator_resetClearsState() {
        val v = SwipePhysics.VelocityEstimator()
        var t = 100L
        v.update(0f, t)
        repeat(5) { t += 8; v.update(40f, t) }
        v.reset()
        assertEquals(0f, v.value, 0f)
    }
}
