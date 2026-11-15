package com.example.fitpulseai.camera

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.fitpulseai.domain.Joint
import com.example.fitpulseai.domain.PoseFrame
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors

class CameraPipeline(
    private val context: Context,
    private val onFrame: (PoseFrame) -> Unit,
    private val onError: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .setPreferredHardwareConfigs(PoseDetectorOptions.CPU_GPU).build()
    )
    private var provider: ProcessCameraProvider? = null
    @Volatile private var closed = false
    private var bindingVersion = 0

    fun bind(owner: LifecycleOwner, view: PreviewView, front: Boolean) {
        val version = ++bindingVersion
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed || version != bindingVersion) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                onError("Camera unavailable: ${e.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val image = proxy.image
        if (image == null || closed) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val width = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
        val height = if (rotation == 90 || rotation == 270) proxy.width else proxy.height
        val timestamp = SystemClock.elapsedRealtime()
        try {
            detector.process(InputImage.fromMediaImage(image, rotation))
                .addOnSuccessListener { pose ->
                    if (!closed) onFrame(PoseFrame(pose.allPoseLandmarks.associate { landmark ->
                        landmark.landmarkType to Joint(landmark.position3D.x, landmark.position3D.y, landmark.position3D.z, landmark.inFrameLikelihood)
                    }, timestamp, width, height))
                }
                .addOnFailureListener { onError("Pose detection failed: ${it.localizedMessage}") }
                .addOnCompleteListener { proxy.close() }
        } catch (e: Exception) {
            proxy.close()
            onError("Pose detection failed: ${e.localizedMessage}")
        }
    }

    fun close() {
        closed = true
        bindingVersion++
        provider?.unbindAll()
        detector.close()
        executor.shutdown()
    }
}
