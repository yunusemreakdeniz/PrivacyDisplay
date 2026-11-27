package io.github.yunusemreakdeniz.privacydisplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldDeciderTest {
    private var t = 0L
    private fun ShieldDecider.frame(faces: Int, yaw: Float = 0f) = onFrame(FaceReading(faces, yaw), t.also { t += 300 })

    @Test
    fun theOwnerAloneIsNeverDarkened() {
        val d = ShieldDecider()
        repeat(20) { d.frame(1, (it % 7) * 5f) }
        assertFalse(d.shielded)
    }

    @Test
    fun aGlanceAsideDoesNotDarkenButLookingAwayDoes() {
        val d = ShieldDecider()
        d.frame(1, 50f)
        d.frame(1, 50f)
        d.frame(1, 10f)
        assertFalse("a short glance", d.shielded)
        repeat(4) { d.frame(1, 50f) }
        assertTrue(d.shielded)
    }

    @Test
    fun aSecondFaceDarkensAfterTwoFrames() {
        val d = ShieldDecider()
        d.frame(2)
        assertFalse("one frame can be noise", d.shielded)
        d.frame(2)
        assertTrue(d.shielded)
    }

    @Test
    fun itLiftsOnlyAfterThreeClearFrames() {
        val d = ShieldDecider()
        d.frame(2); d.frame(2)
        d.frame(1, 10f); d.frame(1, 10f)
        assertTrue(d.shielded)
        d.frame(1, 36f)
        assertTrue("between the thresholds nothing lifts", d.shielded)
        d.frame(1, 10f); d.frame(1, 10f); d.frame(1, 10f)
        assertFalse(d.shielded)
    }

    @Test
    fun noFaceOnlyCountsWhenAskedAndAfterTwoSeconds() {
        val off = ShieldDecider()
        repeat(20) { off.frame(0) }
        assertFalse(off.shielded)
        val on = ShieldDecider().apply { shieldWhenNoFace = true }
        repeat(6) { on.frame(0) }
        assertFalse("under 2 s", on.shielded)
        repeat(3) { on.frame(0) }
        assertTrue(on.shielded)
    }

    @Test
    fun tiltDecidesInTheDarkWithHysteresis() {
        val d = ShieldDecider()
        d.onTilt(45f)
        assertTrue(d.shielded)
        d.onTilt(33f)
        assertTrue(d.shielded)
        d.onTilt(10f)
        assertFalse(d.shielded)
    }

    @Test
    fun motionIsSeenAndStillnessIsTimed() {
        val m = MotionTracker()
        m.onGravity(0f, 0f, 9.8f, 0)
        assertFalse(m.onGravity(0f, 0.2f, 9.8f, 200))
        assertTrue(m.onGravity(4.9f, 0f, 8.5f, 400))
        m.mark(400)
        m.onGravity(4.9f, 0f, 8.5f, 3_400)
        assertTrue(m.stillFor(3_400) >= 3_000)
    }
}
