package com.example.fitpulseai.domain

import com.google.mlkit.vision.pose.PoseLandmark as L
import org.junit.Assert.*
import org.junit.Test

class ExerciseDetectorTest {
    private fun frame(time: Long, kneeY: Float, confidence: Float = 1f): PoseFrame {
        fun j(x: Float, y: Float, c: Float = 1f) = Joint(x, y, 0f, c)
        return PoseFrame(mapOf(
            L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
            L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
            L.LEFT_KNEE to j(100f, kneeY, confidence), L.RIGHT_KNEE to j(200f, 300f),
            L.LEFT_ANKLE to j(100f, 400f), L.RIGHT_ANKLE to j(200f, 400f)
        ), time, 640, 480)
    }
    @Test fun `high knee counts only after returning to rest`() {
        val detector = ExerciseDetector()
        assertTrue(detector.process(frame(0, 300f)).isEmpty())
        assertTrue(detector.process(frame(400, 180f)).isEmpty())
        val reps = detector.process(frame(500, 300f))
        assertEquals(1, reps.count { it.exercise == ExerciseType.HIGH_KNEE && it.side == Side.LEFT })
        assertTrue(detector.process(frame(600, 300f)).isEmpty())
    }
    @Test fun `occluded knee cancels incomplete rep`() {
        val detector = ExerciseDetector()
        detector.process(frame(0, 300f))
        detector.process(frame(400, 180f))
        detector.process(frame(500, 180f, 0.1f))
        assertTrue(detector.process(frame(600, 300f)).isEmpty())
    }
}
