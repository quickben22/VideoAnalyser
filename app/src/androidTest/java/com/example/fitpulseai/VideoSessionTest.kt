package com.example.fitpulseai

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitpulseai.data.telemetryJson
import com.example.fitpulseai.data.toTextReport
import com.example.fitpulseai.data.toTrainerReport
import com.example.fitpulseai.domain.Joint
import com.example.fitpulseai.domain.PoseFrame
import com.example.fitpulseai.domain.SessionRecorder
import com.example.fitpulseai.vision.features
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VideoSessionTest {

    @Test
    fun videoSessionProducesSummaryDocument() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = args.getString("video")
        val video = when {
            name == null -> File(context.filesDir, "workout.mp4")
            File(name).isAbsolute -> File(name)
            else -> File(context.filesDir, name)
        }
        assertTrue("Video not found at ${video.absolutePath}", video.isFile)

        val intervalMs = args.getString("intervalMs")?.toLong() ?: 80L
        val maxSeconds = args.getString("maxSeconds")?.toLong() ?: 0L
        val startMs = (args.getString("startSeconds")?.toLong() ?: 0L) * 1000
        val stopPtsUs = if (maxSeconds > 0) (startMs + maxSeconds * 1000) * 1000 else Long.MAX_VALUE
        val dumpFrames = args.getString("dumpFrames") == "true"
        val probe = args.getString("probe") == "true"
        val probeRows = StringBuilder(
            "pts_ms,landmarks,nose,torso_x,torso_y,torso_len,upright,kneeL,kneeR,elbowL,elbowR," +
                "abdL,abdR,wristOverL,wristOverR,ankleSpread,wristSpread,hipAnkleL,hipAnkleR,posed,calibrated,reps,exercise,posture\n"
        )

        val detector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .setPreferredHardwareConfigs(PoseDetectorOptions.CPU_GPU)
                .build()
        )
        val recorder = SessionRecorder()
        val sizes = mutableListOf<Int>()
        val noses = mutableListOf<Int>()
        var decoded = 0
        var analysed = 0
        var posed = 0
        var calibrated = 0
        var lastPtsMs = 0L
        var nextAnalysisUs = Long.MIN_VALUE
        var width = 0
        var height = 0

        try {
            VideoDecoder(video.path).use { decoder ->
                width = decoder.displayWidth
                height = decoder.displayHeight
                decoder.decode(stopPtsUs) { image, ptsUs ->
                    decoded++
                    lastPtsMs = ptsUs / 1000
                    if (lastPtsMs < startMs) return@decode
                    if (ptsUs < nextAnalysisUs) return@decode
                    nextAnalysisUs = ptsUs + intervalMs * 1000
                    val pose = Tasks.await(detector.process(InputImage.fromMediaImage(image, decoder.rotation)), 30, TimeUnit.SECONDS)
                    analysed++
                    val all = pose.allPoseLandmarks
                    if (sizes.size < 5) {
                        sizes += all.size
                        noses += all.count { it.landmarkType == PoseLandmark.NOSE }
                        Log.i(TAG, "pts=$lastPtsMs landmarks=${all.size} nose=${all.count { it.landmarkType == PoseLandmark.NOSE }} " +
                            "shoulderL=${all.count { it.landmarkType == PoseLandmark.LEFT_SHOULDER }}")
                    }
                    val frame = poseFrame(all, lastPtsMs, width, height)
                    val torso = com.example.fitpulseai.vision.PoseMath.torso(frame)
                    if (torso != null) posed++
                    val outcome = recorder.onFrame(frame)
                    if (outcome.calibrated) calibrated++
                    if (probe) {
                        val ls = frame.joints[PoseLandmark.LEFT_SHOULDER]
                        val rs = frame.joints[PoseLandmark.RIGHT_SHOULDER]
                        val lh = frame.joints[PoseLandmark.LEFT_HIP]
                        val rh = frame.joints[PoseLandmark.RIGHT_HIP]
                        val cx = if (ls != null && rs != null) (ls.x + rs.x) / 2 else -1f
                        val cy = if (lh != null && rh != null) (lh.y + rh.y) / 2 else -1f
                        val f = frame.features()
                        fun num(value: Float?) = value ?: -1f
                        probeRows.append(
                            "$lastPtsMs,${all.size},${all.count { it.landmarkType == PoseLandmark.NOSE }}," +
                                "$cx,$cy,${torso ?: -1f},${num(f?.uprightDeg)},${num(f?.kneeLeft)},${num(f?.kneeRight)}," +
                                "${num(f?.elbowLeft)},${num(f?.elbowRight)},${num(f?.abductionLeft)},${num(f?.abductionRight)}," +
                                "${num(f?.wristAboveShoulderLeft)},${num(f?.wristAboveShoulderRight)}," +
                                "${num(f?.ankleSpread)},${num(f?.wristSpread)}," +
                                "${num(f?.hipAboveAnkleLeft)},${num(f?.hipAboveAnkleRight)}," +
                                "${if (torso != null) 1 else 0},${if (outcome.calibrated) 1 else 0}," +
                                "${outcome.reps},${outcome.exercise ?: ""},${outcome.posture}\n"
                        )
                    }
                    if (probe && analysed % 2000 == 0) {
                        File(context.filesDir, "probe.csv").writeText(probeRows.toString())
                    }
                    if (analysed % 500 == 0) {
                        Log.i(TAG, "analysed=$analysed posed=$posed calibrated=$calibrated reps=${outcome.reps} at ${lastPtsMs}ms")
                    }
                }
            }
        } finally {
            detector.close()
        }

        if (dumpFrames) {
            val requested = args.getString("frameTimes")
            val times = if (requested.isNullOrBlank()) listOf(5_000L, 300_000L, 900_000L)
            else requested.split(',').mapNotNull { it.trim().toLongOrNull()?.times(1000) }
            dumpSampleFrames(video, context.filesDir, times)
        }
        if (probe) File(context.filesDir, "probe.csv").writeText(probeRows.toString())

        Log.i(TAG, "decoded=$decoded analysed=$analysed posed=$posed calibrated=$calibrated lastPts=$lastPtsMs")
        Log.i(TAG, "landmark sizes=${sizes.distinct()} nose counts=${noses.distinct()}")
        assertTrue("No frames were decoded from ${video.name}", decoded > 0)
        assertTrue("No frames were analysed from ${video.name}", analysed > 0)
        assertTrue(
            "Pose not found in enough frames: $posed of $analysed (needs both shoulders and hips at 0.65 confidence)",
            posed * 2 >= analysed
        )

        val telemetry = recorder.finish(lastPtsMs)
        assertNotNull("Session never started: shoulders and ankles were not visible together", telemetry)

        val report = telemetry!!.toTextReport(
            source = video.name,
            sampledFps = 1000.0 / intervalMs,
            diagnostics = listOf(
                "Frames decoded      : $decoded",
                "Frames analysed     : $analysed (${(1000.0 / intervalMs)} fps target)",
                "Pose found          : $posed",
                "Calibrated frames   : $calibrated",
                "Accepted by gate    : ${recorder.acceptedFrames}",
                "Rejected by gate    : ${recorder.rejectedFrames} (scale or subject jump)",
                "Subject switches    : ${recorder.subjectSwitches}",
                "Analysed up to      : ${lastPtsMs}ms",
                "Landmark count      : ${sizes.distinct().sorted()}",
                "Nose landmarks      : ${noses.distinct().sorted()}"
            )
        )
        File(context.filesDir, "session-summary.txt").writeText(report)
        File(context.filesDir, "session-telemetry.json").writeText(telemetryJson.encodeToString(telemetry))
        Log.i(TAG, "Summary written to ${File(context.filesDir, "session-summary.txt").absolutePath}")

        val trainerReport = telemetry!!.toTrainerReport()
        File(context.filesDir, "trainer-report.txt").writeText(trainerReport)
        Log.i(TAG, "Trainer report written to ${File(context.filesDir, "trainer-report.txt").absolutePath}")

        assertTrue("Session summary has no duration", telemetry.durationSeconds > 0)
        assertTrue(report.contains("Total detections"))
        assertTrue("Trainer report has no exercise breakdown", trainerReport.contains("Vježbe i ponavljanja"))
    }

    private fun dumpSampleFrames(video: File, dir: File, times: List<Long> = listOf(5_000L, 300_000L, 900_000L)) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.path)
            times.forEach { at ->
                val bitmap = retriever.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@forEach
                FileOutputStream(File(dir, "frame-$at.jpg")).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
                bitmap.recycle()
            }
        } finally {
            retriever.release()
        }
    }

    private fun poseFrame(landmarks: List<PoseLandmark>, time: Long, width: Int, height: Int) = PoseFrame(
        landmarks.associate { landmark ->
            landmark.landmarkType to Joint(
                landmark.position3D.x, landmark.position3D.y, landmark.position3D.z, landmark.inFrameLikelihood
            )
        },
        time, width, height
    )

    private class VideoDecoder(path: String) : AutoCloseable {
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        val width: Int
        val height: Int
        val rotation: Int

        val displayWidth: Int get() = if (rotation % 180 == 0) width else height
        val displayHeight: Int get() = if (rotation % 180 == 0) height else width

        init {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            width = format.getInteger(MediaFormat.KEY_WIDTH)
            height = format.getInteger(MediaFormat.KEY_HEIGHT)
            rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
        }

        fun decode(maxPtsUs: Long, onFrame: (Image, Long) -> Unit) {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0 && info.presentationTimeUs <= maxPtsUs) {
                        codec.getOutputImage(outIndex)?.let { onFrame(it, info.presentationTimeUs) }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (info.presentationTimeUs > maxPtsUs) outputDone = true
                }
            }
        }

        override fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }

        private companion object {
            const val TIMEOUT_US = 10_000L
        }
    }

    private companion object {
        const val TAG = "VideoSessionTest"
    }
}
