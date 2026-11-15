package com.example.fitpulseai.domain

import java.util.UUID
import java.util.Locale

class SessionAggregator(private val startedMs: Long) {
    private data class Block(val exercise: ExerciseType, val start: Long, var end: Long, val reps: MutableList<Rep>)
    private val blocks = mutableListOf<Block>()
    private val sessionId = UUID.randomUUID().toString()
    val count: Int get() = blocks.sumOf { it.reps.size }

    fun add(rep: Rep) {
        val elapsed = ((rep.timestampMs - startedMs) / 1000).coerceAtLeast(0)
        val last = blocks.lastOrNull()
        if (last != null && last.exercise == rep.exercise && elapsed - last.end <= 10) {
            last.end = elapsed
            last.reps.add(rep)
        } else blocks.add(Block(rep.exercise, elapsed, elapsed, mutableListOf(rep)))
    }

    fun finish(endMs: Long): WorkoutTelemetry {
        // Keep the cloud payload bounded while preserving totals for long, alternating workouts.
        val compact = if (blocks.size <= 12) blocks else {
            val head = blocks.take(9)
            val tail = blocks.drop(9).groupBy { it.exercise }.map { (exercise, group) ->
                Block(exercise, group.first().start, group.last().end, group.flatMap { it.reps }.toMutableList())
            }
            head + tail
        }
        fun average(reps: List<Rep>): Float? = reps.takeIf { it.isNotEmpty() }?.map { it.metric }?.average()?.toFloat()?.let { kotlin.math.round(it * 10) / 10 }
        fun format(seconds: Long) = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
        val timeline = compact.map { block ->
            val left = block.reps.filter { it.side == Side.LEFT }
            val right = block.reps.filter { it.side == Side.RIGHT }
            val angle = block.exercise != ExerciseType.HIGH_KNEE
            TimelineBlock(format(block.start), format(block.end), block.exercise, block.reps.size, left.size, right.size,
                if (angle) average(block.reps) else null,
                if (block.exercise == ExerciseType.SIDE_KICK) average(left) else null,
                if (block.exercise == ExerciseType.SIDE_KICK) average(right) else null,
                if (!angle) average(block.reps) else null)
        }
        val kicks = blocks.filter { it.exercise == ExerciseType.SIDE_KICK }.flatMap { it.reps }
        val left = average(kicks.filter { it.side == Side.LEFT })
        val right = average(kicks.filter { it.side == Side.RIGHT })
        val asymmetries = if (left != null && right != null && kotlin.math.abs(left - right) >= 10f)
            listOf("${if (left < right) "Left" else "Right"} side kick extension averaged ${String.format(Locale.US, "%.1f", kotlin.math.abs(left - right))} degrees lower") else emptyList()
        return WorkoutTelemetry(sessionId, ((endMs - startedMs) / 1000).coerceAtLeast(0), timeline, Totals(count, asymmetries))
    }
}
