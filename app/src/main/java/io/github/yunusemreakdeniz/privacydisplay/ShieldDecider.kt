package io.github.yunusemreakdeniz.privacydisplay

import kotlin.math.abs

/** The four levels of the roadmap's event-driven state machine. */
enum class PrivacyState { IDLE, SENSOR_WATCH, ACTIVE_CHECK, SHIELD_ACTIVE }

/**
 * One analysed camera frame. The largest face is taken to be the owner, the one holding the
 * phone; [others] counts every other face, i.e. people who can also see the screen.
 */
data class FaceReading(val faces: Int, val yawDegrees: Float, val others: Int = (faces - 1).coerceAtLeast(0))

/**
 * Decides when the curtain comes down, built so the owner is not darkened by mistake:
 * - a second face is the real threat: down after [threatFrames] frames in a row;
 * - the owner's own head angle only counts when it stays past [shieldYaw] for [yawFrames]
 *   frames (a glance aside doesn't darken the screen);
 * - no face at all only counts when [shieldWhenNoFace] is on, and after [noFaceMs];
 * - up again only after [clearFrames] frames of the owner alone, looking within [clearYaw].
 */
class ShieldDecider(
    private val shieldYaw: Float = 42f,
    private val clearYaw: Float = 32f,
    private val threatFrames: Int = 2,
    private val yawFrames: Int = 4,
    private val clearFrames: Int = 3,
    private val noFaceMs: Long = 2_000,
) {
    var shielded: Boolean = false
        private set
    var shieldWhenNoFace: Boolean = false

    private var threatRun = 0
    private var yawRun = 0
    private var clearRun = 0
    private var noFaceSince = -1L

    /** Returns true when the shield state changed. */
    fun onFrame(reading: FaceReading, nowMs: Long): Boolean {
        val before = shielded
        val yaw = abs(reading.yawDegrees)
        if (reading.faces == 0) {
            if (noFaceSince < 0) noFaceSince = nowMs
            threatRun = 0
            yawRun = 0
            clearRun = 0
            if (shieldWhenNoFace && nowMs - noFaceSince >= noFaceMs) shielded = true
            return shielded != before
        }
        noFaceSince = -1L
        threatRun = if (reading.others > 0) threatRun + 1 else 0
        yawRun = if (reading.others == 0 && yaw > shieldYaw) yawRun + 1 else 0
        clearRun = if (reading.others == 0 && yaw < clearYaw) clearRun + 1 else 0
        when {
            threatRun >= threatFrames || yawRun >= yawFrames -> shielded = true
            shielded && clearRun >= clearFrames -> shielded = false
        }
        return shielded != before
    }

    /** True while something suspicious is building up, so every frame should be analysed. */
    val suspicious: Boolean get() = shielded || threatRun > 0 || yawRun > 0

    /** Low light: no camera, so the tilt alone decides (a phone turned sideways shows the screen to others). */
    fun onTilt(rollDegrees: Float, shieldRoll: Float = 40f, clearRoll: Float = 28f): Boolean {
        val before = shielded
        val roll = abs(rollDegrees)
        if (roll > shieldRoll) shielded = true else if (roll < clearRoll) shielded = false
        return shielded != before
    }

    fun reset() {
        shielded = false
        threatRun = 0
        yawRun = 0
        clearRun = 0
        noFaceSince = -1L
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
    private var stillPitch = 0f
    private var stillRoll = 0f

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

    fun stillFor(nowMs: Long): Long = nowMs - stillSince

    private fun angleDiff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
