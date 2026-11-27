package io.github.yunusemreakdeniz.privacydisplay

import kotlin.math.abs

/** The four levels of the roadmap's event-driven state machine. */
enum class PrivacyState { IDLE, SENSOR_WATCH, ACTIVE_CHECK, SHIELD_ACTIVE }

/** One analysed camera frame: how many faces, and the head yaw of the main (largest) one. */
data class FaceReading(val faces: Int, val yawDegrees: Float)

/**
 * Hysteresis so the shield doesn't flicker at the edge of the angle:
 * - down when |yaw| > [shieldYaw] or more than one face (someone looking over the shoulder),
 *   or nobody has been seen for [noFaceFrames] frames in a row;
 * - up only when exactly one face is back below |yaw| < [clearYaw].
 * Anything in between keeps the current state.
 */
class ShieldDecider(
    private val shieldYaw: Float = 42f,
    private val clearYaw: Float = 32f,
    private val noFaceFrames: Int = 2,
) {
    var shielded: Boolean = false
        private set
    private var emptyFrames = 0

    /** Returns true when the shield state changed. */
    fun onFrame(reading: FaceReading): Boolean {
        val before = shielded
        if (reading.faces == 0) {
            emptyFrames++
            if (emptyFrames >= noFaceFrames) shielded = true
        } else {
            emptyFrames = 0
            val yaw = abs(reading.yawDegrees)
            when {
                reading.faces > 1 || yaw > shieldYaw -> shielded = true
                reading.faces == 1 && yaw < clearYaw -> shielded = false
            }
        }
        return shielded != before
    }

    /** Low light: no camera, so the tilt alone decides (a phone turned sideways shows the screen to others). */
    fun onTilt(rollDegrees: Float, shieldRoll: Float = 40f, clearRoll: Float = 28f): Boolean {
        val before = shielded
        val roll = abs(rollDegrees)
        if (roll > shieldRoll) shielded = true else if (roll < clearRoll) shielded = false
        return shielded != before
    }

    fun reset() {
        shielded = false
        emptyFrames = 0
    }
}

/** Device motion from the accelerometer: tilt and whether the phone has been still long enough. */
class MotionTracker(
    private val moveThreshold: Float = 8f,
    private val stillThreshold: Float = 3f,
) {
    var pitch = 0f
        private set
    var roll = 0f
        private set
    private var refPitch = Float.NaN
    private var refRoll = Float.NaN
    private var stillSince = 0L

    /** Feed gravity; returns true when the tilt moved more than [moveThreshold] since the last check. */
    fun onGravity(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        val norm = kotlin.math.sqrt(x * x + y * y + z * z).takeIf { it > 0.1f } ?: return false
        pitch = Math.toDegrees(kotlin.math.asin((y / norm).coerceIn(-1f, 1f).toDouble())).toFloat()
        roll = Math.toDegrees(kotlin.math.atan2(x.toDouble(), z.toDouble())).toFloat()
        if (refPitch.isNaN()) {
            mark(nowMs)
            return false
        }
        // Stillness is measured against where the phone settled, not against the last check.
        if (maxOf(abs(pitch - stillPitch), abs(angleDiff(roll, stillRoll))) > stillThreshold) {
            stillPitch = pitch
            stillRoll = roll
            stillSince = nowMs
        }
        return maxOf(abs(pitch - refPitch), abs(angleDiff(roll, refRoll))) > moveThreshold
    }

    /** Takes the current tilt as the new reference (after a check). */
    fun mark(nowMs: Long) {
        refPitch = pitch
        refRoll = roll
        stillPitch = pitch
        stillRoll = roll
        stillSince = nowMs
    }

    private var stillPitch = 0f
    private var stillRoll = 0f

    fun stillFor(nowMs: Long): Long = nowMs - stillSince

    private fun angleDiff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
