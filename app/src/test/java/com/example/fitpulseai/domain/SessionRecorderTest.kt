package com.example.fitpulseai.domain

import com.google.mlkit.vision.pose.PoseLandmark as L
import org.junit.Assert.*
import org.junit.Test

class SessionRecorderTest {
    private fun frame(time: Long, kneeY: Float = 300f, ankles: Boolean = true): PoseFrame {
        fun j(x: Float, y: Float) = Joint(x, y, 0f, 1f)
        val joints = mutableMapOf(
            L.LEFT_SHOULDER to j(100f, 100f), L.RIGHT_SHOULDER to j(200f, 100f),
            L.LEFT_HIP to j(100f, 200f), L.RIGHT_HIP to j(200f, 200f),
            L.LEFT_KNEE to j(100f, kneeY), L.RIGHT_KNEE to j(200f, 300f)
        )
        if (ankles) {
            joints[L.LEFT_ANKLE] = j(100f, 400f)
            joints[L.RIGHT_ANKLE] = j(200f, 400f)
        }
        return PoseFrame(joints, time, 640, 480)
    }

    @Test fun `recording starts only once shoulders and ankles are visible`() {
        val recorder = SessionRecorder()
        val partial = recorder.onFrame(frame(0, ankles = false))
        assertFalse(partial.calibrated)
        assertFalse(partial.started)
        assertNull(recorder.finish(1000))

        val complete = recorder.onFrame(frame(100))
        assertTrue(complete.calibrated)
        assertTrue(complete.started)
        assertEquals(100L, recorder.startedAtMs)
    }

    @Test fun `records reps and reports session duration from the first calibrated frame`() {
        val recorder = SessionRecorder()
        recorder.onFrame(frame(1_000))
        // The landmark smoother converges over several frames, as it does at camera frame rates.
        (1_200L..1_600L step 100L).forEach { recorder.onFrame(frame(it, kneeY = 180f)) }
        val rep = recorder.onFrame(frame(1_800))
        assertEquals(1, rep.reps)

        val telemetry = recorder.finish(4_000)!!
        assertEquals(3, telemetry.durationSeconds)
        assertEquals(1, telemetry.totals.totalReps)
        assertEquals(1, telemetry.timeline.single().reps)
    }

    @Test fun `reset clears the running session`() {
        val recorder = SessionRecorder()
        recorder.onFrame(frame(0))
        recorder.reset()
        assertNull(recorder.startedAtMs)
        assertFalse(recorder.isRecording)
        assertNull(recorder.finish(5000))
    }

    @Test fun `a single rejected frame does not cancel an in-flight repetition`() {
        fun j(x: Float, y: Float) = Joint(x, y, 0f, 1f)
        val distant = PoseFrame(
            mapOf(
                L.LEFT_SHOULDER to j(100f, 200f), L.RIGHT_SHOULDER to j(200f, 200f),
                L.LEFT_HIP to j(100f, 230f), L.RIGHT_HIP to j(200f, 230f),
                L.LEFT_KNEE to j(100f, 260f), L.RIGHT_KNEE to j(200f, 260f),
                L.LEFT_ANKLE to j(100f, 290f), L.RIGHT_ANKLE to j(200f, 290f)
            ),
            300, 640, 480
        )
        val recorder = SessionRecorder()
        recorder.onFrame(frame(1_000))
        (1_200L..1_600L step 100L).forEach { recorder.onFrame(frame(it, kneeY = 180f)) }
        // One bad frame in the middle of the lift must be ignored, not treated as a new subject.
        recorder.onFrame(distant.copy(timestampMs = 1_650))
        recorder.onFrame(frame(1_700, kneeY = 180f))
        val rep = recorder.onFrame(frame(1_800))
        assertEquals(1, rep.reps)
    }

    @Test fun `frames from a far smaller subject are rejected instead of analysed`() {        fun j(x: Float, y: Float) = Joint(x, y, 0f, 1f)
        // A distant person: same screen area, but only 30 px of torso against the locked 100 px.
        val small = PoseFrame(
            mapOf(
                L.LEFT_SHOULDER to j(100f, 200f), L.RIGHT_SHOULDER to j(200f, 200f),
                L.LEFT_HIP to j(100f, 230f), L.RIGHT_HIP to j(200f, 230f),
                L.LEFT_KNEE to j(100f, 260f), L.RIGHT_KNEE to j(200f, 260f),
                L.LEFT_ANKLE to j(100f, 290f), L.RIGHT_ANKLE to j(200f, 290f)
            ),
            300, 640, 480
        )
        val recorder = SessionRecorder()
        recorder.onFrame(frame(0))
        val outcome = recorder.onFrame(small)
        assertFalse(outcome.calibrated)
        assertTrue(recorder.rejectedFrames >= 1)
        assertEquals(0, recorder.count)
    }
}
