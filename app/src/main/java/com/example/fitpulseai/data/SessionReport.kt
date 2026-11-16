package com.example.fitpulseai.data

import com.example.fitpulseai.domain.ActivityBlock
import com.example.fitpulseai.domain.ExerciseTotal
import com.example.fitpulseai.domain.ExerciseType
import com.example.fitpulseai.domain.Posture
import com.example.fitpulseai.domain.TimelineBlock
import com.example.fitpulseai.domain.WorkoutTelemetry
import java.util.Locale

fun ExerciseType.label(): String = when (this) {
    ExerciseType.PUNCH_JAB_CROSS -> "Punch (jab/cross)"
    ExerciseType.HIGH_KNEE -> "High knee"
    ExerciseType.SIDE_KICK -> "Side kick"
    ExerciseType.SQUAT -> "Squat"
    ExerciseType.PUSH_UP -> "Push-up"
    ExerciseType.JUMPING_JACK -> "Jumping jack"
    ExerciseType.IDLE -> "Idle"
}

fun Posture.label(): String = when (this) {
    Posture.STANDING -> "Standing work"
    Posture.FLOOR -> "Floor work (plank / push-up position)"
    Posture.BENT_OVER -> "Bent over / transition"
    Posture.UNKNOWN -> "Unclassified"
}

internal fun clock(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

private fun number(value: Float?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "-"

/**
 * Per-exercise totals. Newer sessions store them directly; older stored sessions are derived from
 * the timeline, which is complete for those recordings.
 */
internal fun WorkoutTelemetry.exerciseTotals(): List<ExerciseTotal> {
    if (exercises.isNotEmpty()) return exercises
    return timeline.groupBy { it.exercise }.map { (exercise, blocks) ->
        ExerciseTotal(
            exercise, blocks.sumOf { it.reps }, blocks.sumOf { it.leftReps }, blocks.sumOf { it.rightReps },
            blocks.mapNotNull { it.average }.averageOrNull(),
            blocks.mapNotNull { it.leftAverage }.averageOrNull(),
            blocks.mapNotNull { it.rightAverage }.averageOrNull(),
            blocks.mapNotNull { it.elevationRatio }.averageOrNull()
        )
    }
}

private fun ExerciseTotal.detail(): String = when (exercise) {
    ExerciseType.SIDE_KICK -> "extension avg ${number(average)} deg" +
        " (left ${number(leftAverage)}, right ${number(rightAverage)})"
    ExerciseType.HIGH_KNEE -> "peak elevation avg ${number(elevationRatio)} torso lengths"
    ExerciseType.PUNCH_JAB_CROSS -> "peak elbow angle avg ${number(average)} deg"
    ExerciseType.PUSH_UP -> "elbow extension avg ${number(average)} deg"
    ExerciseType.SQUAT -> "knee bend avg ${number(average)} deg from straight"
    ExerciseType.JUMPING_JACK -> "ankle spread avg ${number(average)} torso widths"
    ExerciseType.IDLE -> "n/a"
}

private fun ExerciseTotal.sideSummary(): String = when (exercise) {
    ExerciseType.PUNCH_JAB_CROSS, ExerciseType.HIGH_KNEE, ExerciseType.SIDE_KICK ->
        "left ${leftReps.toString().padStart(3)} / right ${rightReps.toString().padStart(3)}"
    ExerciseType.SQUAT, ExerciseType.PUSH_UP, ExerciseType.JUMPING_JACK -> "both sides, counted per repetition"
    ExerciseType.IDLE -> ""
}

/**
 * Deterministic, offline summary of a finished session. Mirrors the counts shown on the
 * summary screen and gives the video regression test a human-readable artifact.
 */
fun WorkoutTelemetry.toTextReport(
    source: String? = null,
    sampledFps: Double? = null,
    diagnostics: List<String> = emptyList()
): String = buildString {
    appendLine("FitPulse AI - session summary")
    appendLine("=".repeat(40))
    if (source != null) appendLine("Source            : $source")
    if (sampledFps != null) appendLine("Sampling          : ${String.format(Locale.US, "%.1f", sampledFps)} fps")
    appendLine("Duration          : ${clock(durationSeconds)}")
    appendLine("Total detections  : ${totals.totalReps}")
    appendLine()

    appendLine("Exercise breakdown")
    appendLine("-".repeat(40))
    val breakdown = exerciseTotals()
    if (breakdown.isEmpty()) {
        appendLine("No exercise movement detected.")
    } else {
        breakdown.forEach { total ->
            appendLine(
                "${total.exercise.label().padEnd(18)} ${total.reps.toString().padStart(4)}   ${total.sideSummary()}"
            )
            appendLine("   ${total.detail()}")
        }
    }
    appendLine()

    appendLine("What the camera saw")
    appendLine("-".repeat(40))
    if (activities.isEmpty()) appendLine("No posture changes recorded.") else activities.forEach { block: ActivityBlock ->
        appendLine("${block.startTime}-${block.endTime}  ${block.posture.label()}")
    }
    appendLine()

    appendLine("Recorded blocks")
    appendLine("-".repeat(40))
    if (timeline.isEmpty()) {
        appendLine("None.")
    } else {
        timeline.take(MAX_BLOCK_LINES).forEach { block: TimelineBlock ->
            val sides = when (block.exercise) {
                ExerciseType.SQUAT, ExerciseType.PUSH_UP -> "both"
                else -> "L${block.leftReps}/R${block.rightReps}"
            }
            appendLine(
                "${block.startTime}-${block.endTime}  ${block.exercise.label().padEnd(18)} " +
                    "${block.reps} ($sides)"
            )
        }
        if (timeline.size > MAX_BLOCK_LINES) appendLine("... ${timeline.size - MAX_BLOCK_LINES} more blocks")
    }
    appendLine()

    appendLine("Asymmetries")
    appendLine("-".repeat(40))
    if (totals.asymmetries.isEmpty()) appendLine("None flagged (needs 10+ degrees of side kick difference).")
    else totals.asymmetries.forEach { appendLine("- $it") }
    appendLine()

    if (diagnostics.isNotEmpty()) {
        appendLine("Diagnostics")
        appendLine("-".repeat(40))
        diagnostics.forEach { appendLine(it) }
        appendLine()
    }

    appendLine("Notes")
    appendLine("-".repeat(40))
    appendLine("Counts are heuristic motion detections from an in-memory pose model, not a validated")
    appendLine("fitness measurement. In a group video the app follows one person at a time.")
    appendLine("No video or images leave the device; nothing here is advice.")
}

private fun List<Float>.averageOrNull(): Float? = takeIf { it.isNotEmpty() }?.average()?.toFloat()

private const val MAX_BLOCK_LINES = 30
