package com.example.fitpulseai.data

import com.example.fitpulseai.domain.ActivityBlock
import com.example.fitpulseai.domain.ExerciseType
import com.example.fitpulseai.domain.Posture
import com.example.fitpulseai.domain.TimelineBlock
import com.example.fitpulseai.domain.Totals
import com.example.fitpulseai.domain.WorkoutTelemetry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrainerReportTest {
    private val telemetry = WorkoutTelemetry(
        sessionId = "test",
        durationSeconds = 754,
        timeline = listOf(
            TimelineBlock("00:10", "00:40", ExerciseType.PUNCH_JAB_CROSS, 8, 3, 5),
            TimelineBlock("01:00", "01:20", ExerciseType.SQUAT, 12, 0, 0),
            TimelineBlock("02:00", "02:10", ExerciseType.PUSH_UP, 3, 0, 0)
        ),
        totals = Totals(23, emptyList()),
        activities = listOf(
            ActivityBlock("00:00", "01:30", Posture.STANDING),
            ActivityBlock("01:30", "12:34", Posture.FLOOR)
        )
    )

    @Test fun `report has the trainer sections without technical angle details`() {
        val report = telemetry.toTrainerReport()
        assertTrue(report.contains("FitPulse AI - Kratki izvještaj trenera"))
        assertTrue(report.contains("Cilj treninga"))
        assertTrue(report.contains("Što se dogodilo"))
        assertTrue(report.contains("Vježbe i ponavljanja"))
        assertTrue(report.contains("Mišićne skupine"))
        assertTrue(report.contains("Napomena"))
        assertTrue(report.contains("Zaključak"))
        assertTrue(report.contains("12:34"))
        assertTrue(report.contains("23"))
        assertTrue(report.contains("Boksački udarci"))
        assertTrue(report.contains("Čučnjevi"))
        assertTrue(report.contains("Sklekovi"))
        assertTrue(report.contains("Nema izražene asimetrije"))
        assertFalse(report.contains("deg"))
        assertFalse(report.contains("torso"))
        assertFalse(report.contains("elevation"))
    }

    @Test fun `report shows left right split for single side exercises only`() {
        val report = telemetry.toTrainerReport()
        assertTrue(report.contains("8 (3 L / 5 D)"))
        assertFalse(report.contains("12 (0 L / 0 D)"))
    }

    @Test fun `report is deterministic and handles an empty session`() {
        assertTrue(telemetry.toTrainerReport() == telemetry.toTrainerReport())
        val empty = WorkoutTelemetry("empty", 5, emptyList(), Totals(0, emptyList()))
        val report = empty.toTrainerReport()
        assertTrue(report.contains("Nema zabilježenih ponavljanja."))
        assertTrue(report.contains("Nema zabilježenih promjena držanja tijela."))
        assertTrue(report.contains("Nema dovoljno podataka"))
    }

    @Test fun `report generated from the mp4 telemetry file`() {
        val json = javaClass.getResourceAsStream("/session-telemetry.json")!!.readBytes().decodeToString()
        val mp4 = telemetryJson.decodeFromString<WorkoutTelemetry>(json)
        val report = mp4.toTrainerReport()

        assertTrue(report.contains("23:41"))
        assertTrue(report.contains("105"))
        assertTrue(report.contains("58 (26 L / 32 D)"))
        assertTrue(report.contains("20 (12 L / 8 D)"))
        assertTrue(report.contains("11"))
        assertTrue(report.contains("9"))
        assertTrue(report.contains("7"))
        assertTrue(report.contains("prsa, ramena, ruke i core"))
        assertTrue(report.contains("Nema izražene asimetrije"))

        val out = File(System.getProperty("trainerReportOut", "build/reports/trainer-report-from-app.txt"))
        out.parentFile?.mkdirs()
        out.writeText(report)
    }
}
