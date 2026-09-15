package com.jewer.bodycam.frontend.screens

import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.graphics.Shadow
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
import com.jewer.bodycam.R
import com.jewer.bodycam.backend.camera.CameraManager
import com.jewer.bodycam.backend.functions.getBeepSoundStatus
import com.jewer.bodycam.backend.functions.getBodycamBrand
import com.jewer.bodycam.backend.functions.getCameraFps
import com.jewer.bodycam.backend.functions.getCurrentTime
import com.jewer.bodycam.backend.functions.getFisheyeK
import com.jewer.bodycam.backend.functions.getFisheyeScale
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
import com.jewer.bodycam.backend.functions.playSound
import com.jewer.bodycam.backend.functions.setScreenBrightness
import com.jewer.bodycam.backend.functions.updateInstructionAlertDialogStatus
import com.jewer.bodycam.backend.functions.updateLastBackZoomRatio
import com.jewer.bodycam.backend.functions.updateLastFrontZoomRatio
import com.jewer.bodycam.backend.functions.vibrateOnce
import com.jewer.bodycam.backend.services.RadioService
import com.jewer.bodycam.backend.services.RecordService
import com.jewer.bodycam.frontend.nav.NAV
import com.jewer.bodycam.ui.theme.Black
import com.jewer.bodycam.ui.theme.DarkYellow
import com.jewer.bodycam.ui.theme.Red
import com.jewer.bodycam.ui.theme.White
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    val isRecordingRunning by RecordService.isRecordingRunning.collectAsStateWithLifecycle()
    val isRadioRunning by RadioService.isRadioRunning.collectAsStateWithLifecycle()
    val radioEndpoints by RadioService.connectedEndpoints.collectAsStateWithLifecycle()

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var useUltraWide by remember { mutableStateOf(true) }
    val chosenBrand = remember { mutableStateOf(getBodycamBrand(context)) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }

    // 依據設定選擇畫質 (SD: 480p [預設, 4:3], HD: 720p [16:9], FHD: 1080p [16:9])
    remember(selectedQualitySetting) {
        CameraManager.getOrCreateVideoCapture(selectedQualitySetting)
    }

    // ── OpenGL 品牌浮水印與魚眼濾鏡 ──
    val surfaceProcessor = remember(context) {
        CameraManager.getOrCreateSurfaceProcessor(
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

    DisposableEffect(previewView) {
        if (isLowBrightnessApproved) setScreenBrightness(context, true)
        CameraManager.attachPreviewSurface(previewView)
        onDispose {
            setScreenBrightness(context, false)
            CameraManager.detachPreviewSurface()
        }
    }

    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect {
            chosenBrand.value = getBodycamBrand(context)
            isSimulatedWideAngleApproved = getSimulatedWideAngleStatus(context)
            fisheyeK = getFisheyeK(context)
            fisheyeScale = getFisheyeScale(context)
            selectedBackCameraIdSetting = getSelectedBackCameraId(context)
            selectedFrontCameraIdSetting = getSelectedFrontCameraId(context)
            selectedCameraFpsSetting = getCameraFps(context)
            selectedQualitySetting = getVideoQuality(context)
        }
    }

    // 當前台服務未錄影時將 CameraX 生命週期綁定至 ProcessLifecycleOwner，錄影時則由 RecordService 保持前景監聽
    LaunchedEffect(cameraProvider, cameraSelector, selectedBackCameraIdSetting, selectedFrontCameraIdSetting, selectedCameraFpsSetting, selectedQualitySetting, isRecordingRunning) {
        val provider = cameraProvider ?: return@LaunchedEffect
        if (isRecordingRunning) {
            CameraManager.attachPreviewSurface(previewView)
            activeCamera = CameraManager.activeCamera
            return@LaunchedEffect
        }

        try {
            delay(200.milliseconds)
            val camera = CameraManager.bindCamera(
                context = context,
                cameraProvider = provider,
                lifecycleOwner = processLifecycleOwner,
                cameraSelector = cameraSelector,
                previewView = previewView,
                fps = selectedCameraFpsSetting,
                selectedQuality = selectedQualitySetting
            )
            activeCamera = camera
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

    val handleCameraSwitch: (Int) -> Unit = { nextLens ->
        if (isRecordingRunning) {
            val intent = Intent(context, RecordService::class.java).apply {
                action = RecordService.STOP_RECORDING
            }
            context.startService(intent)
        }
        lensFacing = nextLens
        useUltraWide = (nextLens == CameraSelector.LENS_FACING_BACK)
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
                        onCameraSwitch = handleCameraSwitch,
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
                        onCameraSwitch = handleCameraSwitch,
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
            if (beepSoundApproved) playSound(context, R.raw.buttontouchedsound)
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
            if (beepSoundApproved) playSound(context, R.raw.buttontouchedsound)
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
                Intent(context.applicationContext, RecordService::class.java).also {
                    it.action = RecordService.STOP_RECORDING
                    context.startService(it)
                }
                if (vibrateApproved) vibrateOnce(context, 1000)
            } else {
                Intent(context.applicationContext, RecordService::class.java).also {
                    it.action = RecordService.START_RECORDING
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
            if (beepSoundApproved) playSound(context, R.raw.buttontouchedsound)
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
            if (beepSoundApproved) playSound(context, R.raw.buttontouchedsound)
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
                Intent(context.applicationContext, RecordService::class.java).also {
                    it.action = RecordService.STOP_RECORDING
                    context.startService(it)
                }
                if (vibrateApproved) vibrateOnce(context, 1000)
            } else {
                Intent(context.applicationContext, RecordService::class.java).also {
                    it.action = RecordService.START_RECORDING
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
            if (beepSoundApproved) playSound(context, R.raw.buttontouchedsound)
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
            if (beepSoundApproved) playSound(context, R.raw.buttontouchedsound)
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
