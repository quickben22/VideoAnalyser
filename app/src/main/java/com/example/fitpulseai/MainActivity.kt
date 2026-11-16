package com.example.fitpulseai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.camera.view.PreviewView
import com.example.fitpulseai.camera.CameraPipeline
import com.example.fitpulseai.domain.PoseFrame
import com.example.fitpulseai.data.SummaryPhase
import com.example.fitpulseai.data.factualPayload
import com.example.fitpulseai.data.label
import com.example.fitpulseai.ui.WorkoutViewModel
import com.google.mlkit.vision.pose.PoseLandmark as L
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { FitPulseApp() } }
    }
}

@Composable
private fun FitPulseApp(vm: WorkoutViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    if (state.summary != null) {
        Summary(state, vm.firebaseConfigured, vm::retry, vm::newWorkout, vm::generateReport)
    } else if (!permission) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera access is needed to analyze your workout. Video is never recorded.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera access") }
        }
    } else {
        Workout(state, vm)
    }
}

@Composable
private fun Workout(state: com.example.fitpulseai.ui.WorkoutState, vm: WorkoutViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var front by remember { mutableStateOf(true) }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val pipeline = remember { CameraPipeline(context, vm::onFrame, vm::error) }
    DisposableEffect(pipeline) { onDispose { pipeline.close() } }
    LaunchedEffect(front, owner) { pipeline.bind(owner, preview, front) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        Skeleton(state.frame, front)
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Color.Black.copy(alpha = 0.75f), shape = MaterialTheme.shapes.large) {
                Text("${formatTime(state.elapsed)}  •  ${state.exercise.name.replace('_', ' ')}  •  ${state.reps} reps", Modifier.padding(12.dp), color = Color.White)
            }
            if (!state.calibrated) {
                Text("Step back until your full body fits inside the frame.", Modifier.padding(12.dp).background(Color.Black.copy(alpha = 0.75f)).padding(8.dp), color = Color.White)
            }
            state.error?.let { Text(it, Modifier.background(Color.Black).padding(8.dp), color = Color.Yellow) }
        }
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { front = !front }) { Text("Switch camera") }
            Button(onClick = vm::finish, enabled = state.started && !state.saving) { Text(if (state.saving) "Saving..." else "Finish workout") }
        }
    }
}

private val bones = listOf(
    L.LEFT_SHOULDER to L.RIGHT_SHOULDER, L.LEFT_SHOULDER to L.LEFT_ELBOW, L.LEFT_ELBOW to L.LEFT_WRIST,
    L.RIGHT_SHOULDER to L.RIGHT_ELBOW, L.RIGHT_ELBOW to L.RIGHT_WRIST,
    L.LEFT_SHOULDER to L.LEFT_HIP, L.RIGHT_SHOULDER to L.RIGHT_HIP, L.LEFT_HIP to L.RIGHT_HIP,
    L.LEFT_HIP to L.LEFT_KNEE, L.LEFT_KNEE to L.LEFT_ANKLE, L.RIGHT_HIP to L.RIGHT_KNEE, L.RIGHT_KNEE to L.RIGHT_ANKLE,
    L.LEFT_ANKLE to L.LEFT_HEEL, L.LEFT_HEEL to L.LEFT_FOOT_INDEX, L.RIGHT_ANKLE to L.RIGHT_HEEL, L.RIGHT_HEEL to L.RIGHT_FOOT_INDEX,
    L.LEFT_WRIST to L.LEFT_INDEX, L.RIGHT_WRIST to L.RIGHT_INDEX, L.LEFT_SHOULDER to L.LEFT_EAR, L.RIGHT_SHOULDER to L.RIGHT_EAR
)

@Composable
private fun Skeleton(frame: PoseFrame?, front: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        if (frame == null || frame.width <= 0 || frame.height <= 0) return@Canvas
        val scale = maxOf(size.width / frame.width, size.height / frame.height)
        val xOffset = (size.width - frame.width * scale) / 2
        val yOffset = (size.height - frame.height * scale) / 2
        fun point(x: Float, y: Float) = Offset(xOffset + (if (front) frame.width - x else x) * scale, yOffset + y * scale)
        bones.forEach { (a, b) ->
            val first = frame.joints[a]
            val second = frame.joints[b]
            if (first != null && second != null) {
                drawLine(if (minOf(first.confidence, second.confidence) >= 0.65f) Color.Green else Color.Yellow,
                    point(first.x, first.y), point(second.x, second.y), strokeWidth = 4.dp.toPx())
            }
        }
        frame.joints.values.forEach { joint ->
            drawCircle(if (joint.confidence >= 0.65f) Color.Green else Color.Yellow, 3.dp.toPx(), point(joint.x, joint.y))
        }
    }
}

@Composable
private fun Summary(state: com.example.fitpulseai.ui.WorkoutState, configured: Boolean, retry: () -> Unit, start: () -> Unit, generateReport: () -> Unit) {
    val summary = state.summary ?: return
    val trainerReportText = state.trainerReport
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Workout summary", style = MaterialTheme.typography.headlineMedium)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("${summary.totals.totalReps} reps", style = MaterialTheme.typography.headlineSmall)
            Text("Duration: ${formatTime(summary.durationSeconds)}")
        } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Detected exercises", style = MaterialTheme.typography.titleLarge)
            if (summary.timeline.isEmpty()) Text("No reps detected.")
            factualPayload(summary).exercises.forEach { exercise ->
                val sides = when (exercise.exercise) {
                    com.example.fitpulseai.domain.ExerciseType.SQUAT,
                    com.example.fitpulseai.domain.ExerciseType.PUSH_UP -> "both sides"
                    else -> "L ${exercise.leftReps}, R ${exercise.rightReps}"
                }
                Text("${exercise.exercise.label()} · ${exercise.detectedReps} detected ($sides)")
                Text("First detection: ${exercise.firstDetected} · Last detection: ${exercise.lastDetected}")
            }
        } }
        if (trainerReportText == null) {
            Button(onClick = generateReport) { Text("Generate report") }
        } else {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Trainer report", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(trainerReportText)
            } }
        }
        when (state.summaryStatus.phase) {
            SummaryPhase.PENDING -> Text("AI recap pending; it will start when connected.")
            SummaryPhase.GENERATING -> Text("Generating AI recap…")
            SummaryPhase.FAILED -> {
                Text(state.summaryStatus.message ?: "AI recap failed.", color = MaterialTheme.colorScheme.error)
                if (configured) OutlinedButton(onClick = retry) { Text("Retry recap") }
            }
            SummaryPhase.COMPLETED -> state.report?.let { report ->
                Text("AI recap · generated from detected estimates", style = MaterialTheme.typography.labelLarge)
                Text(report.headline, style = MaterialTheme.typography.titleLarge)
                Text(report.overview)
                report.chronologicalEntries.forEach { Text("${it.timestamp} · ${it.title}: ${it.description}") }
            }
        }
        Button(onClick = start) { Text("New workout") }
    }
}

private fun formatTime(seconds: Long) = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
