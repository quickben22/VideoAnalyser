package com.example.fitpulseai.data

import androidx.work.WorkInfo
import com.example.fitpulseai.domain.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FactualSummaryTest {
    @Test fun `parse structured summary and reject legacy coaching data`() {
        val text = """{"headline":"Workout","overview":"Two detected reps","chronological_entries":[{"timestamp":"00:01","title":"First","description":"A detection"}]}"""
        assertEquals("Workout", parseSummary(text).headline)
        assertThrows(InvalidSummaryException::class.java) {
            parseSummary("""{"headline":"Old","chronological_summary":[],"form_insights":[],"next_session_tips":[]}""")
        }
        assertThrows(InvalidSummaryException::class.java) {
            parseSummary("""{"headline":"","overview":"Text","chronological_entries":[]}""")
        }
        assertNotEquals("report_123", factualSummaryKey("123"))
        assertNotEquals("coach_123", factualWorkName("123"))
    }

    @Test fun `empty session payload has no exercises`() {
        val telemetry = WorkoutTelemetry("id", 30, emptyList(), Totals(0, emptyList()))
        val payload = factualPayload(telemetry)
        assertTrue(payload.exercises.isEmpty())
        assertEquals(0, payload.totalReps)
        assertTrue(parseSummary("""{"headline":"No detections","overview":"No reps detected.","chronological_entries":[]}""").chronologicalEntries.isEmpty())
    }

    @Test fun `compacted blocks become aggregates without form interpretations`() {
        val blocks = listOf(
            TimelineBlock("00:02", "00:03", ExerciseType.SIDE_KICK, 2, 1, 1, 160f),
            TimelineBlock("00:09", "01:25", ExerciseType.SIDE_KICK, 4, 3, 1, 170f)
        )
        val payload = factualPayload(WorkoutTelemetry("id", 90, blocks, Totals(6, listOf("Left weaker"))))
        val aggregate = payload.exercises.single()
        assertEquals(6, aggregate.detectedReps)
        assertEquals(4, aggregate.leftReps)
        assertEquals("00:02", aggregate.firstDetected)
        assertEquals("01:25", aggregate.lastDetected)
        val json = telemetryJson.encodeToString(payload)
        assertFalse(json.contains("weaker"))
        assertFalse(json.contains("angle"))
    }

    @Test fun `failure classification retries only transient errors and is bounded`() {
        assertTrue(classifySummaryFailure(IOException("offline"), 0).retry)
        assertFalse(classifySummaryFailure(IOException("offline"), 5).retry)
        assertFalse(classifySummaryFailure(InvalidSummaryException("bad"), 0).retry)
        assertFalse(classifySummaryFailure(IllegalStateException("configuration"), 0).retry)
    }

    @Test fun `status represents pending running failure and completion`() {
        assertEquals(SummaryPhase.PENDING, summaryStatus(true, false, WorkInfo.State.ENQUEUED, null).phase)
        assertEquals(SummaryPhase.GENERATING, summaryStatus(true, false, WorkInfo.State.RUNNING, null).phase)
        assertEquals("bad config", summaryStatus(true, false, WorkInfo.State.FAILED, "bad config").message)
        assertEquals(SummaryPhase.FAILED, summaryStatus(false, false, null, null).phase)
        assertEquals(SummaryPhase.COMPLETED, summaryStatus(true, true, WorkInfo.State.SUCCEEDED, null).phase)
    }
}
