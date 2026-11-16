package com.example.fitpulseai.vision

import com.example.fitpulseai.domain.Joint
import com.example.fitpulseai.domain.PoseFrame
import com.google.mlkit.vision.pose.PoseLandmark as L
import org.junit.Assert.*
import org.junit.Test

class PoseGateTest {

    /** Skeleton whose shoulder midline sits at (cx, cy) and whose torso is [torso] px long. */
    private fun frame(cx: Float, cy: Float, torso: Float, time: Long) = PoseFrame(
        mapOf(
            L.LEFT_SHOULDER to Joint(cx - 20f, cy, 0f, 1f),
            L.RIGHT_SHOULDER to Joint(cx + 20f, cy, 0f, 1f),
            L.LEFT_HIP to Joint(cx - 20f, cy + torso, 0f, 1f),
            L.RIGHT_HIP to Joint(cx + 20f, cy + torso, 0f, 1f)
        ),
        time,
        640,
        480
    )

    @Test fun `accepts a plausible standing subject`() {
        val gate = PoseGate()
        assertTrue(gate.accept(frame(320f, 100f, 120f, 0)))
        assertEquals(1, gate.accepted)
        assertEquals(0, gate.rejected)
    }

    @Test fun `rejects a collapsed skeleton whose torso is a few pixels`() {
        val gate = PoseGate()
        assertFalse(gate.accept(frame(320f, 100f, 3f, 0)))
        assertEquals(1, gate.rejectedByScale)
    }

    @Test fun `accepts slow movement of the same subject`() {
        val gate = PoseGate()
        assertTrue(gate.accept(frame(320f, 100f, 120f, 0)))
        // Four steps of 40 px: each is well inside one torso length.
        (1..4).forEach { step ->
            assertTrue(gate.accept(frame(320f + step * 40f, 100f, 120f, step * 200L)))
        }
        assertEquals(5, gate.accepted)
    }

    @Test fun `rejects a subject that teleports across the frame`() {
        val gate = PoseGate()
        assertTrue(gate.accept(frame(150f, 100f, 120f, 0)))
        assertFalse(gate.accept(frame(600f, 100f, 120f, 200)))
        assertEquals(1, gate.rejectedByJump)
    }

    @Test fun `rejects a differently scaled second subject`() {
        val gate = PoseGate()
        assertTrue(gate.accept(frame(320f, 100f, 120f, 0)))
        // Same place, but the torso is half the locked size: a different, more distant person.
        assertFalse(gate.accept(frame(320f, 120f, 60f, 200)))
        assertEquals(1, gate.rejectedByScale)
    }

    @Test fun `reacquires instead of dropping the rest of the session`() {
        val gate = PoseGate(reacquireAfter = 3)
        assertTrue(gate.accept(frame(150f, 100f, 120f, 0)))
        repeat(2) { assertFalse(gate.accept(frame(600f, 100f, 120f, 200L * (it + 1)))) }
        assertTrue(gate.accept(frame(600f, 100f, 120f, 800)))
        assertEquals(1, gate.subjectSwitches)
        assertTrue(gate.accept(frame(600f, 100f, 120f, 1000)))
    }

    @Test fun `reset clears the lock and the counters`() {
        val gate = PoseGate()
        gate.accept(frame(320f, 100f, 120f, 0))
        gate.reset()
        assertEquals(0, gate.accepted)
        assertEquals(0, gate.subjectSwitches)
        // After a reset a distant subject is allowed to lock again.
        assertTrue(gate.accept(frame(100f, 100f, 60f, 5_000)))
    }
}
