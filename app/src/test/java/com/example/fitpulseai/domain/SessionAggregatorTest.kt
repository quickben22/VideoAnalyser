package com.example.fitpulseai.domain

import com.example.fitpulseai.data.telemetryJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SessionAggregatorTest {
    @Test fun `counts sides and reports asymmetry`() {
        val session = SessionAggregator(1000)
        session.add(Rep(ExerciseType.SIDE_KICK, Side.LEFT, 155f, 2000))
        session.add(Rep(ExerciseType.SIDE_KICK, Side.RIGHT, 170f, 3000))
        val result = session.finish(5000)
        assertEquals(2, result.totals.totalReps)
        assertEquals(1, result.timeline.single().leftReps)
        assertEquals(1, result.timeline.single().rightReps)
        assertTrue(result.totals.asymmetries.single().contains("15.0"))
        assertEquals(4L, result.durationSeconds)
    }

    @Test fun `alternating long sessions retain totals within payload budget`() {
        val session = SessionAggregator(0)
        repeat(150) { index ->
            session.add(Rep(if (index % 2 == 0) ExerciseType.HIGH_KNEE else ExerciseType.PUNCH_JAB_CROSS, Side.LEFT, 160f, index * 1000L))
        }
        val result = session.finish(150000)
        assertEquals(150, result.timeline.sumOf { it.reps })
        assertTrue(telemetryJson.encodeToString(result).toByteArray(Charsets.UTF_8).size < 3000)
    }
}
