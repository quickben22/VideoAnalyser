package com.example.fitpulseai.vision

import com.example.fitpulseai.domain.Joint
import com.example.fitpulseai.domain.PoseFrame
import com.example.fitpulseai.domain.Posture
import com.google.mlkit.vision.pose.PoseLandmark as L
import org.junit.Assert.assertEquals
import org.junit.Test

class PoseFeaturesTest {
    private fun j(x: Float, y: Float) = Joint(x, y, 0f, 1f)

    @Test fun `horizontal torso low above the ankles is floor work`() {
        // Looking at the class doing push-ups: the shoulders and hips are at different heights
        // in the 2D projection, which the raw torso angle reports as 150+ degrees. The hips sit
        // almost level with the ankles, so this must still count as floor work.
        val frame = PoseFrame(
            mapOf(
                L.LEFT_SHOULDER to j(300f, 200f), L.RIGHT_SHOULDER to j(380f, 200f),
                L.LEFT_HIP to j(350f, 100f), L.RIGHT_HIP to j(430f, 100f),
                L.LEFT_ANKLE to j(350f, 100f), L.RIGHT_ANKLE to j(430f, 100f)
            ),
            0, 640, 480
        )
        val features = frame.features()!!
        assertEquals(true, features.uprightDeg > 140f)
        assertEquals(Posture.FLOOR, features.posture)
    }

    @Test fun `bent-over standing pose with hips above the ankles is not floor work`() {
        val frame = PoseFrame(
            mapOf(
                L.LEFT_SHOULDER to j(300f, 300f), L.RIGHT_SHOULDER to j(380f, 300f),
                L.LEFT_HIP to j(350f, 150f), L.RIGHT_HIP to j(430f, 150f),
                L.LEFT_ANKLE to j(350f, 300f), L.RIGHT_ANKLE to j(430f, 300f)
            ),
            0, 640, 480
        )
        val features = frame.features()!!
        assertEquals(Posture.BENT_OVER, features.posture)
    }

    @Test fun `upright pose stays standing`() {
        val frame = PoseFrame(
            mapOf(
                L.LEFT_SHOULDER to j(300f, 200f), L.RIGHT_SHOULDER to j(380f, 200f),
                L.LEFT_HIP to j(300f, 300f), L.RIGHT_HIP to j(380f, 300f),
                L.LEFT_ANKLE to j(300f, 400f), L.RIGHT_ANKLE to j(380f, 400f)
            ),
            0, 640, 480
        )
        assertEquals(Posture.STANDING, frame.features()!!.posture)
    }
}
