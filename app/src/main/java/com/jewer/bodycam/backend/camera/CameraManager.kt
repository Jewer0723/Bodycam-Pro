package com.jewer.bodycam.backend.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.jewer.bodycam.backend.functions.getFlashlightStatus
import java.util.concurrent.Executors

object CameraManager {
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    @SuppressLint("StaticFieldLeak")
    var surfaceProcessor: WideAngleSurfaceProcessor? = null
        private set
    var wideAngleEffect: CustomCameraEffect? = null
        private set
    var preview: Preview? = null
        private set
    var videoCapture: VideoCapture<Recorder>? = null
        private set
    var recorder: Recorder? = null
        private set
    var activeCamera: Camera? = null
        private set

    var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    var currentPreviewView: PreviewView? = null
    var currentFps: Int = 0
    var currentQuality: String = "SD"

    fun getOrCreateSurfaceProcessor(
        context: Context,
        isPortrait: Boolean,
        isFrontCamera: Boolean,
        fisheyeK: Float,
        fisheyeScale: Float,
        isFisheyeEnabled: Boolean,
        brand: String,
        userName: String,
        isRecording: Boolean
    ): WideAngleSurfaceProcessor {
        var processor = surfaceProcessor
        if (processor == null) {
            processor = WideAngleSurfaceProcessor(
                context = context.applicationContext,
                isPortrait = isPortrait,
                isFrontCamera = isFrontCamera,
                fisheyeK = fisheyeK,
                fisheyeScale = fisheyeScale,
                isFisheyeEnabled = isFisheyeEnabled,
                brand = brand,
                userName = userName,
                isRecording = isRecording
            )
            surfaceProcessor = processor
            wideAngleEffect = CustomCameraEffect(
                CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
                cameraExecutor,
                processor
            ) { Log.e("CameraManager", "WideAngleEffect error", it) }
        } else {
            processor.updateParams(
                isPortrait = isPortrait,
                isFrontCamera = isFrontCamera,
                fisheyeK = fisheyeK,
                fisheyeScale = fisheyeScale,
                isFisheyeEnabled = isFisheyeEnabled,
                brand = brand,
                userName = userName,
                isRecording = isRecording
            )
        }
        return processor
    }

    fun getOrCreateVideoCapture(selectedQuality: String): VideoCapture<Recorder> {
        val currentCap = videoCapture
        if (currentCap == null || currentQuality != selectedQuality) {
            currentQuality = selectedQuality
            val targetQuality = when (selectedQuality) {
                "FHD" -> Quality.FHD
                "HD" -> Quality.HD
                else -> Quality.SD
            }
            val newRecorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        targetQuality,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            recorder = newRecorder
            val newCap = VideoCapture.withOutput(newRecorder)
            videoCapture = newCap
            return newCap
        }
        return currentCap
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun bindCamera(
        context: Context,
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector,
        previewView: PreviewView?,
        fps: Int,
        selectedQuality: String
    ): Camera? {
        currentCameraSelector = cameraSelector
        currentPreviewView = previewView
        currentFps = fps
        currentQuality = selectedQuality

        val vCap = getOrCreateVideoCapture(selectedQuality)
        val effect = wideAngleEffect ?: return null

        val previewBuilder = Preview.Builder()
        if (fps > 0) {
            val camera2Extender = Camera2Interop.Extender(previewBuilder)
            camera2Extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(fps, fps)
            )
        }
        val newPreview = previewBuilder.build()
        preview = newPreview

        if (previewView != null) {
            newPreview.surfaceProvider = previewView.surfaceProvider
        }

        try {
            cameraProvider.unbindAll()

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(newPreview)
                .addUseCase(vCap)
                .addEffect(effect)
                .build()

            val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
            activeCamera = camera

            if (getFlashlightStatus(context) && camera.cameraInfo.hasFlashUnit()) {
                camera.cameraControl.enableTorch(true)
            }

            return camera
        } catch (e: Exception) {
            Log.e("CameraManager", "bindCamera error", e)
            return null
        }
    }

    fun attachPreviewSurface(previewView: PreviewView) {
        currentPreviewView = previewView
        preview?.surfaceProvider = previewView.surfaceProvider
    }

    fun detachPreviewSurface() {
        currentPreviewView = null
        preview?.surfaceProvider = null
    }
}
