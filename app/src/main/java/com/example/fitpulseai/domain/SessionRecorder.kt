package com.example.fitpulseai.domain

import com.example.fitpulseai.vision.LandmarkSmoother
import com.example.fitpulseai.vision.PoseGate
import com.example.fitpulseai.vision.features
import com.example.fitpulseai.vision.visible
import com.google.mlkit.vision.pose.PoseLandmark as L

data class FrameOutcome(
    val frame: PoseFrame,
    val calibrated: Boolean,
    val started: Boolean,
    val reps: Int,
    val exercise: ExerciseType?,
    val posture: Posture = Posture.UNKNOWN
)

/**
 * Turns raw pose frames into reps and a finished session. Shared by the camera UI and the
 * video regression test so both drive exactly the same detection logic.
 *
 * Frames rejected by [PoseGate] are ignored rather than analysed: in a group workout the pose
 * detector can hop between people, and mixing their skeletons produces angle jumps that look
 * like repetitions.
 */
class SessionRecorder(gate: PoseGate = PoseGate()) {
    private var smoother = LandmarkSmoother()
    private var detector = ExerciseDetector()
    private var aggregator: SessionAggregator? = null
    private val gate = gate
    private var rejectRun = 0
    private var lastAccepted: PoseFrame? = null

    var startedAtMs: Long? = null
        private set
    val count: Int get() = aggregator?.count ?: 0
    val isRecording: Boolean get() = aggregator != null
    val acceptedFrames: Int get() = gate.accepted
    val rejectedFrames: Int get() = gate.rejected
    val subjectSwitches: Int get() = gate.subjectSwitches

    fun onFrame(raw: PoseFrame): FrameOutcome {
        if (!gate.accept(raw)) {
            rejectRun++
            // A single misdetected frame is not a new subject: only clear the smoothing history
            // immediately, and give up the detector's in-flight reps after a sustained run.
            if (rejectRun == 1) smoother.reset()
            if (rejectRun == RESET_AFTER_REJECTS) detector = ExerciseDetector()
            // Keep showing the last accepted subject instead of flickering to a rejected one.
            return FrameOutcome(lastAccepted ?: raw, false, startedAtMs != null, count, null)
        }
        rejectRun = 0

        val frame = smoother.smooth(raw)
        lastAccepted = frame
        val features = frame.features()
        val calibrated = CALIBRATION_JOINTS.all { frame.visible(it) != null }
        if (startedAtMs == null && calibrated) {
            startedAtMs = frame.timestampMs
            aggregator = SessionAggregator(frame.timestampMs)
        }
        val posture = features?.posture ?: Posture.UNKNOWN
        aggregator?.observePosture(frame.timestampMs, posture)
        val reps = if (calibrated && aggregator != null) detector.process(frame, features) else {
            detector = ExerciseDetector()
            emptyList()
        }
        reps.forEach { aggregator?.add(it) }
        return FrameOutcome(frame, calibrated, startedAtMs != null, count, reps.lastOrNull()?.exercise, posture)
    }

    fun finish(endMs: Long): WorkoutTelemetry? = aggregator?.finish(endMs)

    fun reset() {
        smoother = LandmarkSmoother()
        detector = ExerciseDetector()
        aggregator = null
        startedAtMs = null
        gate.reset()
        rejectRun = 0
        lastAccepted = null
    }

    private companion object {
        const val RESET_AFTER_REJECTS = 50
        val CALIBRATION_JOINTS = listOf(L.LEFT_SHOULDER, L.RIGHT_SHOULDER, L.LEFT_ANKLE, L.RIGHT_ANKLE)
    }
}
