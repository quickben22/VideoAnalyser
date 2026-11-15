package com.example.fitpulseai.vision

import com.example.fitpulseai.domain.Joint
import com.example.fitpulseai.domain.PoseFrame
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

object PoseMath {
    fun angle(a: Joint, b: Joint, c: Joint): Float {
        val difference = abs((atan2(c.y - b.y, c.x - b.x) - atan2(a.y - b.y, a.x - b.x)) * 180f / PI.toFloat())
        return if (difference > 180f) 360f - difference else difference
    }
    fun distance(a: Joint, b: Joint): Float = hypot(a.x - b.x, a.y - b.y)
    fun torso(frame: PoseFrame): Float? {
        val ls = frame.visible(PoseLandmark.LEFT_SHOULDER) ?: return null
        val rs = frame.visible(PoseLandmark.RIGHT_SHOULDER) ?: return null
        val lh = frame.visible(PoseLandmark.LEFT_HIP) ?: return null
        val rh = frame.visible(PoseLandmark.RIGHT_HIP) ?: return null
        return hypot((ls.x + rs.x - lh.x - rh.x) / 2, (ls.y + rs.y - lh.y - rh.y) / 2)
            .takeIf { it > 1f }
    }
}

fun PoseFrame.visible(id: Int): Joint? = joints[id]?.takeIf { it.confidence >= 0.65f }

class LandmarkSmoother(private val alpha: Float = 0.45f) {
    private val previous = mutableMapOf<Int, Joint>()
    fun smooth(frame: PoseFrame): PoseFrame {
        val smoothed = frame.joints.mapValues { (id, current) ->
            val old = previous[id]
            if (old == null || current.confidence < 0.65f || old.confidence < 0.65f) current
            else Joint(
                old.x + alpha * (current.x - old.x), old.y + alpha * (current.y - old.y),
                old.z + alpha * (current.z - old.z), current.confidence
            )
        }
        previous.clear()
        previous.putAll(smoothed)
        return frame.copy(joints = smoothed)
    }
    fun reset() = previous.clear()
}
