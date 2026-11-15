package com.example.fitpulseai.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.fitpulseai.domain.ExerciseType
import com.example.fitpulseai.domain.FactualSummary
import com.example.fitpulseai.domain.WorkoutTelemetry
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException as JsonException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.sessionStore by preferencesDataStore(name = "workout_sessions")
val telemetryJson = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }
internal fun factualSummaryKey(id: String) = "factual_summary_v1_$id"
internal fun factualWorkName(id: String) = "factual_summary_v1_$id"
private const val ERROR_KEY = "error"

class LocalSessionStorage(private val context: Context) {
    private fun telemetryKey(id: String) = stringPreferencesKey("telemetry_$id")
    suspend fun save(telemetry: WorkoutTelemetry) {
        context.sessionStore.edit {
            it[telemetryKey(telemetry.sessionId)] = telemetryJson.encodeToString(telemetry)
            it[stringPreferencesKey("latest_session")] = telemetry.sessionId
        }
    }
    val latestSession: Flow<String?> = context.sessionStore.data.map { it[stringPreferencesKey("latest_session")] }
    suspend fun saveSummary(id: String, summary: FactualSummary) {
        context.sessionStore.edit { it[stringPreferencesKey(factualSummaryKey(id))] = telemetryJson.encodeToString(summary) }
    }
    fun summary(id: String): Flow<FactualSummary?> = context.sessionStore.data.map { preferences ->
        preferences[stringPreferencesKey(factualSummaryKey(id))]?.let { parseSummary(it) }
    }
    fun telemetry(id: String): Flow<WorkoutTelemetry?> = context.sessionStore.data.map { preferences ->
        preferences[telemetryKey(id)]?.let { telemetryJson.decodeFromString<WorkoutTelemetry>(it) }
    }
}

@Serializable
data class ExerciseAggregate(
    val exercise: ExerciseType,
    val detectedReps: Int,
    val leftReps: Int,
    val rightReps: Int,
    val firstDetected: String,
    val lastDetected: String
)

@Serializable
data class FactualPayload(
    @SerialName("duration_seconds") val durationSeconds: Long,
    @SerialName("total_detected_reps") val totalReps: Int,
    @SerialName("exercise_aggregates") val exercises: List<ExerciseAggregate>
)

fun factualPayload(telemetry: WorkoutTelemetry): FactualPayload {
    fun seconds(time: String): Long {
        val parts = time.split(":")
        require(parts.size == 2) { "Invalid recorded timestamp" }
        return parts[0].toLong() * 60 + parts[1].toLong()
    }
    return FactualPayload(telemetry.durationSeconds, telemetry.totals.totalReps,
        telemetry.timeline.groupBy { it.exercise }.map { (exercise, blocks) ->
            ExerciseAggregate(exercise, blocks.sumOf { it.reps }, blocks.sumOf { it.leftReps },
                blocks.sumOf { it.rightReps }, blocks.minBy { seconds(it.startTime) }.startTime,
                blocks.maxBy { seconds(it.endTime) }.endTime)
        }.sortedBy { seconds(it.firstDetected) })
}

class InvalidSummaryException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun parseSummary(text: String): FactualSummary {
    val result = try {
        telemetryJson.decodeFromString<FactualSummary>(text)
    } catch (e: JsonException) {
        throw InvalidSummaryException("The AI returned an invalid summary.", e)
    } catch (e: IllegalArgumentException) {
        throw InvalidSummaryException("The AI returned an invalid summary.", e)
    }
    if (result.headline.isBlank() || result.overview.isBlank() ||
        result.chronologicalEntries.any { it.timestamp.isBlank() || it.title.isBlank() || it.description.isBlank() }) {
        throw InvalidSummaryException("The AI returned an incomplete summary.")
    }
    return result
}

class GeminiSummaryRepository(private val context: Context) {
    suspend fun generate(telemetry: WorkoutTelemetry): FactualSummary {
        check(FirebaseApp.getApps(context).isNotEmpty()) { "Firebase is not configured. Add app/google-services.json." }
        val entry = Schema.obj(mapOf("timestamp" to Schema.string(), "title" to Schema.string(), "description" to Schema.string()))
        val schema = Schema.obj(mapOf("headline" to Schema.string(), "overview" to Schema.string(),
            "chronological_entries" to Schema.array(entry)))
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig { responseMimeType = "application/json"; responseSchema = schema },
            systemInstruction = content { text(
                "Write a concise factual workout recap using only the supplied JSON. Counts are detected estimates, not ground truth. " +
                    "Include duration and the detected exercise counts and left/right totals. " +
                    "First and last detections are boundaries, not continuous activity or a sequence of blocks; never infer chronology between them. " +
                    "Chronological entries may describe only those recorded first or last timestamps, or be empty. " +
                    "If no reps were detected, say so and return an empty chronological_entries array. " +
                    "Never provide advice, coaching, form evaluation, encouragement as assessment, or infer effort, health, calories or activities."
            ) }
        )
        val response = model.generateContent(telemetryJson.encodeToString(factualPayload(telemetry))).text
            ?: throw InvalidSummaryException("The AI returned an empty summary.")
        return parseSummary(response)
    }
}

enum class SummaryPhase { PENDING, GENERATING, COMPLETED, FAILED }
data class SummaryStatus(val phase: SummaryPhase, val message: String? = null)

fun summaryStatus(configured: Boolean, hasSummary: Boolean, workState: WorkInfo.State?, error: String?): SummaryStatus = when {
    hasSummary -> SummaryStatus(SummaryPhase.COMPLETED)
    !configured -> SummaryStatus(SummaryPhase.FAILED, "Firebase is not configured. Add app/google-services.json and rebuild.")
    workState == WorkInfo.State.RUNNING -> SummaryStatus(SummaryPhase.GENERATING)
    workState == WorkInfo.State.FAILED || workState == WorkInfo.State.CANCELLED || workState == WorkInfo.State.SUCCEEDED ->
        SummaryStatus(SummaryPhase.FAILED, error ?: "Summary generation did not complete. Retry.")
    else -> SummaryStatus(SummaryPhase.PENDING)
}

data class SummaryFailure(val retry: Boolean, val message: String)
fun classifySummaryFailure(error: Throwable, attempts: Int): SummaryFailure {
    val transient = error is IOException || error is QuotaExceededException ||
        error is RequestTimeoutException || error is ServerException || error is ServiceConnectionHandshakeFailedException
    return when {
        transient && attempts < 5 -> SummaryFailure(true, "Connection or quota issue; retrying automatically.")
        transient -> SummaryFailure(false, "Connection or quota issue persists. Check connectivity and Firebase quota, then retry.")
        error is InvalidSummaryException || error is com.google.firebase.ai.type.SerializationException ->
            SummaryFailure(false, "The AI returned an invalid response. Retry or check the model configuration.")
        error is APINotConfiguredException || error is ServiceDisabledException || error is InvalidAPIKeyException ->
            SummaryFailure(false, "Check the Firebase configuration, enabled AI Logic APIs, and model availability.")
        error is PermissionMissingException -> SummaryFailure(false, "Firebase access denied. Check App Check and project permissions.")
        else -> SummaryFailure(false, "Summary generation failed. Check Firebase AI Logic and App Check configuration, then retry.")
    }
}

suspend fun scheduleSummary(context: Context, id: String, replace: Boolean = false) {
    val work = WorkManager.getInstance(context)
    work.cancelUniqueWork("coach_$id")
    val request = OneTimeWorkRequestBuilder<SummaryWorker>()
        .setInputData(workDataOf("session_id" to id))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    withContext(Dispatchers.IO) {
        work.enqueueUniqueWork(factualWorkName(id), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request).result.get()
    }
}

class SummaryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("session_id") ?: return Result.failure(workDataOf(ERROR_KEY to "Missing session identifier."))
        val storage = LocalSessionStorage(applicationContext)
        return try {
            val telemetry = try {
                storage.telemetry(id).first()
            } catch (e: IOException) {
                android.util.Log.e("SummaryWorker", "Could not read workout", e)
                return Result.failure(workDataOf(ERROR_KEY to "Could not read the saved workout on this device."))
            }
                ?: return Result.failure(workDataOf(ERROR_KEY to "Saved workout not found."))
            if (FirebaseApp.getApps(applicationContext).isEmpty()) {
                return Result.failure(workDataOf(ERROR_KEY to "Firebase is not configured. Add app/google-services.json and rebuild."))
            }
            val result = GeminiSummaryRepository(applicationContext).generate(telemetry)
            try {
                storage.saveSummary(id, result)
            } catch (e: IOException) {
                android.util.Log.e("SummaryWorker", "Could not save summary", e)
                return Result.failure(workDataOf(ERROR_KEY to "Could not save the summary on this device."))
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("SummaryWorker", "Summary request failed", e)
            val failure = classifySummaryFailure(e, runAttemptCount)
            if (failure.retry) Result.retry() else Result.failure(workDataOf(ERROR_KEY to failure.message))
        }
    }
}
