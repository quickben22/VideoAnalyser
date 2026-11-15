package com.example.fitpulseai.domain

import com.example.fitpulseai.vision.PoseMath
import com.example.fitpulseai.vision.visible
import com.google.mlkit.vision.pose.PoseLandmark as L
import kotlin.math.abs

private enum class Phase { READY, ACTIVE_PEAK, COOLDOWN }

private class RepMachine(private val type: ExerciseType, private val side: Side) {
    private var phase = Phase.COOLDOWN
    private var lastRep = Long.MIN_VALUE / 2
    private var peak = 0f

    fun update(rest: Boolean, active: Boolean, metric: Float, time: Long): Rep? {
        when (phase) {
            Phase.COOLDOWN -> if (rest && time - lastRep >= 350) phase = Phase.READY
            Phase.READY -> if (active && time - lastRep >= 350) {
                phase = Phase.ACTIVE_PEAK
                peak = metric
            }
            Phase.ACTIVE_PEAK -> {
                peak = maxOf(peak, metric)
                if (rest) {
                    phase = Phase.COOLDOWN
                    lastRep = time
                    return Rep(type, side, peak, time)
                }
            }
        }
        return null
    }

    fun invalidate() { phase = Phase.COOLDOWN }
}

class ExerciseDetector {
    private val machines = ExerciseType.entries.filter { it != ExerciseType.IDLE }
        .flatMap { exercise -> Side.entries.map { side -> (exercise to side) to RepMachine(exercise, side) } }.toMap()
    private val previousWrists = mutableMapOf<Side, Pair<Joint, Long>>()
    private val ankleBaselines = mutableMapOf<Side, Float>()

    fun process(frame: PoseFrame): List<Rep> {
        val torso = PoseMath.torso(frame)
        if (torso == null) {
            machines.values.forEach { it.invalidate() }
            previousWrists.clear()
            return emptyList()
        }
        val reps = mutableListOf<Rep>()
        for (side in Side.entries) {
            val left = side == Side.LEFT
            val shoulder = frame.visible(if (left) L.LEFT_SHOULDER else L.RIGHT_SHOULDER)
            val elbow = frame.visible(if (left) L.LEFT_ELBOW else L.RIGHT_ELBOW)
            val wrist = frame.visible(if (left) L.LEFT_WRIST else L.RIGHT_WRIST)
            val hip = frame.visible(if (left) L.LEFT_HIP else L.RIGHT_HIP)
            val oppositeHip = frame.visible(if (left) L.RIGHT_HIP else L.LEFT_HIP)
            val knee = frame.visible(if (left) L.LEFT_KNEE else L.RIGHT_KNEE)
            val ankle = frame.visible(if (left) L.LEFT_ANKLE else L.RIGHT_ANKLE)
            val standingAnkle = frame.visible(if (left) L.RIGHT_ANKLE else L.LEFT_ANKLE)
            fun update(type: ExerciseType, rest: Boolean, active: Boolean, metric: Float) {
                machines.getValue(type to side).update(rest, active, metric, frame.timestampMs)?.let(reps::add)
            }
            if (shoulder != null && elbow != null && wrist != null) {
                val angle = PoseMath.angle(shoulder, elbow, wrist)
                val prev = previousWrists[side]
                val dt = if (prev != null) frame.timestampMs - prev.second else 0
                val speed = if (prev != null && dt in 1..250) PoseMath.distance(wrist, prev.first) / torso / (dt / 1000f) else 0f
                previousWrists[side] = wrist to frame.timestampMs
                update(ExerciseType.PUNCH_JAB_CROSS, angle < 100f, angle > 155f && speed > 0.5f && abs(wrist.y - shoulder.y) < torso * 0.8f, angle)
            } else {
                machines.getValue(ExerciseType.PUNCH_JAB_CROSS to side).invalidate()
                previousWrists.remove(side)
            }
            if (hip != null && knee != null && standingAnkle != null) {
                val baseline = ankleBaselines[side] ?: standingAnkle.y
                val stationary = abs(standingAnkle.y - baseline) < torso * 0.15f
                if (knee.y > hip.y + torso * 0.25f) ankleBaselines[side] = baseline * 0.9f + standingAnkle.y * 0.1f
                update(ExerciseType.HIGH_KNEE, knee.y > hip.y + torso * 0.25f, knee.y < hip.y && stationary, (hip.y - knee.y) / torso)
            } else {
                machines.getValue(ExerciseType.HIGH_KNEE to side).invalidate()
                ankleBaselines.remove(side)
            }
            if (hip != null && oppositeHip != null && knee != null && ankle != null) {
                val abduction = PoseMath.angle(oppositeHip, hip, knee)
                val extension = PoseMath.angle(hip, knee, ankle)
                update(ExerciseType.SIDE_KICK, abduction < 100f, abduction > 110f && extension > 150f, extension)
            } else machines.getValue(ExerciseType.SIDE_KICK to side).invalidate()
        }
        return reps
    }
}
