package com.example.fitpulseai.vision

import com.example.fitpulseai.domain.PoseFrame
import com.google.mlkit.vision.pose.PoseLandmark as L
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Scale-normalised body measurements for one pose frame. Every length is divided by the torso
 * length, so the numbers mean the same thing whether the subject is near or far from the camera.
 *
 * Image y grows downwards, so "above" is a smaller y. Positive [uprightDeg] means the torso leans
 * away from vertical; 90 degrees means the torso is horizontal (push-up, plank, lying down).
 */
data class PoseFeatures(
    val torso: Float,
    val uprightDeg: Float,
    val kneeLeft: Float?,
    val kneeRight: Float?,
    val elbowLeft: Float?,
    val elbowRight: Float?,
    val abductionLeft: Float?,
    val abductionRight: Float?,
    val wristAboveShoulderLeft: Float?,
    val wristAboveShoulderRight: Float?,
    val ankleSpread: Float?,
    val wristSpread: Float?,
    val hipAboveAnkleLeft: Float?,
    val hipAboveAnkleRight: Float?
) {
    /**
     * Coarse body orientation. The bands leave gaps on purpose: a pose in transition is
     * reported as UNKNOWN so no exercise is counted from it.
     */
    val posture: com.example.fitpulseai.domain.Posture
        get() {
            // In a plank or push-up the hips and ankles are roughly level in the image, so their
            // difference is close to zero. When standing the hips sit well above the ankles and
            // this value is strongly negative. This also covers push-ups seen from the front,
            // where the torso is foreshortened and the raw angle can read 150+ degrees.
            val hipsLevelWithAnkles = abs(hipAboveAnkleLeft ?: 2f) < 0.7f &&
                abs(hipAboveAnkleRight ?: 2f) < 0.7f
            return when {
                uprightDeg <= 40f -> com.example.fitpulseai.domain.Posture.STANDING
                hipsLevelWithAnkles && uprightDeg > 45f -> com.example.fitpulseai.domain.Posture.FLOOR
                uprightDeg in 50f..125f -> com.example.fitpulseai.domain.Posture.FLOOR
                uprightDeg >= 140f -> com.example.fitpulseai.domain.Posture.BENT_OVER
                else -> com.example.fitpulseai.domain.Posture.UNKNOWN
            }
        }
}

fun PoseFrame.features(): PoseFeatures? {
    val torso = PoseMath.torso(this) ?: return null
    val ls = visible(L.LEFT_SHOULDER)
    val rs = visible(L.RIGHT_SHOULDER)
    val lh = visible(L.LEFT_HIP)
    val rh = visible(L.RIGHT_HIP)
    val la = visible(L.LEFT_ANKLE)
    val ra = visible(L.RIGHT_ANKLE)
    val lk = visible(L.LEFT_KNEE)
    val rk = visible(L.RIGHT_KNEE)
    val le = visible(L.LEFT_ELBOW)
    val re = visible(L.RIGHT_ELBOW)
    val lw = visible(L.LEFT_WRIST)
    val rw = visible(L.RIGHT_WRIST)

    val upright = if (ls != null && rs != null && lh != null && rh != null) {
        val sx = (ls.x + rs.x) / 2
        val sy = (ls.y + rs.y) / 2
        val hx = (lh.x + rh.x) / 2
        val hy = (lh.y + rh.y) / 2
        // Angle between the torso vector and straight down (0, 1).
        abs(atan2(hx - sx, hy - sy) * 180f / PI.toFloat())
    } else 0f

    fun knee(hip: com.example.fitpulseai.domain.Joint?, k: com.example.fitpulseai.domain.Joint?, ankle: com.example.fitpulseai.domain.Joint?) =
        if (hip != null && k != null && ankle != null) PoseMath.angle(hip, k, ankle) else null

    fun elbow(shoulder: com.example.fitpulseai.domain.Joint?, e: com.example.fitpulseai.domain.Joint?, w: com.example.fitpulseai.domain.Joint?) =
        if (shoulder != null && e != null && w != null) PoseMath.angle(shoulder, e, w) else null

    return PoseFeatures(
        torso = torso,
        uprightDeg = upright,
        kneeLeft = knee(lh, lk, la),
        kneeRight = knee(rh, rk, ra),
        elbowLeft = elbow(ls, le, lw),
        elbowRight = elbow(rs, re, rw),
        abductionLeft = if (rh != null && lh != null && lk != null) PoseMath.angle(rh, lh, lk) else null,
        abductionRight = if (lh != null && rh != null && rk != null) PoseMath.angle(lh, rh, rk) else null,
        wristAboveShoulderLeft = if (ls != null && lw != null) (ls.y - lw.y) / torso else null,
        wristAboveShoulderRight = if (rs != null && rw != null) (rs.y - rw.y) / torso else null,
        ankleSpread = if (la != null && ra != null) PoseMath.distance(la, ra) / torso else null,
        wristSpread = if (lw != null && rw != null) PoseMath.distance(lw, rw) / torso else null,
        hipAboveAnkleLeft = if (lh != null && la != null) (lh.y - la.y) / torso else null,
        hipAboveAnkleRight = if (rh != null && ra != null) (rh.y - ra.y) / torso else null
    )
}
