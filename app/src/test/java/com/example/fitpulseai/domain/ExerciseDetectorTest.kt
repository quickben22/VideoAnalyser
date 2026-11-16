package com.example.fitpulseai.domain

import com.google.mlkit.vision.pose.PoseLandmark as L
import org.junit.Assert.*
import org.junit.Test

class ExerciseDetectorTest {
    private fun j(x: Float, y: Float, c: Float = 1f) = Joint(x, y, 0f, c)

    private fun frame(time: Long, joints: Map<Int, Joint>) = PoseFrame(joints, time, 640, 480)

    /** Upright body with straight legs; [kneeY] moves both knees. */
    private fun standing(time: Long, leftKnee: Pair<Float, Float> = 100f to 300f, rightKnee: Pair<Float, Float> = 200f to 300f) = frame(
        time,
        mapOf(
            L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
            L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
            L.LEFT_KNEE to j(leftKnee.first, leftKnee.second), L.RIGHT_KNEE to j(rightKnee.first, rightKnee.second),
            L.LEFT_ANKLE to j(100f, 400f), L.RIGHT_ANKLE to j(200f, 400f)
        )
    )

    /** Side view in a push-up: torso horizontal, arms either straight or bent at the elbow. */
    private fun floor(time: Long, bent: Boolean) = frame(
        time,
        mapOf(
            L.LEFT_SHOULDER to j(if (bent) 350f else 300f, 200f), L.RIGHT_SHOULDER to j(if (bent) 430f else 380f, 200f),
            L.LEFT_HIP to j(540f, 200f), L.RIGHT_HIP to j(620f, 200f),
            L.LEFT_ELBOW to j(350f, 250f), L.RIGHT_ELBOW to j(430f, 250f),
            L.LEFT_WRIST to j(if (bent) 400f else 400f, if (bent) 250f else 300f),
            L.RIGHT_WRIST to j(480f, if (bent) 250f else 300f)
        )
    )

    @Test fun `high knee counts only after returning to rest`() {
        val detector = ExerciseDetector()
        assertTrue(detector.process(standing(0)).isEmpty())
        assertTrue(detector.process(standing(400, leftKnee = 100f to 180f)).isEmpty())
        val reps = detector.process(standing(500))
        assertEquals(1, reps.count { it.exercise == ExerciseType.HIGH_KNEE && it.side == Side.LEFT })
        assertTrue(detector.process(standing(600)).isEmpty())
    }

    @Test fun `occluded knee cancels incomplete rep`() {
        val detector = ExerciseDetector()
        detector.process(standing(0))
        detector.process(standing(400, leftKnee = 100f to 180f))
        detector.process(
            frame(
                500,
                mapOf(
                    L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                    L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                    L.LEFT_KNEE to j(100f, 180f, 0.1f), L.RIGHT_KNEE to j(200f, 300f),
                    L.LEFT_ANKLE to j(100f, 400f), L.RIGHT_ANKLE to j(200f, 400f)
                )
            )
        )
        assertTrue(detector.process(standing(600)).isEmpty())
    }

    @Test fun `squat needs both knees bent together`() {
        val detector = ExerciseDetector()
        assertTrue(detector.process(standing(0)).isEmpty())
        // Knees splay outwards, which is how a squat looks from the front: both knee angles drop.
        detector.process(standing(200, leftKnee = 160f to 290f, rightKnee = 140f to 290f))
        detector.process(standing(400, leftKnee = 160f to 290f, rightKnee = 140f to 290f))
        val reps = detector.process(standing(600))
        assertEquals(1, reps.count { it.exercise == ExerciseType.SQUAT && it.side == Side.BOTH })
    }

    @Test fun `one folded leg alone is not a squat`() {
        val detector = ExerciseDetector()
        detector.process(standing(0))
        detector.process(standing(200, leftKnee = 100f to 180f))
        val reps = detector.process(standing(400))
        assertEquals(0, reps.count { it.exercise == ExerciseType.SQUAT })
    }

    @Test fun `leg spread with both feet planted is not a side kick`() {
        val detector = ExerciseDetector()
        // Jumping-jack shape: left knee out to the side, but the left foot is on the floor.
        val spread = frame(
            0,
            mapOf(
                L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                L.LEFT_KNEE to j(50f, 300f), L.RIGHT_KNEE to j(200f, 300f),
                L.LEFT_ANKLE to j(0f, 400f), L.RIGHT_ANKLE to j(200f, 400f)
            )
        )
        val reps = mutableListOf<Rep>()
        reps += detector.process(spread.copy(timestampMs = 0))
        reps += detector.process(spread.copy(timestampMs = 200))
        reps += detector.process(spread.copy(timestampMs = 400))
        reps += detector.process(standing(600))
        assertEquals(0, reps.count { it.exercise == ExerciseType.SIDE_KICK })
    }

    @Test fun `floor posture counts push ups`() {
        val detector = ExerciseDetector()
        assertTrue(detector.process(floor(0, bent = false)).isEmpty())
        detector.process(floor(200, bent = true))
        detector.process(floor(400, bent = true))
        val reps = detector.process(floor(600, bent = false))
        assertEquals(1, reps.count { it.exercise == ExerciseType.PUSH_UP && it.side == Side.BOTH })
    }

    @Test fun `inverted horizontal torso counts push ups even when the ankles are lost`() {
        // Front-on push-ups are foreshortened: ML Kit reports the hips above the shoulders and
        // loses the ankles, so the posture classifier cannot rely on hip height alone.
        fun horizontal(time: Long, elbowAngle: Float): Pair<PoseFrame, com.example.fitpulseai.vision.PoseFeatures> {
            val f = com.example.fitpulseai.vision.PoseFeatures(
                torso = 100f, uprightDeg = 165f,
                kneeLeft = null, kneeRight = null,
                elbowLeft = elbowAngle, elbowRight = elbowAngle,
                abductionLeft = null, abductionRight = null,
                wristAboveShoulderLeft = null, wristAboveShoulderRight = null,
                ankleSpread = null, wristSpread = null,
                hipAboveAnkleLeft = null, hipAboveAnkleRight = null
            )
            return standing(time) to f
        }
        val detector = ExerciseDetector()
        val reps = mutableListOf<Rep>()
        listOf(170f, 85f, 85f, 170f).forEachIndexed { index, angle ->
            val (frame, features) = horizontal(index * 200L, angle)
            reps += detector.process(frame, features)
        }
        assertEquals(1, reps.count { it.exercise == ExerciseType.PUSH_UP && it.side == Side.BOTH })
    }

    @Test fun `upright arm work is not counted as a push up`() {
        val detector = ExerciseDetector()
        repeat(4) { step ->
            assertTrue(detector.process(standing(step * 200L)).none { it.exercise == ExerciseType.PUSH_UP })
        }
    }

    @Test fun `symmetric arm extension is not a punch`() {
        fun arms(angle: String) = if (angle == "guard") mapOf(
            L.LEFT_ELBOW to j(100f, 140f), L.RIGHT_ELBOW to j(200f, 140f),
            L.LEFT_WRIST to j(70f, 120f), L.RIGHT_WRIST to j(230f, 120f)
        ) else mapOf(
            L.LEFT_ELBOW to j(140f, 100f), L.RIGHT_ELBOW to j(240f, 100f),
            L.LEFT_WRIST to j(180f, 100f), L.RIGHT_WRIST to j(280f, 100f)
        )
        fun pose(time: Long, phase: String) = frame(
            time,
            mapOf(
                L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                L.LEFT_KNEE to j(100f, 300f), L.RIGHT_KNEE to j(200f, 300f),
                L.LEFT_ANKLE to j(100f, 400f), L.RIGHT_ANKLE to j(200f, 400f)
            ) + arms(phase)
        )
        val detector = ExerciseDetector()
        val reps = mutableListOf<Rep>()
        reps += detector.process(pose(0, "guard"))
        reps += detector.process(pose(200, "extended"))
        reps += detector.process(pose(400, "extended"))
        reps += detector.process(pose(600, "guard"))
        assertEquals(0, reps.count { it.exercise == ExerciseType.PUNCH_JAB_CROSS })
    }

    @Test fun `punch counts one rep from a guard to an extension and back`() {
        // Left fist starts at the chin, shoots out level with the shoulder, then returns.
        fun arm(elbowX: Float, elbowY: Float, wristX: Float, wristY: Float) = mapOf(
            L.LEFT_ELBOW to j(elbowX, elbowY), L.LEFT_WRIST to j(wristX, wristY),
            // The other arm stays folded, as it would during a jab.
            L.RIGHT_ELBOW to j(200f, 140f), L.RIGHT_WRIST to j(220f, 120f)
        )
        fun pose(time: Long, elbowX: Float, elbowY: Float, wristX: Float, wristY: Float) = frame(
            time,
            mapOf(
                L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                L.LEFT_KNEE to j(100f, 300f), L.RIGHT_KNEE to j(200f, 300f),
                L.LEFT_ANKLE to j(100f, 400f), L.RIGHT_ANKLE to j(200f, 400f)
            ) + arm(elbowX, elbowY, wristX, wristY)
        )
        val detector = ExerciseDetector()
        val reps = mutableListOf<Rep>()
        reps += detector.process(pose(0, 80f, 130f, 110f, 120f))
        reps += detector.process(pose(200, 150f, 105f, 190f, 100f))
        reps += detector.process(pose(400, 150f, 105f, 190f, 100f))
        reps += detector.process(pose(600, 80f, 130f, 110f, 120f))
        assertEquals(1, reps.count { it.exercise == ExerciseType.PUNCH_JAB_CROSS && it.side == Side.LEFT })
    }

    @Test fun `arm swings through shoulder height are not punches`() {
        // Jumping-jack arms: elbow straight the whole time, so the fist never visits a guard.
        fun pose(time: Long, wristY: Float) = frame(
            time,
            mapOf(
                L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                L.LEFT_KNEE to j(100f, 300f), L.RIGHT_KNEE to j(200f, 300f),
                L.LEFT_ANKLE to j(100f, 400f), L.RIGHT_ANKLE to j(200f, 400f),
                L.LEFT_ELBOW to j(60f, wristY + 20f), L.RIGHT_ELBOW to j(240f, wristY + 20f),
                L.LEFT_WRIST to j(20f, wristY), L.RIGHT_WRIST to j(280f, wristY)
            )
        )
        val detector = ExerciseDetector()
        val reps = mutableListOf<Rep>()
        (0..8).forEach { step ->
            val y = 320f - step * 30f
            reps += detector.process(pose(step * 100L, y))
        }
        assertEquals(0, reps.count { it.exercise == ExerciseType.PUNCH_JAB_CROSS })
    }

    @Test fun `one extended arm with the other arm out to the side is not a punch`() {
        // Both arms are held away from the body, as in a lateral raise. The wrist must have a
        // guard to return to, otherwise arm swings read as a stream of jabs.
        fun pose(time: Long, leftOut: Boolean) = frame(
            time,
            mapOf(
                L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                L.LEFT_KNEE to j(100f, 300f), L.RIGHT_KNEE to j(200f, 300f),
                L.LEFT_ANKLE to j(100f, 400f), L.RIGHT_ANKLE to j(200f, 400f),
                // The other arm stays bent but well away from the chest the whole time.
                L.RIGHT_ELBOW to j(240f, 130f), L.RIGHT_WRIST to j(290f, 100f),
                L.LEFT_ELBOW to j(if (leftOut) 150f else 80f, if (leftOut) 105f else 130f),
                L.LEFT_WRIST to j(if (leftOut) 190f else 110f, if (leftOut) 100f else 120f)
            )
        )
        val detector = ExerciseDetector()
        val reps = mutableListOf<Rep>()
        reps += detector.process(pose(0, leftOut = false))
        reps += detector.process(pose(200, leftOut = true))
        reps += detector.process(pose(400, leftOut = true))
        reps += detector.process(pose(600, leftOut = false))
        assertEquals(0, reps.count { it.exercise == ExerciseType.PUNCH_JAB_CROSS })
    }

    @Test fun `rear leg of a lunge is not a side kick`() {
        // Back leg straight and its heel lifted, but the foot stays low and behind the body.
        val lunge = frame(
            0,
            mapOf(
                L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                L.LEFT_KNEE to j(70f, 250f), L.RIGHT_KNEE to j(170f, 280f),
                L.LEFT_ANKLE to j(40f, 300f), L.RIGHT_ANKLE to j(210f, 400f)
            )
        )
        val detector = ExerciseDetector()
        val reps = mutableListOf<Rep>()
        reps += detector.process(standing(0))
        reps += detector.process(lunge.copy(timestampMs = 200))
        reps += detector.process(lunge.copy(timestampMs = 400))
        reps += detector.process(standing(600))
        assertEquals(0, reps.count { it.exercise == ExerciseType.SIDE_KICK })
    }

    @Test fun `leg raised in front is not a side kick`() {        // Knee comes up in front of the hip and the shin extends forwards, not sideways.
        val pose = frame(
            0,
            mapOf(
                L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                L.LEFT_KNEE to j(105f, 130f), L.RIGHT_KNEE to j(200f, 300f),
                L.LEFT_ANKLE to j(110f, 40f), L.RIGHT_ANKLE to j(200f, 400f)
            )
        )
        val detector = ExerciseDetector()
        val reps = mutableListOf<Rep>()
        reps += detector.process(pose.copy(timestampMs = 0))
        reps += detector.process(pose.copy(timestampMs = 300))
        reps += detector.process(standing(600))
        assertEquals(0, reps.count { it.exercise == ExerciseType.SIDE_KICK })
    }

    @Test fun `side kick needs the foot out to the side`() {
        // Left leg abducts to the side, foot at hip height, shin straight.
        val kick = frame(
            0,
            mapOf(
                L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                L.LEFT_KNEE to j(40f, 180f), L.RIGHT_KNEE to j(200f, 300f),
                L.LEFT_ANKLE to j(0f, 170f), L.RIGHT_ANKLE to j(200f, 400f)
            )
        )
        val detector = ExerciseDetector()
        assertTrue(detector.process(standing(0)).isEmpty())
        assertTrue(detector.process(kick.copy(timestampMs = 200)).isEmpty())
        assertTrue(detector.process(kick.copy(timestampMs = 400)).isEmpty())
        val reps = detector.process(standing(600))
        assertEquals(1, reps.count { it.exercise == ExerciseType.SIDE_KICK && it.side == Side.LEFT })
    }

    @Test fun `jumping jack counts one rep per open and close`() {
        fun jack(time: Long, open: Boolean) = frame(
            time,
            mapOf(
                L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                L.LEFT_KNEE to j(if (open) 40f else 100f, 300f),
                L.RIGHT_KNEE to j(if (open) 260f else 200f, 300f),
                L.LEFT_ANKLE to j(if (open) 0f else 140f, 400f),
                L.RIGHT_ANKLE to j(if (open) 300f else 160f, 400f),
                L.LEFT_ELBOW to j(if (open) 80f else 80f, if (open) 60f else 130f),
                L.RIGHT_ELBOW to j(220f, if (open) 60f else 130f),
                L.LEFT_WRIST to j(if (open) 60f else 95f, if (open) 20f else 160f),
                L.RIGHT_WRIST to j(if (open) 240f else 205f, if (open) 20f else 160f)
            )
        )
        val detector = ExerciseDetector()
        val reps = mutableListOf<Rep>()
        reps += detector.process(jack(0, open = false))
        reps += detector.process(jack(200, open = true))
        reps += detector.process(jack(400, open = true))
        reps += detector.process(jack(600, open = false))
        assertEquals(1, reps.count { it.exercise == ExerciseType.JUMPING_JACK && it.side == Side.BOTH })
    }

    @Test fun `lunge with both knees bent is not a high knee`() {
        fun lungeFrame(time: Long, kneeUp: Boolean) = frame(
            time,
            mapOf(
                L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                L.LEFT_KNEE to j(100f, if (kneeUp) 190f else 300f), L.RIGHT_KNEE to j(200f, 250f),
                L.LEFT_ANKLE to j(100f, 300f), L.RIGHT_ANKLE to j(200f, 400f)
            )
        )
        // Both knees are bent in a lunge, unlike a high knee where the support leg is straight.
        val features = com.example.fitpulseai.vision.PoseFeatures(
            torso = 100f, uprightDeg = 3f,
            kneeLeft = 112f, kneeRight = 116f, elbowLeft = null, elbowRight = null,
            abductionLeft = null, abductionRight = null,
            wristAboveShoulderLeft = null, wristAboveShoulderRight = null,
            ankleSpread = null, wristSpread = null,
            hipAboveAnkleLeft = 0.8f, hipAboveAnkleRight = 0.8f
        )
        val detector = ExerciseDetector()
        val reps = mutableListOf<Rep>()
        reps += detector.process(lungeFrame(0, kneeUp = false), features)
        reps += detector.process(lungeFrame(200, kneeUp = true), features)
        reps += detector.process(lungeFrame(400, kneeUp = false), features)
        assertEquals(0, reps.count { it.exercise == ExerciseType.HIGH_KNEE })
    }

    @Test fun `high knee still counts when the support leg is straight`() {
        fun march(time: Long, kneeUp: Boolean) = frame(
            time,
            mapOf(
                L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
                L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
                L.LEFT_KNEE to j(100f, if (kneeUp) 190f else 300f), L.RIGHT_KNEE to j(200f, 300f),
                L.LEFT_ANKLE to j(100f, 300f), L.RIGHT_ANKLE to j(200f, 400f)
            )
        )
        val features = com.example.fitpulseai.vision.PoseFeatures(
            torso = 100f, uprightDeg = 3f,
            kneeLeft = 60f, kneeRight = 176f, elbowLeft = null, elbowRight = null,
            abductionLeft = null, abductionRight = null,
            wristAboveShoulderLeft = null, wristAboveShoulderRight = null,
            ankleSpread = null, wristSpread = null,
            hipAboveAnkleLeft = 1f, hipAboveAnkleRight = 1f
        )
        val detector = ExerciseDetector()
        val reps = mutableListOf<Rep>()
        reps += detector.process(march(0, kneeUp = false), features)
        reps += detector.process(march(200, kneeUp = true), features)
        reps += detector.process(march(400, kneeUp = false), features)
        assertEquals(1, reps.count { it.exercise == ExerciseType.HIGH_KNEE && it.side == Side.LEFT })
    }

    @Test fun `wide overhead squat is not a jumping jack`() {
        // Feet stay wide and the knees bend, so the feet never return together.
        fun squat(time: Long, kneesBent: Boolean) = frame(
            time,
            mapOf(
                L.LEFT_SHOULDER to j(100f, if (kneesBent) 220f else 100f),
                L.RIGHT_SHOULDER to j(200f, if (kneesBent) 220f else 100f),
                L.LEFT_HIP to j(100f, if (kneesBent) 300f else 200f),
                L.RIGHT_HIP to j(200f, if (kneesBent) 300f else 200f),
                L.LEFT_KNEE to j(40f, if (kneesBent) 330f else 300f),
                L.RIGHT_KNEE to j(260f, if (kneesBent) 330f else 300f),
                L.LEFT_ANKLE to j(0f, 400f), L.RIGHT_ANKLE to j(300f, 400f),
                L.LEFT_ELBOW to j(70f, 40f), L.RIGHT_ELBOW to j(230f, 40f),
                L.LEFT_WRIST to j(50f, 0f), L.RIGHT_WRIST to j(250f, 0f)
            )
        )
        val detector = ExerciseDetector()
        val reps = mutableListOf<Rep>()
        listOf(false, true, true, false).forEachIndexed { index, bent ->
            reps += detector.process(squat(index * 200L, bent))
        }
        assertEquals(0, reps.count { it.exercise == ExerciseType.JUMPING_JACK })
    }
}
