package io.github.yunusemreakdeniz.privacydisplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldDeciderTest {
    @Test
    fun sideLookDropsTheCurtainAndOnlyAClearLookLiftsIt() {
        val d = ShieldDecider()
        assertFalse(d.onFrame(FaceReading(1, 20f)))
        assertTrue(d.onFrame(FaceReading(1, 45f)))
        assertTrue(d.shielded)
        // Between the two thresholds nothing changes (no flicker).
        assertFalse(d.onFrame(FaceReading(1, 36f)))
        assertTrue(d.shielded)
        assertTrue(d.onFrame(FaceReading(1, -30f)))
        assertFalse(d.shielded)
    }

    @Test
    fun aSecondFaceDropsTheCurtainAtOnce() {
        val d = ShieldDecider()
        d.onFrame(FaceReading(2, 0f))
        assertTrue(d.shielded)
        d.onFrame(FaceReading(2, 0f))
        assertTrue("two faces never lift it", d.shielded)
        d.onFrame(FaceReading(1, 5f))
        assertFalse(d.shielded)
    }

    @Test
    fun noFaceNeedsTwoFramesInARow() {
        val d = ShieldDecider()
        d.onFrame(FaceReading(0, 0f))
        assertFalse(d.shielded)
        d.onFrame(FaceReading(0, 0f))
        assertTrue(d.shielded)
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
        // Tilt the phone about 30 degrees.
        assertTrue(m.onGravity(4.9f, 0f, 8.5f, 400))
        m.mark(400)
        m.onGravity(4.9f, 0f, 8.5f, 3_400)
        assertTrue(m.stillFor(3_400) >= 3_000)
    }
}
