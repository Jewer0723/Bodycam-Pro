package com.jewer.bodycam.frontend.screens

import android.content.Intent
import android.graphics.RectF
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.jewer.bodycam.R
import com.jewer.bodycam.backend.camera.CustomCameraEffect
import com.jewer.bodycam.backend.camera.WideAngleSurfaceProcessor
import com.jewer.bodycam.backend.functions.getBeepSoundStatus
import com.jewer.bodycam.backend.functions.getBodyDetectionStatus
import com.jewer.bodycam.backend.functions.getBodycamBrand
import com.jewer.bodycam.backend.functions.getCameraFps
import com.jewer.bodycam.backend.functions.getCurrentTime
import com.jewer.bodycam.backend.functions.getFisheyeK
import com.jewer.bodycam.backend.functions.getFisheyeScale
import com.jewer.bodycam.backend.functions.getFlashlightStatus
import com.jewer.bodycam.backend.functions.getInstructionAlertDialogStatus
import com.jewer.bodycam.backend.functions.getLastBackZoomRatio
import com.jewer.bodycam.backend.functions.getLastFrontZoomRatio
import com.jewer.bodycam.backend.functions.getLowBrightnessStatus
import com.jewer.bodycam.backend.functions.getSelectedBackCameraId
import com.jewer.bodycam.backend.functions.getSelectedFrontCameraId
import com.jewer.bodycam.backend.functions.getSimulatedWideAngleStatus
import com.jewer.bodycam.backend.functions.getUserName
import com.jewer.bodycam.backend.functions.getVibrateStatus
import com.jewer.bodycam.backend.functions.getVideoQuality
import com.jewer.bodycam.backend.functions.orientationFlow
import com.jewer.bodycam.backend.functions.playSoundAtMaxVolume
import com.jewer.bodycam.backend.functions.setScreenBrightness
import com.jewer.bodycam.backend.functions.updateInstructionAlertDialogStatus
import com.jewer.bodycam.backend.functions.updateLastBackZoomRatio
import com.jewer.bodycam.backend.functions.updateLastFrontZoomRatio
import com.jewer.bodycam.backend.functions.vibrateOnce
import com.jewer.bodycam.backend.services.RadioService
import com.jewer.bodycam.backend.services.ScreenRecordService
import com.jewer.bodycam.frontend.nav.NAV
import com.jewer.bodycam.ui.theme.Black
import com.jewer.bodycam.ui.theme.DarkYellow
import com.jewer.bodycam.ui.theme.Red
import com.jewer.bodycam.ui.theme.White
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCamera2Interop::class, ExperimentalGetImage::class)
@Composable
fun CameraScreen(navController: NavHostController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val processLifecycleOwner = remember { ProcessLifecycleOwner.get() }

    val currentTime = remember { mutableStateOf(getCurrentTime()) }
    val currentMode by orientationFlow.collectAsStateWithLifecycle()
    val isPortrait = currentMode == 1

    LaunchedEffect(Unit) {
        while (isActive) {
            currentTime.value = getCurrentTime()
            delay(1000.milliseconds)
        }
    }

    val userName = getUserName(context)

    // 設定值快取
    val vibrateApproved = remember { getVibrateStatus(context) }
    val beepSoundApproved = remember { getBeepSoundStatus(context) }
    val instructionAlertDialogApproved = remember { getInstructionAlertDialogStatus(context) }
    val isLowBrightnessApproved = remember { getLowBrightnessStatus(context) }
    val isFlashlightApproved = remember { getFlashlightStatus(context) }

    var isBodyDetectionApproved by remember { mutableStateOf(getBodyDetectionStatus(context)) }
    var isSimulatedWideAngleApproved by remember { mutableStateOf(getSimulatedWideAngleStatus(context)) }
    var fisheyeK by remember { mutableFloatStateOf(getFisheyeK(context)) }
    var fisheyeScale by remember { mutableFloatStateOf(getFisheyeScale(context)) }
    var selectedBackCameraIdSetting by remember { mutableStateOf(getSelectedBackCameraId(context)) }
    var selectedFrontCameraIdSetting by remember { mutableStateOf(getSelectedFrontCameraId(context)) }
    var selectedCameraFpsSetting by remember { mutableIntStateOf(getCameraFps(context)) }
    var selectedQualitySetting by remember { mutableStateOf(getVideoQuality(context)) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val textShadow = remember { Shadow(color = Black, offset = Offset(3f, 3f), blurRadius = 2f) }
    val consolasBold = remember { FontFamily(Font(R.font.consolas, FontWeight.Bold)) }

    var instructionAlertDialogIsVisible by remember { mutableStateOf(true) }

    val previewView: PreviewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    val isRecordingRunning by ScreenRecordService.isRecordingRunning.collectAsStateWithLifecycle()
    val isRadioRunning by RadioService.isRadioRunning.collectAsStateWithLifecycle()
    val radioEndpoints by RadioService.connectedEndpoints.collectAsStateWithLifecycle()

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var useUltraWide by remember { mutableStateOf(true) }
    val chosenBrand = remember { mutableStateOf(getBodycamBrand(context)) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // 依據設定選擇畫質 (SD: 480p [預設, 4:3], HD: 720p [16:9], FHD: 1080p [16:9], UHD: 4K [16:9])
    val targetQuality = when (selectedQualitySetting) {
        "UHD" -> Quality.UHD
        "FHD" -> Quality.FHD
        "HD" -> Quality.HD
        else -> Quality.SD
    }

    val recorder = remember(targetQuality) {
        Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    targetQuality,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()
    }
    val videoCapture = remember(recorder) {
        VideoCapture.withOutput(recorder).also {
            ScreenRecordService.setVideoCapture(it)
        }
    }

    // ── OpenGL 品牌浮水印與魚眼濾鏡 ──
    val surfaceProcessor = remember(context) {
        WideAngleSurfaceProcessor(
            context = context,
            isPortrait = isPortrait,
            isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT,
            fisheyeK = fisheyeK,
            fisheyeScale = fisheyeScale,
            isFisheyeEnabled = isSimulatedWideAngleApproved,
            brand = chosenBrand.value ?: "AXON",
            userName = userName,
            isRecording = isRecordingRunning
        )
    }

    LaunchedEffect(surfaceProcessor, isPortrait, lensFacing, fisheyeK, fisheyeScale, isSimulatedWideAngleApproved, chosenBrand.value, userName, isRecordingRunning) {
        surfaceProcessor.updateParams(
            isPortrait = isPortrait,
            isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT,
            fisheyeK = fisheyeK,
            fisheyeScale = fisheyeScale,
            isFisheyeEnabled = isSimulatedWideAngleApproved,
            brand = chosenBrand.value ?: "AXON",
            userName = userName,
            isRecording = isRecordingRunning
        )
    }

    val wideAngleEffect = remember(surfaceProcessor) {
        CustomCameraEffect(
            CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
            cameraExecutor,
            surfaceProcessor
        ) { Log.e("WideAngle", "Effect error", it) }
    }

    // ── 人體辨識相關 ──
    var detectedPoseBoundingBox by remember { mutableStateOf<RectF?>(null) }
    var frameWidth by remember { mutableIntStateOf(0) }
    var frameHeight by remember { mutableIntStateOf(0) }

    LaunchedEffect(surfaceProcessor, detectedPoseBoundingBox, frameWidth, frameHeight) {
        surfaceProcessor.updatePoseBox(detectedPoseBoundingBox, frameWidth, frameHeight)
    }

    val poseDetector = remember {
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    fun toggleRadio() {
        if (isRadioRunning) {
            val intent = Intent(context, RadioService::class.java).apply { action = RadioService.ACTION_STOP }
            context.startService(intent)
        } else {
            val intent = Intent(context, RadioService::class.java).apply { action = RadioService.ACTION_START }
            context.startForegroundService(intent)
        }
    }

    LaunchedEffect(Unit) {
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e("CameraScreen", "Failed to get CameraProvider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    val cameraSelector = remember(lensFacing, selectedBackCameraIdSetting, selectedFrontCameraIdSetting) {
        CameraSelector.Builder().addCameraFilter { cameraInfos ->
            val selectedId = if (lensFacing == CameraSelector.LENS_FACING_BACK) selectedBackCameraIdSetting else selectedFrontCameraIdSetting
            if (selectedId.isNotEmpty()) {
                val match = cameraInfos.find { Camera2CameraInfo.from(it).cameraId == selectedId }
                if (match != null) return@addCameraFilter listOf(match)
            }
            cameraInfos.filter { it.lensFacing == lensFacing }
        }.build()
    }

    DisposableEffect(surfaceProcessor) {
        if (isLowBrightnessApproved) setScreenBrightness(context, true)
        onDispose {
            setScreenBrightness(context, false)
            poseDetector.close()
            analysisExecutor.shutdown()
            if (!ScreenRecordService.isRecordingRunning.value) {
                cameraExecutor.shutdown()
                surfaceProcessor.release()
            }
        }
    }

    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect {
            chosenBrand.value = getBodycamBrand(context)
            isBodyDetectionApproved = getBodyDetectionStatus(context)
            isSimulatedWideAngleApproved = getSimulatedWideAngleStatus(context)
            fisheyeK = getFisheyeK(context)
            fisheyeScale = getFisheyeScale(context)
            selectedBackCameraIdSetting = getSelectedBackCameraId(context)
            selectedFrontCameraIdSetting = getSelectedFrontCameraId(context)
            selectedCameraFpsSetting = getCameraFps(context)
            selectedQualitySetting = getVideoQuality(context)
        }
    }

    // 將 CameraX 生命週期綁定至 ProcessLifecycleOwner，配合前台服務確保在背景與關閉螢幕時相機與 OpenGL 錄影持續運作
    LaunchedEffect(cameraProvider, cameraSelector, isBodyDetectionApproved, processLifecycleOwner, selectedBackCameraIdSetting, selectedFrontCameraIdSetting, selectedCameraFpsSetting, selectedQualitySetting) {
        val provider = cameraProvider ?: return@LaunchedEffect
        // 錄影中不調用 unbindAll()，防止錄影中途因狀態刷新造成錄影中斷
        if (isRecordingRunning) return@LaunchedEffect
        try {
            delay(200.milliseconds)
            val previewBuilder = Preview.Builder()
            if (selectedCameraFpsSetting > 0) {
                val camera2Extender = Camera2Interop.Extender(previewBuilder)
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(selectedCameraFpsSetting, selectedCameraFpsSetting)
                )
            }
            val preview = previewBuilder.build()
            preview.surfaceProvider = previewView.surfaceProvider
            provider.unbindAll()

            val useCaseGroupBuilder = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(videoCapture)
                .addEffect(wideAngleEffect)

            if (isBodyDetectionApproved) {
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    if (lifecycleOwner.lifecycle.currentState < Lifecycle.State.STARTED) {
                        imageProxy.close(); return@setAnalyzer
                    }
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        try {
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val image = InputImage.fromMediaImage(mediaImage, rotation)
                            if (rotation == 90 || rotation == 270) { frameWidth = imageProxy.height; frameHeight = imageProxy.width }
                            else { frameWidth = imageProxy.width; frameHeight = imageProxy.height }

                            poseDetector.process(image)
                                .addOnSuccessListener { pose ->
                                    if (lifecycleOwner.lifecycle.currentState < Lifecycle.State.STARTED) return@addOnSuccessListener
                                    val landmarks = pose.allPoseLandmarks
                                    val highConfidenceLandmarks = landmarks.filter { it.inFrameLikelihood > 0.99f }
                                    if (highConfidenceLandmarks.size >= 5) {
                                        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
                                        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
                                        for (landmark in highConfidenceLandmarks) {
                                            val position = landmark.position
                                            if (position.x < minX) minX = position.x
                                            if (position.x > maxX) maxX = position.x
                                            if (position.y < minY) minY = position.y
                                            if (position.y > maxY) maxY = position.y
                                        }
                                        detectedPoseBoundingBox = RectF(minX - 30f, minY - 30f, maxX + 30f, maxY + 30f)
                                    } else { detectedPoseBoundingBox = null }
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        } catch (_: Exception) { imageProxy.close() }
                    } else { imageProxy.close() }
                }
                useCaseGroupBuilder.addUseCase(imageAnalysis)
            }

            val camera = provider.bindToLifecycle(processLifecycleOwner, cameraSelector, useCaseGroupBuilder.build())
            activeCamera = camera
            if (isFlashlightApproved) camera.cameraControl.enableTorch(true)
        } catch (e: Exception) {
            Log.e("CameraPreview", "Error initializing camera", e)
        }
    }

    LaunchedEffect(activeCamera, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                activeCamera?.let { camera ->
                    val lastRatio = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                        getLastBackZoomRatio(context) else getLastFrontZoomRatio(context)
                    camera.cameraInfo.zoomState.value?.let { state ->
                        val targetRatio = if (lastRatio > 0 && lastRatio >= state.minZoomRatio && lastRatio <= state.maxZoomRatio) {
                            lastRatio
                        } else {
                            state.minZoomRatio
                        }
                        camera.cameraControl.setZoomRatio(targetRatio)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    activeCamera?.let { camera ->
                        val currentZoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                        val newZoom = (currentZoom * zoom).coerceIn(
                            camera.cameraInfo.zoomState.value?.minZoomRatio ?: 1f,
                            camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 10f
                        )
                        camera.cameraControl.setZoomRatio(newZoom)
                        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            updateLastBackZoomRatio(context, newZoom)
                        } else {
                            updateLastFrontZoomRatio(context, newZoom)
                        }
                    }
                }
            }
    ) {
        // 置中相機預覽畫面
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        if (isBodyDetectionApproved) {
            val currentPose = detectedPoseBoundingBox
            if (currentPose != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scaleX = size.width / frameWidth.toFloat()
                    val scaleY = size.height / frameHeight.toFloat()
                    val left = currentPose.left * scaleX
                    val top = currentPose.top * scaleY
                    val right = currentPose.right * scaleX
                    val bottom = currentPose.bottom * scaleY
                    drawRect(
                        color = DarkYellow,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }

        // ── 工具列（垂直模式在下方中間，水平模式在右側中間） ──
        Box(
            modifier = if (isPortrait) {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 20.dp, start = 16.dp, end = 16.dp)
            } else {
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp, top = 16.dp, bottom = 16.dp)
            }
        ) {
            if (isPortrait) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PortraitToolbarButtons(
                        isRecordingRunning = isRecordingRunning,
                        isRadioRunning = isRadioRunning,
                        radioEndpoints = radioEndpoints,
                        beepSoundApproved = beepSoundApproved,
                        vibrateApproved = vibrateApproved,
                        consolasBold = consolasBold,
                        textShadow = textShadow,
                        navController = navController,
                        lensFacing = lensFacing,
                        onCameraSwitch = { nextLens ->
                            lensFacing = nextLens
                            useUltraWide = (nextLens == CameraSelector.LENS_FACING_BACK)
                        },
                        toggleRadio = { toggleRadio() }
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HorizontalToolbarButtons(
                        isRecordingRunning = isRecordingRunning,
                        isRadioRunning = isRadioRunning,
                        radioEndpoints = radioEndpoints,
                        beepSoundApproved = beepSoundApproved,
                        vibrateApproved = vibrateApproved,
                        consolasBold = consolasBold,
                        textShadow = textShadow,
                        navController = navController,
                        lensFacing = lensFacing,
                        onCameraSwitch = { nextLens ->
                            lensFacing = nextLens
                            useUltraWide = (nextLens == CameraSelector.LENS_FACING_BACK)
                        },
                        toggleRadio = { toggleRadio() }
                    )
                }
            }
        }
    }

    if (instructionAlertDialogApproved && instructionAlertDialogIsVisible) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(text = "Instruction", color = White) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(
                            text =  "●  Tap the record button on toolbar to start/stop recording.\n\n" +
                                    "●  Record result will be stored in “Bodycam” folder in device media store space.\n\n" +
                                    "●  Toolbar buttons (Record, Settings, Switch Camera, Video Gallery, Radio) stay fixed on screen.\n\n" +
                                    "●  If you want to use radio system, push the radio button on all of your devices then wait for connection.\n\n" +
                                    "●  You can change the orientation of your device in settings.\n\n" +
                                    "●  You can manually select the camera lens in settings.\n\n" +
                                    "●  Pinch the screen to zoom in/out the camera.\n\n" +
                                    "●  User name can be changed.",
                            color = White
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { instructionAlertDialogIsVisible = false }) { Text(color = DarkYellow, text = "close") } },
            dismissButton = { TextButton(onClick = { instructionAlertDialogIsVisible = false; updateInstructionAlertDialogStatus(context, false) }) { Text(color = DarkYellow, text = "close permanently") } }
        )
    }
}

@Composable
private fun HorizontalToolbarButtons(
    isRecordingRunning: Boolean,
    isRadioRunning: Boolean,
    radioEndpoints: Set<String>,
    beepSoundApproved: Boolean,
    vibrateApproved: Boolean,
    consolasBold: FontFamily,
    textShadow: Shadow,
    navController: NavHostController,
    lensFacing: Int,
    onCameraSwitch: (Int) -> Unit,
    toggleRadio: () -> Unit
) {
    val context = LocalContext.current

    // 1. 設定按鈕
    IconButton(
        modifier = Modifier.size(50.dp),
        onClick = {
            navController.navigate(NAV.SETTING)
            if (beepSoundApproved) playSoundAtMaxVolume(context, R.raw.buttontouchedsound)
            if (vibrateApproved) vibrateOnce(context, 1000)
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings_foreground),
            tint = White,
            contentDescription = "Settings",
            modifier = Modifier.size(50.dp)
        )
    }

    // 2. 鏡頭切換按鈕
    IconButton(
        modifier = Modifier.size(50.dp),
        onClick = {
            val nextLens = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            onCameraSwitch(nextLens)
            if (beepSoundApproved) playSoundAtMaxVolume(context, R.raw.buttontouchedsound)
            if (vibrateApproved) vibrateOnce(context, 1000)
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_camera_switch_foreground),
            tint = White,
            contentDescription = "Switch Camera",
            modifier = Modifier.size(50.dp)
        )
    }

    // 3. 錄影控制按鈕
    IconButton(
        modifier = Modifier.size(90.dp),
        onClick = {
            if (isRecordingRunning) {
                Intent(context.applicationContext, ScreenRecordService::class.java).also {
                    it.action = ScreenRecordService.STOP_RECORDING
                    context.startService(it)
                }
                if (beepSoundApproved) playSoundAtMaxVolume(context, R.raw.axonstoprecordsound)
                if (vibrateApproved) vibrateOnce(context, 1000)
            } else {
                Intent(context.applicationContext, ScreenRecordService::class.java).also {
                    it.action = ScreenRecordService.START_RECORDING
                    context.startForegroundService(it)
                }
            }
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_start_record_buttom_foreground),
            tint = if (isRecordingRunning) Red else DarkYellow,
            contentDescription = "Toggle Recording",
            modifier = Modifier.size(90.dp)
        )
    }

    // 4. 媒體庫按鈕
    IconButton(
        modifier = Modifier.size(50.dp),
        onClick = {
            navController.navigate(NAV.VIDEO)
            if (beepSoundApproved) playSoundAtMaxVolume(context, R.raw.buttontouchedsound)
            if (vibrateApproved) vibrateOnce(context, 1000)
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_gallery_foreground),
            tint = White,
            contentDescription = "Video Library",
            modifier = Modifier.size(50.dp)
        )
    }

    // 5. 對講機按鈕
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (isRadioRunning && radioEndpoints.isNotEmpty()) {
            Text(
                text = "Online: ${radioEndpoints.size + 1}",
                color = DarkYellow,
                fontSize = 12.sp,
                fontFamily = consolasBold,
                style = MaterialTheme.typography.bodySmall.copy(shadow = textShadow)
            )
        }
        IconButton(
            modifier = Modifier.size(40.dp),
            onClick = { toggleRadio() }
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_radio_foreground),
                tint = if (isRadioRunning) DarkYellow else White,
                contentDescription = "Radio",
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
private fun PortraitToolbarButtons(
    isRecordingRunning: Boolean,
    isRadioRunning: Boolean,
    radioEndpoints: Set<String>,
    beepSoundApproved: Boolean,
    vibrateApproved: Boolean,
    consolasBold: FontFamily,
    textShadow: Shadow,
    navController: NavHostController,
    lensFacing: Int,
    onCameraSwitch: (Int) -> Unit,
    toggleRadio: () -> Unit
) {
    val context = LocalContext.current

    // 1. 對講機按鈕
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (isRadioRunning && radioEndpoints.isNotEmpty()) {
            Text(
                text = "Online: ${radioEndpoints.size + 1}",
                color = DarkYellow,
                fontSize = 12.sp,
                fontFamily = consolasBold,
                style = MaterialTheme.typography.bodySmall.copy(shadow = textShadow)
            )
        }
        IconButton(
            modifier = Modifier.size(40.dp),
            onClick = { toggleRadio() }
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_radio_foreground),
                tint = if (isRadioRunning) DarkYellow else White,
                contentDescription = "Radio",
                modifier = Modifier.size(40.dp)
            )
        }
    }

    // 2. 媒體庫按鈕
    IconButton(
        modifier = Modifier.size(50.dp),
        onClick = {
            navController.navigate(NAV.VIDEO)
            if (beepSoundApproved) playSoundAtMaxVolume(context, R.raw.buttontouchedsound)
            if (vibrateApproved) vibrateOnce(context, 1000)
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_gallery_foreground),
            tint = White,
            contentDescription = "Video Library",
            modifier = Modifier.size(50.dp)
        )
    }

    // 3. 錄影控制按鈕
    IconButton(
        modifier = Modifier.size(90.dp),
        onClick = {
            if (isRecordingRunning) {
                Intent(context.applicationContext, ScreenRecordService::class.java).also {
                    it.action = ScreenRecordService.STOP_RECORDING
                    context.startService(it)
                }
                if (beepSoundApproved) playSoundAtMaxVolume(context, R.raw.axonstoprecordsound)
                if (vibrateApproved) vibrateOnce(context, 1000)
            } else {
                Intent(context.applicationContext, ScreenRecordService::class.java).also {
                    it.action = ScreenRecordService.START_RECORDING
                    context.startForegroundService(it)
                }
            }
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_start_record_buttom_foreground),
            tint = if (isRecordingRunning) Red else DarkYellow,
            contentDescription = "Toggle Recording",
            modifier = Modifier.size(90.dp)
        )
    }

    // 4. 鏡頭切換按鈕
    IconButton(
        modifier = Modifier.size(50.dp),
        onClick = {
            val nextLens = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            onCameraSwitch(nextLens)
            if (beepSoundApproved) playSoundAtMaxVolume(context, R.raw.buttontouchedsound)
            if (vibrateApproved) vibrateOnce(context, 1000)
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_camera_switch_foreground),
            tint = White,
            contentDescription = "Switch Camera",
            modifier = Modifier.size(50.dp)
        )
    }

    // 5. 設定按鈕
    IconButton(
        modifier = Modifier.size(50.dp),
        onClick = {
            navController.navigate(NAV.SETTING)
            if (beepSoundApproved) playSoundAtMaxVolume(context, R.raw.buttontouchedsound)
            if (vibrateApproved) vibrateOnce(context, 1000)
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings_foreground),
            tint = White,
            contentDescription = "Settings",
            modifier = Modifier.size(50.dp)
        )
    }
}
