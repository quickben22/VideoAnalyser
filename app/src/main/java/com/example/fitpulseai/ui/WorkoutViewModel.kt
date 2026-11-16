package com.example.fitpulseai.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.example.fitpulseai.data.LocalSessionStorage
import com.example.fitpulseai.data.SummaryStatus
import com.example.fitpulseai.data.SummaryPhase
import com.example.fitpulseai.data.factualWorkName
import com.example.fitpulseai.data.scheduleSummary
import com.example.fitpulseai.data.summaryStatus
import com.example.fitpulseai.data.toTrainerReport
import com.example.fitpulseai.domain.*
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkoutState(
    val frame: PoseFrame? = null,
    val calibrated: Boolean = false,
    val started: Boolean = false,
    val elapsed: Long = 0,
    val reps: Int = 0,
    val exercise: ExerciseType = ExerciseType.IDLE,
    val summary: WorkoutTelemetry? = null,
    val report: FactualSummary? = null,
    val trainerReport: String? = null,
    val summaryStatus: SummaryStatus = SummaryStatus(SummaryPhase.PENDING),
    val error: String? = null,
    val saving: Boolean = false
)

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = LocalSessionStorage(application)
    private val _state = MutableStateFlow(WorkoutState())
    val state: StateFlow<WorkoutState> = _state.asStateFlow()
    private var recorder = SessionRecorder()
    private var reportJob: kotlinx.coroutines.Job? = null
    private var trainerReportJob: kotlinx.coroutines.Job? = null
    val firebaseConfigured = FirebaseApp.getApps(application).isNotEmpty()

    init {
        viewModelScope.launch {
            try {
                val id = storage.latestSession.first()
                val saved = id?.let { storage.telemetry(it).first() }
                if (saved != null && recorder.startedAtMs == null && _state.value.summary == null) {
                    showSummary(saved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error("Could not load saved workout: ${e.localizedMessage}")
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(500)
                recorder.startedAtMs?.let { start -> _state.update { it.copy(elapsed = (SystemClock.elapsedRealtime() - start) / 1000) } }
            }
        }
    }

    fun onFrame(raw: PoseFrame) {
        if (_state.value.summary != null || _state.value.saving) return
        val outcome = recorder.onFrame(raw)
        _state.update { it.copy(frame = outcome.frame, calibrated = outcome.calibrated, started = outcome.started, reps = outcome.reps, exercise = outcome.exercise ?: it.exercise) }
    }

    fun finish() {
        val telemetry = recorder.finish(SystemClock.elapsedRealtime()) ?: return
        recorder.reset()
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                storage.save(telemetry)
                showSummary(telemetry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, summary = telemetry, error = "Could not save workout: ${e.localizedMessage}") }
            }
        }
    }

    private fun showSummary(telemetry: WorkoutTelemetry) {
        _state.update { it.copy(summary = telemetry, saving = false) }
        trainerReportJob?.cancel()
        trainerReportJob = viewModelScope.launch {
            try {
                storage.trainerReport(telemetry.sessionId).collect { text ->
                    _state.update { it.copy(trainerReport = text) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error("Could not load saved report: ${e.localizedMessage}")
            }
        }
        reportJob?.cancel()
        reportJob = viewModelScope.launch {
            val work = WorkManager.getInstance(getApplication())
            val workFlow = work.getWorkInfosForUniqueWorkFlow(factualWorkName(telemetry.sessionId))
            try {
                work.cancelUniqueWork("coach_${telemetry.sessionId}")
                val existingReport = storage.summary(telemetry.sessionId).first()
                if (firebaseConfigured && existingReport == null && workFlow.first().isEmpty()) {
                    scheduleSummary(getApplication(), telemetry.sessionId)
                }
                combine(storage.summary(telemetry.sessionId), workFlow) { report, infos ->
                    val info = infos.lastOrNull()
                    report to summaryStatus(firebaseConfigured, report != null, info?.state, info?.outputData?.getString("error"))
                }.collect { (report, status) -> _state.update { it.copy(report = report, summaryStatus = status) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(summaryStatus = SummaryStatus(SummaryPhase.FAILED, "Could not load summary: ${e.localizedMessage}")) }
            }
        }
    }

    /** Generates the deterministic trainer-style report from the recorded session data. */
    fun generateReport() {
        val telemetry = _state.value.summary ?: return
        if (_state.value.trainerReport != null) return
        viewModelScope.launch {
            try {
                val text = telemetry.toTrainerReport()
                storage.saveTrainerReport(telemetry.sessionId, text)
                _state.update { it.copy(trainerReport = text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error("Could not generate report: ${e.localizedMessage}")
            }
        }
    }

    fun retry() {
        val id = _state.value.summary?.sessionId ?: return
        if (firebaseConfigured) {
            _state.update { it.copy(summaryStatus = SummaryStatus(SummaryPhase.PENDING)) }
            viewModelScope.launch {
                try {
                    scheduleSummary(getApplication(), id, replace = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(summaryStatus = SummaryStatus(SummaryPhase.FAILED, "Could not schedule recap: ${e.localizedMessage}")) }
                }
            }
        }
        else error("Firebase is not configured. Add app/google-services.json.")
    }

    fun newWorkout() {
        reportJob?.cancel()
        trainerReportJob?.cancel()
        recorder.reset()
        _state.value = WorkoutState()
    }

    fun error(message: String) { _state.update { it.copy(error = message) } }
}
