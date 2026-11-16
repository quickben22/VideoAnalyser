package com.example.fitpulseai.data

import com.example.fitpulseai.domain.ExerciseTotal
import com.example.fitpulseai.domain.ExerciseType
import com.example.fitpulseai.domain.Posture
import com.example.fitpulseai.domain.WorkoutTelemetry

/**
 * Deterministic, offline trainer-style report in Croatian.
 *
 * It is built only from the data the app already recorded in [WorkoutTelemetry]: rep totals,
 * per-exercise counts, the posture timeline and any flagged asymmetries. No AI model, network
 * connection or saved video is used, and the same telemetry always produces the same text.
 */
fun WorkoutTelemetry.toTrainerReport(): String {
    val exercises = exerciseTotals()
    return buildString {
        appendLine("FitPulse AI - Kratki izvještaj trenera")
        appendLine("=".repeat(40))
        appendLine("Trajanje: ${clock(durationSeconds)}   |   Ukupno ponavljanja: ${totals.totalReps}")
        appendLine()

        appendLine("Cilj treninga")
        appendLine("-".repeat(40))
        appendLine(goal(exercises))
        appendLine()

        appendLine("Što se dogodilo")
        appendLine("-".repeat(40))
        val phases = compactPhases()
        if (phases.isEmpty()) {
            appendLine("- Nema zabilježenih promjena držanja tijela.")
        } else {
            phases.forEach { phase ->
                appendLine("- ${phase.startTime}-${phase.endTime}  ${phase.posture.trainerLabel()}")
            }
        }
        appendLine()

        appendLine("Vježbe i ponavljanja")
        appendLine("-".repeat(40))
        if (exercises.isEmpty()) {
            appendLine("Nema zabilježenih ponavljanja.")
        } else {
            exercises.sortedByDescending { it.reps }.forEach { total ->
                val sides = when (total.exercise) {
                    ExerciseType.PUNCH_JAB_CROSS, ExerciseType.HIGH_KNEE, ExerciseType.SIDE_KICK ->
                        " (${total.leftReps} L / ${total.rightReps} D)"
                    else -> ""
                }
                appendLine("  ${total.exercise.trainerLabel().padEnd(18)} ${total.reps.toString().padStart(3)}$sides")
            }
        }
        appendLine()

        appendLine("Mišićne skupine")
        appendLine("-".repeat(40))
        val muscles = exercises.mapNotNull { muscleGroups[it.exercise] }.distinct()
        if (muscles.isEmpty()) {
            appendLine("Nema dovoljno podataka za procjenu mišićnih skupina.")
        } else {
            muscles.forEach { appendLine("- $it") }
        }
        appendLine()

        appendLine("Napomena")
        appendLine("-".repeat(40))
        if (totals.asymmetries.isEmpty()) {
            appendLine("Nema izražene asimetrije između lijeve i desne strane.")
        } else {
            totals.asymmetries.forEach { appendLine("- $it") }
        }
        appendLine("Brojevi su približne procjene automatske detekcije pokreta, ne validirano mjerenje.")
        appendLine()

        append("Zaključak: ${conclusion(exercises)}")
    }
}

/**
 * The raw posture stream is noisy (camera cuts and short transitions). Collapse it into a few
 * readable phases so the report stays short: merge neighbouring blocks of the same posture and
 * keep only the longest phases.
 */
private fun WorkoutTelemetry.compactPhases(): List<com.example.fitpulseai.domain.ActivityBlock> {
    val merged = mutableListOf<Triple<Posture, Long, Long>>()
    activities.filter { it.posture != Posture.UNKNOWN }.forEach { block ->
        val start = seconds(block.startTime)
        val end = seconds(block.endTime)
        val last = merged.lastOrNull()
        if (last != null && last.first == block.posture && start - last.third <= MERGE_GAP_SECONDS) {
            merged[merged.lastIndex] = Triple(block.posture, last.second, maxOf(last.third, end))
        } else {
            merged.add(Triple(block.posture, start, end))
        }
    }
    val long = merged.filter { it.third - it.second >= MIN_PHASE_SECONDS }
    val chosen = (if (long.isNotEmpty()) long else merged.sortedByDescending { it.third - it.second }.take(3))
        .sortedByDescending { it.third - it.second }
        .take(MAX_PHASES)
        .sortedBy { it.second }
    return chosen.map { (posture, start, end) ->
        com.example.fitpulseai.domain.ActivityBlock(clock(start), clock(end), posture)
    }
}

private fun seconds(time: String): Long {
    val parts = time.split(":").map { it.toLongOrNull() ?: 0L }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        else -> 0L
    }
}

private const val MERGE_GAP_SECONDS = 60L
private const val MIN_PHASE_SECONDS = 60L
private const val MAX_PHASES = 6

private fun goal(exercises: List<ExerciseTotal>): String {
    val cardio = exercises.any { it.exercise in CARDIO_EXERCISES }
    val strength = exercises.any { it.exercise in STRENGTH_EXERCISES }
    return when {
        cardio && strength -> "Kružni trening koji kombinira kardio kondiciju i snagu kroz više mišićnih skupina."
        cardio -> "Kardio trening s naglaskom na kondiciju i ritam pokreta."
        strength -> "Trening snage s vlastitom težinom i radom na glavnim mišićnim skupinama."
        else -> "Trening za cijelo tijelo. Cilj je bio kroz krug vježbi aktivirati glavne mišićne skupine."
    }
}

private fun conclusion(exercises: List<ExerciseTotal>): String {
    if (exercises.isEmpty()) return "Nema zabilježenih vježbi, pa nema ni zaključka o opterećenju."
    val count = exercises.size
    val noun = when {
        count % 10 == 1 && count % 100 != 11 -> "vježba"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "vježbe"
        else -> "vježbi"
    }
    return "Obuhvaćeno je $count $noun. Opterećenje je raspoređeno kroz kardio i snagu, " +
        "bez prevelikog naglaska na jednu mišićnu skupinu."
}

private fun ExerciseType.trainerLabel(): String = when (this) {
    ExerciseType.PUNCH_JAB_CROSS -> "Boksački udarci"
    ExerciseType.HIGH_KNEE -> "Visoka koljena"
    ExerciseType.SIDE_KICK -> "Bočni udarci"
    ExerciseType.SQUAT -> "Čučnjevi"
    ExerciseType.PUSH_UP -> "Sklekovi"
    ExerciseType.JUMPING_JACK -> "Skokovi"
    ExerciseType.IDLE -> "Mirnoća"
}

private fun Posture.trainerLabel(): String = when (this) {
    Posture.STANDING -> "Rad u stojećem položaju"
    Posture.FLOOR -> "Rad na podu"
    Posture.BENT_OVER -> "Savijeni položaj / prijelaz"
    Posture.UNKNOWN -> "Nepoznato"
}

private val CARDIO_EXERCISES = setOf(
    ExerciseType.PUNCH_JAB_CROSS, ExerciseType.HIGH_KNEE,
    ExerciseType.JUMPING_JACK, ExerciseType.SIDE_KICK
)
private val STRENGTH_EXERCISES = setOf(ExerciseType.SQUAT, ExerciseType.PUSH_UP)

private val muscleGroups = mapOf<ExerciseType, String?>(
    ExerciseType.PUNCH_JAB_CROSS to "Boksački udarci: prsa, ramena, ruke i core.",
    ExerciseType.HIGH_KNEE to "Visoka koljena: noge, kukovi i core; snažan kardio.",
    ExerciseType.JUMPING_JACK to "Skokovi: cijelo tijelo i kardio.",
    ExerciseType.SQUAT to "Čučnjevi: noge i gluteusi.",
    ExerciseType.PUSH_UP to "Sklekovi: prsa, ruke i core.",
    ExerciseType.SIDE_KICK to "Bočni udarci: noge i kukovi.",
    ExerciseType.IDLE to null
)
