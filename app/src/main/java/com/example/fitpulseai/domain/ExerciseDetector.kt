package com.example.fitpulseai.domain

import com.example.fitpulseai.vision.PoseFeatures
import com.example.fitpulseai.vision.PoseMath
import com.example.fitpulseai.vision.features
import com.example.fitpulseai.vision.visible
import com.google.mlkit.vision.pose.PoseLandmark as L
import kotlin.math.abs

private enum class Phase { COOLDOWN, READY, ACTIVE }

private class RepMachine(private val type: ExerciseType, private val side: Side) {
    private var phase = Phase.COOLDOWN
    private var lastRep = Long.MIN_VALUE / 2
    private var peak = 0f

    fun update(rest: Boolean, active: Boolean, metric: Float, time: Long): Rep? = when (phase) {
        Phase.COOLDOWN -> {
            if (rest && time - lastRep >= MIN_GAP_MS) phase = Phase.READY
            null
        }
        Phase.READY -> {
            if (active && time - lastRep >= MIN_GAP_MS) {
                phase = Phase.ACTIVE
                peak = metric
            }
            null
        }
        Phase.ACTIVE -> {
            peak = maxOf(peak, metric)
            if (rest) {
                phase = Phase.COOLDOWN
                lastRep = time
                Rep(type, side, peak, time)
            } else null
        }
    }

    fun invalidate() { phase = Phase.COOLDOWN }

    companion object { const val MIN_GAP_MS = 350L }
}

/**
 * Counts repetitions from pose geometry.
 *
 * The first job is deciding what posture the tracked person is in, because the same joint angle
 * means different things standing up and lying down: 160 degrees of knee bend is a squat when
 * upright, but an ordinary straight leg in a plank. Detectors only run inside their own posture,
 * and measurements that also match an unrelated movement are excluded on purpose:
 *
 *  - a side kick needs the foot out to the side and level with the hip, so a lunge's raised heel
 *    and a front knee raise cannot be counted as kicks;
 *  - a punch needs a guard (fist at the shoulder) followed by one fist moving away while the other
 *    stays tucked in, so jumping jacks and symmetric arm swings are not counted;
 *  - a jumping jack needs the feet to come back together with the arms down, so a wide overhead
 *    squat is not counted;
 *  - a squat needs both knees to bend together and the torso to stay upright.
 */
class ExerciseDetector {
    private val machines = buildMap {
        for (side in listOf(Side.LEFT, Side.RIGHT)) {
            put(ExerciseType.PUNCH_JAB_CROSS to side, RepMachine(ExerciseType.PUNCH_JAB_CROSS, side))
            put(ExerciseType.HIGH_KNEE to side, RepMachine(ExerciseType.HIGH_KNEE, side))
            put(ExerciseType.SIDE_KICK to side, RepMachine(ExerciseType.SIDE_KICK, side))
        }
        put(ExerciseType.SQUAT to Side.BOTH, RepMachine(ExerciseType.SQUAT, Side.BOTH))
        put(ExerciseType.PUSH_UP to Side.BOTH, RepMachine(ExerciseType.PUSH_UP, Side.BOTH))
        put(ExerciseType.JUMPING_JACK to Side.BOTH, RepMachine(ExerciseType.JUMPING_JACK, Side.BOTH))
    }
    private val previousWrists = mutableMapOf<Side, Pair<Joint, Long>>()
    private val ankleBaselines = mutableMapOf<Side, Float>()

    fun process(frame: PoseFrame, features: PoseFeatures? = frame.features()): List<Rep> {
        if (features == null) {
            machines.values.forEach { it.invalidate() }
            previousWrists.clear()
            return emptyList()
        }
        val reps = mutableListOf<Rep>()
        val sink = Sink(frame.timestampMs, reps)

        if (features.posture == Posture.STANDING) {
            standing(frame, features, sink)
        } else {
            sink.invalidate(ExerciseType.PUNCH_JAB_CROSS)
            sink.invalidate(ExerciseType.HIGH_KNEE)
            sink.invalidate(ExerciseType.SIDE_KICK)
            sink.invalidate(ExerciseType.SQUAT)
            sink.invalidate(ExerciseType.JUMPING_JACK)
            previousWrists.clear()
            ankleBaselines.clear()
        }

        // A front-on push-up is foreshortened: the torso vector often points up in the 2D pose
        // (150-180 degrees) and the ankles are lost, so the FLOOR label alone is too narrow.
        if (features.posture == Posture.FLOOR || features.uprightDeg > 140f) pushUps(features, sink)
        else sink.invalidate(ExerciseType.PUSH_UP)

        return reps
    }

    private inner class Sink(private val time: Long, private val into: MutableList<Rep>) {
        fun update(type: ExerciseType, side: Side, rest: Boolean, active: Boolean, metric: Float) {
            machines.getValue(type to side).update(rest, active, metric, time)?.let(into::add)
        }

        fun invalidate(type: ExerciseType) {
            machines.keys.filter { it.first == type }.forEach { machines.getValue(it).invalidate() }
        }
    }

    private fun standing(frame: PoseFrame, f: PoseFeatures, sink: Sink) {
        for (side in listOf(Side.LEFT, Side.RIGHT)) {
            val left = side == Side.LEFT
            val shoulder = frame.visible(if (left) L.LEFT_SHOULDER else L.RIGHT_SHOULDER)
            val elbow = frame.visible(if (left) L.LEFT_ELBOW else L.RIGHT_ELBOW)
            val wrist = frame.visible(if (left) L.LEFT_WRIST else L.RIGHT_WRIST)
            val otherElbow = if (left) f.elbowRight else f.elbowLeft
            val otherShoulder = frame.visible(if (left) L.RIGHT_SHOULDER else L.LEFT_SHOULDER)
            val otherWrist = frame.visible(if (left) L.RIGHT_WRIST else L.LEFT_WRIST)
            val hip = frame.visible(if (left) L.LEFT_HIP else L.RIGHT_HIP)
            val oppositeHip = frame.visible(if (left) L.RIGHT_HIP else L.LEFT_HIP)
            val knee = frame.visible(if (left) L.LEFT_KNEE else L.RIGHT_KNEE)
            val ankle = frame.visible(if (left) L.LEFT_ANKLE else L.RIGHT_ANKLE)
            val standingAnkle = frame.visible(if (left) L.RIGHT_ANKLE else L.LEFT_ANKLE)
            val angle = if (left) f.elbowLeft else f.elbowRight

            if (shoulder != null && elbow != null && wrist != null && angle != null) {
                val prev = previousWrists[side]
                val dt = if (prev != null) frame.timestampMs - prev.second else 0
                val speed = if (prev != null && dt in 1..250) {
                    PoseMath.distance(wrist, prev.first) / f.torso / (dt / 1000f)
                } else 0f
                previousWrists[side] = wrist to frame.timestampMs
                // A jab starts with the fist folded at the chin and ends with the arm straight
                // at shoulder height. A punch towards the camera is foreshortened, so the wrist
                // does not have to travel far in 2D. What rules out a jumping jack or a lateral
                // arm swing is that the *other* arm is not flung out sideways at the same time.
                val wristFromShoulder = PoseMath.distance(wrist, shoulder) / f.torso
                val wristAbove = (shoulder.y - wrist.y) / f.torso
                val otherWristOut = otherWrist != null && otherShoulder != null &&
                    abs(otherWrist.x - otherShoulder.x) > f.torso * 0.55f
                val otherIsNotExtended = otherElbow == null || otherElbow < 130f
                val atShoulderHeight = abs(wrist.y - shoulder.y) < f.torso * 0.5f
                sink.update(
                    ExerciseType.PUNCH_JAB_CROSS, side,
                    rest = angle < 100f && wristFromShoulder < 0.6f && wristAbove < 0.3f,
                    active = angle > 155f && speed > 0.5f && atShoulderHeight &&
                        otherIsNotExtended && !otherWristOut,
                    metric = angle
                )
            } else {
                machines.getValue(ExerciseType.PUNCH_JAB_CROSS to side).invalidate()
                previousWrists.remove(side)
            }

            if (hip != null && knee != null && standingAnkle != null) {
                val baseline = ankleBaselines[side] ?: standingAnkle.y
                val stationary = abs(standingAnkle.y - baseline) < f.torso * 0.25f
                if (knee.y >= hip.y + f.torso * 0.1f) ankleBaselines[side] = baseline * 0.9f + standingAnkle.y * 0.1f
                // Image y grows downwards: in a high knee the knee reaches about hip height.
                // A standing knee sits a whole torso lower, so this still has a clear rest state.
                val kneeIsUp = knee.y < hip.y + f.torso * 0.1f
                // In a high knee the other leg is straight. A lunge bends *both* knees, and its
                // front knee also rises to hip height, so it used to be counted as a knee lift.
                val otherKnee = if (left) f.kneeRight else f.kneeLeft
                val supportLegStraight = otherKnee == null || otherKnee > 140f
                sink.update(
                    ExerciseType.HIGH_KNEE, side,
                    rest = !kneeIsUp,
                    active = kneeIsUp && stationary && supportLegStraight,
                    metric = (hip.y - knee.y) / f.torso
                )
            } else {
                machines.getValue(ExerciseType.HIGH_KNEE to side).invalidate()
                ankleBaselines.remove(side)
            }

            val abduction = if (left) f.abductionLeft else f.abductionRight
            val extension = if (left) f.kneeLeft else f.kneeRight
            if (hip != null && oppositeHip != null && knee != null && ankle != null && standingAnkle != null &&
                abduction != null && extension != null
            ) {
                val footIsRaised = ankle.y < standingAnkle.y - f.torso * 0.25f
                // A raised heel in a lunge is still a whole torso below the hip, and a front knee
                // raise keeps the foot under the body. A real side kick lifts the foot to roughly
                // hip height and moves it out to the side.
                val footAtHipHeight = abs(ankle.y - hip.y) < f.torso * 0.35f
                val footOutToSide = abs(ankle.x - hip.x) > f.torso * 0.35f
                sink.update(
                    ExerciseType.SIDE_KICK, side,
                    rest = abduction < 100f,
                    active = abduction > 110f && extension > 150f && footIsRaised &&
                        footAtHipHeight && footOutToSide,
                    metric = extension
                )
            } else machines.getValue(ExerciseType.SIDE_KICK to side).invalidate()
        }

        val knees = listOfNotNull(f.kneeLeft, f.kneeRight)
        if (knees.size == 2) {
            // A squat bends both knees together; a high knee folds only one leg, so require both.
            sink.update(
                ExerciseType.SQUAT, Side.BOTH,
                rest = knees.minOrNull()!! > 160f,
                active = knees.maxOrNull()!! < 120f,
                metric = 180f - knees.average().toFloat()
            )
        } else machines.getValue(ExerciseType.SQUAT to Side.BOTH).invalidate()

        // A jumping jack opens the legs and throws both arms overhead, then closes back to feet
        // together and arms down. Requiring the closed position is what distinguishes it from a
        // wide overhead squat, where the feet never come back together.
        val feetWide = f.ankleSpread?.let { it > 0.8f } ?: false
        val feetTogether = f.ankleSpread?.let { it < 0.5f } ?: false
        val wristsOverhead = (f.wristAboveShoulderLeft ?: 0f) > 0.2f &&
            (f.wristAboveShoulderRight ?: 0f) > 0.2f
        sink.update(
            ExerciseType.JUMPING_JACK, Side.BOTH,
            rest = feetTogether && !wristsOverhead,
            active = feetWide && wristsOverhead && knees.size == 2 && knees.minOrNull()!! > 140f,
            metric = f.ankleSpread ?: 0f
        )
    }

    private fun pushUps(f: PoseFeatures, sink: Sink) {
        val elbows = listOfNotNull(f.elbowLeft, f.elbowRight)
        if (elbows.size == 2) {
            // Top of a push-up is straight arms, the bottom is a deep elbow bend.
            sink.update(
                ExerciseType.PUSH_UP, Side.BOTH,
                rest = elbows.minOrNull()!! > 160f,
                active = elbows.maxOrNull()!! < 110f,
                metric = elbows.average().toFloat()
            )
        } else machines.getValue(ExerciseType.PUSH_UP to Side.BOTH).invalidate()
    }
}
