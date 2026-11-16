package com.example.fitpulseai.data

import com.example.fitpulseai.domain.ExerciseType
import com.example.fitpulseai.domain.TimelineBlock
import com.example.fitpulseai.domain.Totals
import com.example.fitpulseai.domain.WorkoutTelemetry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SessionReportTest {
    private val telemetry = WorkoutTelemetry(
        sessionId = "test",
        durationSeconds = 754,
        timeline = listOf(
            TimelineBlock("00:10", "00:40", ExerciseType.HIGH_KNEE, 12, 6, 6, elevationRatio = 0.42f),
            TimelineBlock("01:00", "01:20", ExerciseType.PUNCH_JAB_CROSS, 8, 4, 4, average = 150.2f),
            TimelineBlock("02:00", "02:10", ExerciseType.SIDE_KICK, 3, 1, 2, average = 160f, leftAverage = 150f, rightAverage = 170f)
        ),
        totals = Totals(23, emptyList())
    )

    @Test fun `report shows duration totals and per exercise breakdown`() {
        val report = telemetry.toTextReport(source = "clip.mp4", sampledFps = 12.5)
        assertTrue(report.contains("Source            : clip.mp4"))
        assertTrue(report.contains("Sampling          : 12.5 fps"))
        assertTrue(report.contains("Duration          : 12:34"))
        assertTrue(report.contains("Total detections  : 23"))
        assertTrue(report.contains("High knee"))
        assertTrue(report.contains("left   6 / right   6"))
        assertTrue(report.contains("left   4 / right   4"))
        assertTrue(report.contains("left   1 / right   2"))
        assertTrue(report.contains("00:10-00:40"))
        assertTrue(report.contains("heuristic motion detections"))
    }

    @Test fun `report includes flagged asymmetries`() {
        val flagged = telemetry.copy(totals = Totals(23, listOf("Left side kick extension averaged 12.4 degrees lower")))
        assertTrue(flagged.toTextReport().contains("Left side kick extension averaged 12.4 degrees lower"))
    }

    @Test fun `report handles a session with no detections`() {
        val empty = WorkoutTelemetry("empty", 5, emptyList(), Totals(0, emptyList()))
        val report = empty.toTextReport()
        assertTrue(report.contains("No exercise movement detected."))
        assertTrue(report.contains("None flagged"))
    }

    @Test fun `average formatting is locale independent`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertTrue(telemetry.toTextReport().contains("150.2"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
