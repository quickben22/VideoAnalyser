package com.example.fitpulseai.vision

import com.example.fitpulseai.domain.PoseFrame
import com.google.mlkit.vision.pose.PoseLandmark as L
import kotlin.math.hypot

/**
 * ML Kit's bundled pose detector returns one skeleton per frame. In footage with several people
 * it may follow a different person from one moment to the next, and in wide or low-light shots it
 * sometimes reports a collapsed skeleton (shoulders almost touching the hips). Both make the
 * angle-based rep detector fire on movements the tracked person never performed.
 *
 * The gate keeps one plausible subject: it rejects skeletons that are too small or too large for
 * the frame, skeletons whose scale differs from the locked subject, and skeletons that jump to a
 * new position between consecutive frames. If too many frames in a row are rejected it accepts the
 * new subject instead of dropping the rest of the session, so the analysis degrades gracefully.
 */
class PoseGate(
    private val minTorsoFraction: Float = 0.05f,
    private val maxTorsoFraction: Float = 0.75f,
    private val scaleTolerance: Float = 0.35f,
    private val jumpTolerance: Float = 1f,
    private val maxJumpGapMs: Long = 600,
    private val reacquireAfter: Int = 15
) {
    private var lockedTorso: Float? = null
    private var lastX: Float? = null
    private var lastY: Float? = null
    private var lastFrameTime: Long? = null
    private var consecutiveRejects = 0

    var accepted = 0
        private set
    var rejectedByScale = 0
        private set
    var rejectedByJump = 0
        private set
    var subjectSwitches = 0
        private set

    val rejected: Int get() = rejectedByScale + rejectedByJump

    fun accept(frame: PoseFrame): Boolean {
        val previousFrameTime = lastFrameTime
        lastFrameTime = frame.timestampMs
        val torso = PoseMath.torso(frame)
        if (torso == null) {
            consecutiveRejects++
            rejectedByScale++
            return false
        }
        val fraction = torso / minOf(frame.width, frame.height).toFloat()
        if (fraction < minTorsoFraction || fraction > maxTorsoFraction) {
            consecutiveRejects++
            rejectedByScale++
            return false
        }

        val reference = lockedTorso
        if (reference == null) return lock(torso, frame)

        val scaled = torso < reference * (1f - scaleTolerance) || torso > reference * (1f + scaleTolerance)
        val centre = centre(frame)
        val gap = previousFrameTime?.let { frame.timestampMs - it }
        val jumped = centre != null && lastX != null && lastY != null && gap != null &&
            gap in 1..maxJumpGapMs && hypot(centre.first - lastX!!, centre.second - lastY!!) > reference * jumpTolerance

        if (!scaled && !jumped) {
            // Track slow scale and position changes (walking towards or away from the camera).
            lockedTorso = reference * 0.98f + torso * 0.02f
            lastX = centre?.first ?: lastX
            lastY = centre?.second ?: lastY
            consecutiveRejects = 0
            accepted++
            return true
        }

        consecutiveRejects++
        if (scaled) rejectedByScale++ else rejectedByJump++
        if (consecutiveRejects >= reacquireAfter) {
            // The subject is gone for good; adopt whoever the detector is following now.
            return lock(torso, frame, isSwitch = true)
        }
        return false
    }

    fun reset() {
        lockedTorso = null
        lastX = null
        lastY = null
        lastFrameTime = null
        consecutiveRejects = 0
        accepted = 0
        rejectedByScale = 0
        rejectedByJump = 0
        subjectSwitches = 0
    }

    private fun lock(torso: Float, frame: PoseFrame, isSwitch: Boolean = false): Boolean {
        val centre = centre(frame)
        lockedTorso = torso
        lastX = centre?.first
        lastY = centre?.second
        consecutiveRejects = 0
        if (isSwitch) subjectSwitches++
        accepted++
        return true
    }

    private fun centre(frame: PoseFrame): Pair<Float, Float>? {
        val ls = frame.visible(L.LEFT_SHOULDER) ?: return null
        val rs = frame.visible(L.RIGHT_SHOULDER) ?: return null
        val lh = frame.visible(L.LEFT_HIP) ?: return null
        val rh = frame.visible(L.RIGHT_HIP) ?: return null
        return (ls.x + rs.x + lh.x + rh.x) / 4f to (ls.y + rs.y + lh.y + rh.y) / 4f
    }
}
