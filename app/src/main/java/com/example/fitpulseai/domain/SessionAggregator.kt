package com.example.fitpulseai.domain

import java.util.Locale
import java.util.UUID

/**
 * Groups detected reps into contiguous blocks and produces the finished session payload.
 *
 * Blocks are compacted for storage so a long session cannot grow the payload without limit.
 * Compaction never invents a time span: it keeps whole blocks, so a block's start and end are
 * always the first and last rep that actually happened in it. Per-exercise totals are kept
 * separately in [WorkoutTelemetry.exercises] and stay complete even when blocks are dropped.
 */
class SessionAggregator(private val startedMs: Long) {
    private data class Block(val exercise: ExerciseType, val start: Long, var end: Long, val reps: MutableList<Rep>)
    private val blocks = mutableListOf<Block>()
    private val activity = ActivityTracker(startedMs)
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

    fun observePosture(timeMs: Long, posture: Posture) = activity.observe(timeMs, posture)

    fun finish(endMs: Long): WorkoutTelemetry {
        val compact = if (blocks.size <= MAX_BLOCKS) blocks
        else blocks.take(MAX_BLOCKS / 2) + blocks.takeLast(MAX_BLOCKS / 2)

        fun average(reps: List<Rep>): Float? =
            reps.takeIf { it.isNotEmpty() }?.map { it.metric }?.average()?.toFloat()
                ?.let { kotlin.math.round(it * 10) / 10 }

        val timeline = compact.map { block ->
            val left = block.reps.filter { it.side == Side.LEFT }
            val right = block.reps.filter { it.side == Side.RIGHT }
            TimelineBlock(
                format(block.start), format(block.end), block.exercise, block.reps.size, left.size, right.size,
                if (block.exercise != ExerciseType.HIGH_KNEE) average(block.reps) else null,
                if (block.exercise == ExerciseType.SIDE_KICK) average(left) else null,
                if (block.exercise == ExerciseType.SIDE_KICK) average(right) else null,
                if (block.exercise == ExerciseType.HIGH_KNEE) average(block.reps) else null
            )
        }

        val exercises = blocks.groupBy { it.exercise }.map { (exercise, group) ->
            val reps = group.flatMap { it.reps }
            val left = reps.filter { it.side == Side.LEFT }
            val right = reps.filter { it.side == Side.RIGHT }
            ExerciseTotal(
                exercise, reps.size, left.size, right.size,
                if (exercise != ExerciseType.HIGH_KNEE) average(reps) else null,
                if (exercise == ExerciseType.SIDE_KICK) average(left) else null,
                if (exercise == ExerciseType.SIDE_KICK) average(right) else null,
                if (exercise == ExerciseType.HIGH_KNEE) average(reps) else null
            )
        }

        return WorkoutTelemetry(
            sessionId,
            ((endMs - startedMs) / 1000).coerceAtLeast(0),
            timeline,
            Totals(count, asymmetries()),
            exercises,
            activity.finish(endMs)
        )
    }

    private fun asymmetries(): List<String> {
        fun average(reps: List<Rep>): Float? = reps.takeIf { it.isNotEmpty() }?.map { it.metric }?.average()?.toFloat()
        val kicks = blocks.filter { it.exercise == ExerciseType.SIDE_KICK }.flatMap { it.reps }
        val left = average(kicks.filter { it.side == Side.LEFT }) ?: return emptyList()
        val right = average(kicks.filter { it.side == Side.RIGHT }) ?: return emptyList()
        return if (kotlin.math.abs(left - right) >= 10f) listOf(
            "${if (left < right) "Left" else "Right"} side kick extension averaged " +
                "${String.format(Locale.US, "%.1f", kotlin.math.abs(left - right))} degrees lower"
        ) else emptyList()
    }

    private companion object {
        const val MAX_BLOCKS = 40
    }
}

/**
 * Collapses the per-frame posture stream into a few long segments so the summary can say what the
 * person was doing even in stretches where no repetition was counted.
 */
private class ActivityTracker(private val startedMs: Long) {
    private class Segment(val posture: Posture, val start: Long, var end: Long)
    private val segments = mutableListOf<Segment>()

    fun observe(timeMs: Long, posture: Posture) {
        if (posture == Posture.UNKNOWN) return
        val last = segments.lastOrNull()
        if (last != null && last.posture == posture && timeMs - last.end <= GAP_MS) last.end = timeMs
        else segments.add(Segment(posture, timeMs, timeMs))
    }

    fun finish(endMs: Long): List<ActivityBlock> {
        segments.lastOrNull()?.let { if (endMs - it.end <= GAP_MS) it.end = endMs }
        val long = segments.filter { it.end - it.start >= MIN_DURATION_MS }
        val relevant = if (long.isEmpty() && segments.isNotEmpty()) {
            listOf(segments.maxBy { it.end - it.start })
        } else long
        return relevant.map { ActivityBlock(format((it.start - startedMs) / 1000), format((it.end - startedMs) / 1000), it.posture) }
    }

    private companion object {
        const val GAP_MS = 2_000L
        const val MIN_DURATION_MS = 4_000L
    }
}

private fun format(seconds: Long): String =
    String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
