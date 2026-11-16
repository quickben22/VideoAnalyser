package com.example.fitpulseai.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ExerciseType { PUNCH_JAB_CROSS, HIGH_KNEE, SIDE_KICK, SQUAT, PUSH_UP, JUMPING_JACK, IDLE }
enum class Side { LEFT, RIGHT, BOTH }

/** Broad body orientation, used to keep detectors from firing outside their posture. */
enum class Posture { STANDING, FLOOR, BENT_OVER, UNKNOWN }

data class Joint(val x: Float, val y: Float, val z: Float, val confidence: Float)
data class PoseFrame(val joints: Map<Int, Joint>, val timestampMs: Long, val width: Int, val height: Int)
data class Rep(val exercise: ExerciseType, val side: Side, val metric: Float, val timestampMs: Long)

@Serializable
data class TimelineBlock(
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("primary_exercise") val exercise: ExerciseType,
    val reps: Int,
    @SerialName("left_reps") val leftReps: Int,
    @SerialName("right_reps") val rightReps: Int,
    @SerialName("avg_peak_angle_deg") val average: Float? = null,
    @SerialName("left_avg_angle_deg") val leftAverage: Float? = null,
    @SerialName("right_avg_angle_deg") val rightAverage: Float? = null,
    @SerialName("avg_peak_elevation_ratio") val elevationRatio: Float? = null
)

/** Total for one exercise across the whole session, independent of how the timeline was compacted. */
@Serializable
data class ExerciseTotal(
    val exercise: ExerciseType,
    val reps: Int,
    @SerialName("left_reps") val leftReps: Int,
    @SerialName("right_reps") val rightReps: Int,
    @SerialName("avg_peak_angle_deg") val average: Float? = null,
    @SerialName("left_avg_angle_deg") val leftAverage: Float? = null,
    @SerialName("right_avg_angle_deg") val rightAverage: Float? = null,
    @SerialName("avg_peak_elevation_ratio") val elevationRatio: Float? = null
)

/** A stretch of the session in one body orientation, whether or not reps were counted. */
@Serializable
data class ActivityBlock(
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val posture: Posture
)

@Serializable
data class Totals(
    @SerialName("total_reps") val totalReps: Int,
    @SerialName("detected_asymmetries") val asymmetries: List<String>
)

@Serializable
data class WorkoutTelemetry(
    @SerialName("session_id") val sessionId: String,
    @SerialName("duration_seconds") val durationSeconds: Long,
    @SerialName("timeline_blocks") val timeline: List<TimelineBlock>,
    val totals: Totals,
    /** Per-exercise totals for the whole session; unaffected by timeline compaction. */
    val exercises: List<ExerciseTotal> = emptyList(),
    /** What the body was doing over time, even where no reps were counted. */
    val activities: List<ActivityBlock> = emptyList()
)

@Serializable
data class SummaryEvent(val timestamp: String, val title: String, val description: String)
@Serializable
data class FactualSummary(
    val headline: String,
    val overview: String,
    @SerialName("chronological_entries") val chronologicalEntries: List<SummaryEvent>
)
