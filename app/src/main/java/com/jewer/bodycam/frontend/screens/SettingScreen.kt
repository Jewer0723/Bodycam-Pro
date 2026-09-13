package com.jewer.bodycam.frontend.screens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import androidx.navigation.NavController
import com.jewer.bodycam.R
import com.jewer.bodycam.backend.functions.getBeepSoundStatus
import com.jewer.bodycam.backend.functions.getBeepVolume
import com.jewer.bodycam.backend.functions.getBodyDetectionStatus
import com.jewer.bodycam.backend.functions.getBodycamBrand
import com.jewer.bodycam.backend.functions.getCameraFps
import com.jewer.bodycam.backend.functions.getVideoQuality
import com.jewer.bodycam.backend.functions.getFisheyeK
import com.jewer.bodycam.backend.functions.getFisheyeScale
import com.jewer.bodycam.backend.functions.getFlashlightStatus
import com.jewer.bodycam.backend.functions.getKeyRecordingStatus
import com.jewer.bodycam.backend.functions.getLowBrightnessStatus
import com.jewer.bodycam.backend.functions.getOrientationMode
import com.jewer.bodycam.backend.functions.getSelectedBackCameraId
import com.jewer.bodycam.backend.functions.getSelectedFrontCameraId
import com.jewer.bodycam.backend.functions.getSimulatedWideAngleStatus
import com.jewer.bodycam.backend.functions.getUserName
import com.jewer.bodycam.backend.functions.getVibrateAndBeepTimeInterval
import com.jewer.bodycam.backend.functions.getVibrateStatus
import com.jewer.bodycam.backend.functions.playSoundAtMaxVolume
import com.jewer.bodycam.backend.functions.updateBeepSoundStatus
import com.jewer.bodycam.backend.functions.updateBeepVolume
import com.jewer.bodycam.backend.functions.updateBodyDetectionStatus
import com.jewer.bodycam.backend.functions.updateBodycamBrand
import com.jewer.bodycam.backend.functions.updateCameraFps
import com.jewer.bodycam.backend.functions.updateFisheyeK
import com.jewer.bodycam.backend.functions.updateFisheyeScale
import com.jewer.bodycam.backend.functions.updateFlashlightStatus
import com.jewer.bodycam.backend.functions.updateKeyRecordingStatus
import com.jewer.bodycam.backend.functions.updateLowBrightnessStatus
import com.jewer.bodycam.backend.functions.updateOrientationMode
import com.jewer.bodycam.backend.functions.updateSelectedBackCameraId
import com.jewer.bodycam.backend.functions.updateSelectedFrontCameraId
import com.jewer.bodycam.backend.functions.updateSimulatedWideAngleStatus
import com.jewer.bodycam.backend.functions.updateUserName
import com.jewer.bodycam.backend.functions.updateVibrateAndBeepTimeInterval
import com.jewer.bodycam.backend.functions.updateVibrateStatus
import com.jewer.bodycam.backend.functions.updateVideoQuality
import com.jewer.bodycam.backend.functions.vibrateOnce
import com.jewer.bodycam.frontend.nav.NAV
import com.jewer.bodycam.ui.theme.DarkYellow
import com.jewer.bodycam.ui.theme.Gray
import com.jewer.bodycam.ui.theme.Red
import com.jewer.bodycam.ui.theme.White
import java.util.Locale
import kotlin.math.roundToInt

data class CameraOption(val id: String, val displayName: String)

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun SettingScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val userName = remember { mutableStateOf(getUserName(context)) }
    val reName = remember { mutableStateOf(false) }
    val instructionAlertDialogIsVisible = remember { mutableStateOf(false) }
    var timeIntervalExpand by remember { mutableStateOf(false) }
    var fpsExpand by remember { mutableStateOf(false) }
    var qualityExpand by remember { mutableStateOf(false) }
    var brandExpand by remember { mutableStateOf(false) }
    var orientationExpand by remember { mutableStateOf(false) }
    var backCameraExpand by remember { mutableStateOf(false) }
    var frontCameraExpand by remember { mutableStateOf(false) }
    
    val isVibrateChecked = remember { mutableStateOf(getVibrateStatus(context)) }
    val isBeepSoundChecked = remember { mutableStateOf(getBeepSoundStatus(context)) }
    var beepVolume by remember { mutableIntStateOf(getBeepVolume(context)) }
    val isLowBrightnessChecked = remember { mutableStateOf(getLowBrightnessStatus(context)) }
    val isFlashlightChecked = remember { mutableStateOf(getFlashlightStatus(context)) }
    val isKeyRecordingChecked = remember { mutableStateOf(getKeyRecordingStatus(context)) }
    val isBodyDetectionChecked = remember { mutableStateOf(getBodyDetectionStatus(context)) }
    val isSimulatedWideAngleChecked = remember { mutableStateOf(getSimulatedWideAngleStatus(context)) }
    var fisheyeK by remember { mutableFloatStateOf(getFisheyeK(context)) }
    var fisheyeScale by remember { mutableFloatStateOf(getFisheyeScale(context)) }
    val chosenOrientationMode = remember { mutableIntStateOf(getOrientationMode(context)) }
    val chosenBrandState = remember { mutableStateOf(getBodycamBrand(context)) }

    // ── 相機選擇相關 ──
    var availableBackCameras by remember { mutableStateOf(emptyList<CameraOption>()) }
    var availableFrontCameras by remember { mutableStateOf(emptyList<CameraOption>()) }
    var selectedBackCameraId by remember { mutableStateOf(getSelectedBackCameraId(context)) }
    var selectedFrontCameraId by remember { mutableStateOf(getSelectedFrontCameraId(context)) }

    LaunchedEffect(Unit) {
        val cameraManager = context.getSystemService<CameraManager>() ?: return@LaunchedEffect
        val backCameras = mutableListOf<CameraOption>()
        val frontCameras = mutableListOf<CameraOption>()

        try {
            cameraManager.cameraIdList.forEach { logicalId ->
                val chars = cameraManager.getCameraCharacteristics(logicalId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                
                // 1. 先加入邏輯鏡頭本身
                addCameraOption(cameraManager, logicalId, facing, backCameras, frontCameras)

                // 2. 深度挖掘：獲取該邏輯鏡頭包含的所有物理鏡頭 (API 28+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val physicalIds = chars.physicalCameraIds
                    physicalIds.forEach { physicalId ->
                        // 避免重複加入
                        if (physicalId != logicalId) {
                            addCameraOption(cameraManager, physicalId, facing, backCameras, frontCameras, isPhysical = true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Settings", "Failed to list cameras", e)
        }

        availableBackCameras = backCameras
        availableFrontCameras = frontCameras

        // 初始化預設值
        if (selectedBackCameraId.isEmpty() && backCameras.isNotEmpty()) {
            selectedBackCameraId = backCameras.first().id
            updateSelectedBackCameraId(context, selectedBackCameraId)
        }
        if (selectedFrontCameraId.isEmpty() && frontCameras.isNotEmpty()) {
            selectedFrontCameraId = frontCameras.first().id
            updateSelectedFrontCameraId(context, selectedFrontCameraId)
        }
    }

    val playFeedback = {
        if (isBeepSoundChecked.value) playSoundAtMaxVolume(context, R.raw.settingsactivatedsound)
        if (isVibrateChecked.value) vibrateOnce(context, 500)
    }

    data class TimeInterval(val displayText: String, val intervalMillis: Long)
    val timeIntervalOptions = listOf(
        TimeInterval("0.5 min", 30000L),
        TimeInterval("1 min", 60000L),
        TimeInterval("1.5 min", 90000L),
        TimeInterval("2 min", 120000L),
        TimeInterval("3 min", 180000L),
        TimeInterval("5 min", 300000L),
        TimeInterval("10 min", 600000L),
        TimeInterval("30 min", 1800000L),
        TimeInterval("60 min", 3600000L)
    )
    val initialInterval = remember {
        timeIntervalOptions.find { it.intervalMillis == getVibrateAndBeepTimeInterval(context) }
            ?: timeIntervalOptions.find { it.intervalMillis == 120000L }!!
    }
    var chosenTimeInterval by remember { mutableStateOf(initialInterval) }

    data class FpsOption(val displayText: String, val fps: Int)
    val fpsOptions = listOf(
        FpsOption("Auto", 0),
        FpsOption("15 FPS", 15),
        FpsOption("24 FPS", 24),
        FpsOption("30 FPS", 30),
        FpsOption("60 FPS", 60)
    )
    val initialFpsOption = remember {
        fpsOptions.find { it.fps == getCameraFps(context) }
            ?: fpsOptions.find { it.fps == 30 }!!
    }
    var chosenFpsOption by remember { mutableStateOf(initialFpsOption) }

    data class QualityOption(val displayText: String, val code: String)
    val qualityOptions = listOf(
        QualityOption("SD (480p)", "SD"),
        QualityOption("HD (720p)", "HD"),
        QualityOption("FHD (1080p)", "FHD"),
        QualityOption("UHD (4K)", "UHD")
    )
    val initialQualityOption = remember {
        qualityOptions.find { it.code == getVideoQuality(context) }
            ?: qualityOptions.find { it.code == "SD" }!!
    }
    var chosenQualityOption by remember { mutableStateOf(initialQualityOption) }

    val bodycamBrands = listOf("AXON", "MOTOROLA", "TRANSCEND", "GETAC", "DOZOR", "PANASONIC")

    LaunchedEffect(userName.value, isVibrateChecked.value, isLowBrightnessChecked.value, isFlashlightChecked.value, isKeyRecordingChecked.value, isBodyDetectionChecked.value, chosenOrientationMode.intValue, isSimulatedWideAngleChecked.value, fisheyeK, fisheyeScale) {
        updateUserName(context, userName.value)
        updateVibrateStatus(context, isVibrateChecked.value)
        updateLowBrightnessStatus(context, isLowBrightnessChecked.value)
        updateFlashlightStatus(context, isFlashlightChecked.value)
        updateKeyRecordingStatus(context, isKeyRecordingChecked.value)
        updateBodyDetectionStatus(context, isBodyDetectionChecked.value)
        updateOrientationMode(context, chosenOrientationMode.intValue)
        updateSimulatedWideAngleStatus(context, isSimulatedWideAngleChecked.value)
        updateFisheyeK(context, fisheyeK)
        updateFisheyeScale(context, fisheyeScale)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 返回 + 使用者名稱列
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.navigate(NAV.CAMERA) }) {
                    Icon(painter = painterResource(R.drawable.ic_arrowback_foreground), contentDescription = "Back", modifier = Modifier.padding(end = 8.dp), tint = White)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = "Settings", color = White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                Text(text = userName.value, textAlign = TextAlign.End, modifier = Modifier.weight(1f), color = White)
                IconButton(onClick = { reName.value = true }) {
                    Icon(painter = painterResource(id = R.drawable.ic_modify_user_name_foreground), contentDescription = "rename", modifier = Modifier.padding(end = 8.dp), tint = DarkYellow)
                }
            }

            HorizontalDivider(thickness = 2.dp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
                    .verticalScroll(rememberScrollState()
                )
            ) {
                // 震動
                TextButton(onClick = {
                    isVibrateChecked.value = !isVibrateChecked.value
                    updateVibrateStatus(context, isVibrateChecked.value)
                    if (isVibrateChecked.value) playFeedback()
                }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Vibrate Effect", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Switch(checked = isVibrateChecked.value, onCheckedChange = {
                            isVibrateChecked.value = it
                            updateVibrateStatus(context, it)
                            if (isVibrateChecked.value) playFeedback()
                        },
                            colors = SwitchDefaults.colors(checkedThumbColor = White, uncheckedThumbColor = White, checkedTrackColor = DarkYellow, uncheckedTrackColor = Gray))
                    }
                }

                // 嗶聲
                TextButton(onClick = {
                    isBeepSoundChecked.value = !isBeepSoundChecked.value
                    updateBeepSoundStatus(context, isBeepSoundChecked.value)
                    if (isBeepSoundChecked.value) playFeedback()
                }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Beep Sound", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Switch(checked = isBeepSoundChecked.value, onCheckedChange = {
                            isBeepSoundChecked.value = it
                            updateBeepSoundStatus(context, it)
                            if (isBeepSoundChecked.value) playFeedback()
                        },
                            colors = SwitchDefaults.colors(checkedThumbColor = White, uncheckedThumbColor = White, checkedTrackColor = DarkYellow, uncheckedTrackColor = Gray))
                    }
                }

                // 嗶聲音量調整 (僅在開啟嗶聲時顯示)
                if (isBeepSoundChecked.value) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Beep Volume: ", color = White, fontSize = 14.sp)
                            Text(text = "$beepVolume%", color = DarkYellow, fontSize = 14.sp)
                        }
                        Slider(
                            value = beepVolume.toFloat(),
                            onValueChange = {
                                beepVolume = it.roundToInt()
                                updateBeepVolume(context, beepVolume)
                            },
                            valueRange = 0f..100f,
                            steps = 99,
                            colors = SliderDefaults.colors(thumbColor = DarkYellow, activeTrackColor = DarkYellow)
                        )
                    }
                }

                // 最低亮度
                TextButton(onClick = {
                    isLowBrightnessChecked.value = !isLowBrightnessChecked.value
                    updateLowBrightnessStatus(context, isLowBrightnessChecked.value)
                    if (isLowBrightnessChecked.value) playFeedback()
                }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Low Brightness", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Switch(checked = isLowBrightnessChecked.value, onCheckedChange = {
                            isLowBrightnessChecked.value = it
                            updateLowBrightnessStatus(context, it)
                            if (isLowBrightnessChecked.value) playFeedback()
                        },
                            colors = SwitchDefaults.colors(checkedThumbColor = White, uncheckedThumbColor = White, checkedTrackColor = DarkYellow, uncheckedTrackColor = Gray))
                    }
                }

                // 手電筒
                TextButton(onClick = {
                    isFlashlightChecked.value = !isFlashlightChecked.value
                    updateFlashlightStatus(context, isFlashlightChecked.value)
                    if (isFlashlightChecked.value) playFeedback()
                }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Flashlight", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Switch(checked = isFlashlightChecked.value, onCheckedChange = {
                            isFlashlightChecked.value = it
                            updateFlashlightStatus(context, it)
                            if (isFlashlightChecked.value) playFeedback()
                        },
                            colors = SwitchDefaults.colors(checkedThumbColor = White, uncheckedThumbColor = White, checkedTrackColor = DarkYellow, uncheckedTrackColor = Gray))
                    }
                }

                // 人體辨識
                TextButton(onClick = {
                    isBodyDetectionChecked.value = !isBodyDetectionChecked.value
                    updateBodyDetectionStatus(context, isBodyDetectionChecked.value)
                    if (isBodyDetectionChecked.value) playFeedback()
                }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Human Body Detection", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Switch(checked = isBodyDetectionChecked.value, onCheckedChange = {
                            isBodyDetectionChecked.value = it
                            updateBodyDetectionStatus(context, it)
                            if (isBodyDetectionChecked.value) playFeedback()
                        },
                            colors = SwitchDefaults.colors(checkedThumbColor = White, uncheckedThumbColor = White, checkedTrackColor = DarkYellow, uncheckedTrackColor = Gray))
                    }
                }

                // 模擬廣角 (魚眼濾鏡)
                TextButton(onClick = {
                    isSimulatedWideAngleChecked.value = !isSimulatedWideAngleChecked.value
                    updateSimulatedWideAngleStatus(context, isSimulatedWideAngleChecked.value)
                    if (isSimulatedWideAngleChecked.value) playFeedback()
                }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Fisheye Mode", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Switch(checked = isSimulatedWideAngleChecked.value, onCheckedChange = {
                            isSimulatedWideAngleChecked.value = it
                            updateSimulatedWideAngleStatus(context, it)
                            if (isSimulatedWideAngleChecked.value) playFeedback()
                        },
                            colors = SwitchDefaults.colors(checkedThumbColor = White, uncheckedThumbColor = White, checkedTrackColor = DarkYellow, uncheckedTrackColor = Gray))
                    }
                }

                // 魚眼 K 值調整 (僅在開啟魚眼時顯示)
                if (isSimulatedWideAngleChecked.value) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Fisheye Strength (K): ", color = White, fontSize = 14.sp)
                            Text(text = String.format(Locale.US, "%.2f", fisheyeK), color = DarkYellow, fontSize = 14.sp)
                        }
                        Slider(
                            value = fisheyeK,
                            onValueChange = { fisheyeK = it },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(thumbColor = DarkYellow, activeTrackColor = DarkYellow)
                        )
                    }

                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Fisheye Scale: ", color = White, fontSize = 14.sp)
                            Text(text = String.format(Locale.US, "%.2f", fisheyeScale), color = DarkYellow, fontSize = 14.sp)
                        }
                        Slider(
                            value = fisheyeScale,
                            onValueChange = { fisheyeScale = it },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(thumbColor = DarkYellow, activeTrackColor = DarkYellow)
                        )
                    }
                }

                // 音量鍵錄錄影
                TextButton(onClick = {
                    isKeyRecordingChecked.value = !isKeyRecordingChecked.value
                    updateKeyRecordingStatus(context, isKeyRecordingChecked.value)
                    if (isKeyRecordingChecked.value) playFeedback()
                }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Volume Keys Record (Up: Start / Down: Stop)", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Switch(checked = isKeyRecordingChecked.value, onCheckedChange = {
                            isKeyRecordingChecked.value = it
                            updateKeyRecordingStatus(context, it)
                            if (isKeyRecordingChecked.value) playFeedback()
                        },
                            colors = SwitchDefaults.colors(checkedThumbColor = White, uncheckedThumbColor = White, checkedTrackColor = DarkYellow, uncheckedTrackColor = Gray))
                    }
                }

                // 顯示方向模式選擇
                TextButton(onClick = { orientationExpand = !orientationExpand }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Display Orientation", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Text(text = if (chosenOrientationMode.intValue == 1) "Portrait" else "Landscape", color = DarkYellow)
                        DropdownMenu(expanded = orientationExpand, onDismissRequest = { orientationExpand = false }, modifier = Modifier.border(1.dp, White)) {
                            DropdownMenuItem(text = { Text(text = "Landscape", color = White) }, onClick = {
                                chosenOrientationMode.intValue = 0
                                updateOrientationMode(context, 0)
                                playFeedback()
                                orientationExpand = false
                            })
                            DropdownMenuItem(text = { Text(text = "Portrait", color = White) }, onClick = {
                                chosenOrientationMode.intValue = 1
                                updateOrientationMode(context, 1)
                                playFeedback()
                                orientationExpand = false
                            })
                        }
                    }
                }

                // 嗶聲/震動間隔
                TextButton(onClick = { timeIntervalExpand = !timeIntervalExpand }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Vibrate/Beep Sound Time Interval", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Text(text = chosenTimeInterval.displayText, color = DarkYellow)
                        DropdownMenu(expanded = timeIntervalExpand, onDismissRequest = { timeIntervalExpand = false }, modifier = Modifier.border(1.dp, White)) {
                            timeIntervalOptions.forEach { timeOption ->
                                DropdownMenuItem(text = { Text(text = timeOption.displayText, color = White) }, onClick = {
                                    chosenTimeInterval = timeOption
                                    updateVibrateAndBeepTimeInterval(context, timeOption.intervalMillis)
                                    playFeedback()
                                    timeIntervalExpand = false
                                })
                            }
                        }
                    }
                }

                // 相機影格率 (FPS) 選擇
                TextButton(onClick = { fpsExpand = !fpsExpand }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Camera Frame Rate (FPS)", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Text(text = chosenFpsOption.displayText, color = DarkYellow)
                        DropdownMenu(expanded = fpsExpand, onDismissRequest = { fpsExpand = false }, modifier = Modifier.border(1.dp, White)) {
                            fpsOptions.forEach { option ->
                                DropdownMenuItem(text = { Text(text = option.displayText, color = White) }, onClick = {
                                    chosenFpsOption = option
                                    updateCameraFps(context, option.fps)
                                    playFeedback()
                                    fpsExpand = false
                                })
                            }
                        }
                    }
                }

                // 錄影畫質選擇 (SD, HD, FHD, UHD, 預設 SD)
                TextButton(onClick = { qualityExpand = !qualityExpand }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Video Quality", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Text(text = chosenQualityOption.displayText, color = DarkYellow)
                        DropdownMenu(expanded = qualityExpand, onDismissRequest = { qualityExpand = false }, modifier = Modifier.border(1.dp, White)) {
                            qualityOptions.forEach { option ->
                                DropdownMenuItem(text = { Text(text = option.displayText, color = White) }, onClick = {
                                    chosenQualityOption = option
                                    updateVideoQuality(context, option.code)
                                    playFeedback()
                                    qualityExpand = false
                                })
                            }
                        }
                    }
                }



                // 前置相機選擇 (下拉選單方式)
                TextButton(onClick = { frontCameraExpand = !frontCameraExpand }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Front Camera", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        val currentLabel = availableFrontCameras.find { it.id == selectedFrontCameraId }?.displayName ?: "Default"
                        Text(text = currentLabel, color = DarkYellow)
                        DropdownMenu(expanded = frontCameraExpand, onDismissRequest = { frontCameraExpand = false }, modifier = Modifier.border(1.dp, White)) {
                            availableFrontCameras.forEach { camera ->
                                DropdownMenuItem(text = { Text(text = camera.displayName, color = White) }, onClick = {
                                    selectedFrontCameraId = camera.id
                                    updateSelectedFrontCameraId(context, camera.id)
                                    playFeedback()
                                    frontCameraExpand = false
                                })
                            }
                        }
                    }
                }

                // 後置相機選擇 (下拉選單方式)
                TextButton(onClick = { backCameraExpand = !backCameraExpand }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Back Camera", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        val currentLabel = availableBackCameras.find { it.id == selectedBackCameraId }?.displayName ?: "Default"
                        Text(text = currentLabel, color = DarkYellow)
                        DropdownMenu(expanded = backCameraExpand, onDismissRequest = { backCameraExpand = false }, modifier = Modifier.border(1.dp, White)) {
                            availableBackCameras.forEach { camera ->
                                DropdownMenuItem(text = { Text(text = camera.displayName, color = White) }, onClick = {
                                    selectedBackCameraId = camera.id
                                    updateSelectedBackCameraId(context, camera.id)
                                    playFeedback()
                                    backCameraExpand = false
                                })
                            }
                        }
                    }
                }

                // 品牌選擇 (下拉選單方式)
                TextButton(onClick = { brandExpand = !brandExpand }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Bodycam Brand", textAlign = TextAlign.Start, modifier = Modifier.weight(1f), color = White)
                        Text(text = chosenBrandState.value ?: "AXON", color = DarkYellow)
                        DropdownMenu(expanded = brandExpand, onDismissRequest = { brandExpand = false }, modifier = Modifier.border(1.dp, White)) {
                            bodycamBrands.forEach { brand ->
                                DropdownMenuItem(text = { Text(text = brand, color = White) }, onClick = {
                                    chosenBrandState.value = brand
                                    updateBodycamBrand(context, brand)
                                    playFeedback()
                                    brandExpand = false
                                })
                            }
                        }
                    }
                }

                // 說明書對話框開關
                TextButton(onClick = { instructionAlertDialogIsVisible.value = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(text = "Instruction", textAlign = TextAlign.Center, modifier = Modifier.weight(1f), color = Red)
                }
            }
        }
    }

    // ── 重新命名對話框 ────────────────────────────────────────────
    if (reName.value) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = "ReName User Name") },
            text = {
                OutlinedTextField(
                    value = userName.value,
                    onValueChange = { userName.value = it },
                    label = { Text("Input new user name") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done)
                )
            },
            confirmButton = {
                TextButton(onClick = { 
                    updateUserName(context, userName.value)
                    playFeedback()
                    reName.value = false 
                }) {
                    Text(text = "confirm")
                }
            }
        )
    }

    // 說明書對話框
    if (instructionAlertDialogIsVisible.value) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(text = "Instruction", color = White) },
            text = { Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        text =  "●  AXON : Tap top right \u201CAXON\u201D icon to start/stop recording.\n\n" +
                                "●  MOTOROLA : Tap top left \u201CMOTOROLA\u201D icon to start/stop recording.\n\n" +
                                "●  TRANSCEND : Tap bottom left \u201CTRANSCEND\u201D icon to start/stop recording.\n\n" +
                                "●  GETAC : Tap top left \u201CGETAC\u201D icon to start/stop recording.\n\n" +
                                "●  DOZOR : Tap top right \u201CDOZOR\u201D icon to start/stop recording.\n\n" +
                                "●  PANASONIC : Tap top right \u201CPANASONIC\u201D icon to start/stop recording.\n\n" +
                                "●  Record result will be stored in \u201CBodycam\u201D folder in device media store space.\n\n" +
                                "●  For android 14+ device, you can chose to record \u201CA single app\u201D or \u201CEntire screen\u201D.\n\n" +
                                "●  Tap the screen then \u201Csettings\u201D 、 \u201Cradio system\u201D 、\u201Ccamera change\u201D and \u201Cmedia storage\u201D buttom will show on the screen.\n\n" +
                                "●  If you want to use radio system, push the radio buttom on all of your devices then wait for connection, there will be online devices number on the top of the buttom when connected.\n\n" +
                                "●  You can change the orientation of your device in settings.\n\n" +
                                "●  You can manually select the camera lens in settings.\n\n" +
                                "●  Pinch the screen to zoom in/out the camera.\n\n" +
                                "●  User name can be changed.",
                        color = White
                    )
                }
            }
            },
            confirmButton = { TextButton(onClick = { instructionAlertDialogIsVisible.value = false }) { Text(color = DarkYellow, text = "close") } },
        )
    }
}

// 輔助函式：解析相機資訊並分類
private fun addCameraOption(
    manager: CameraManager, 
    id: String, 
    facing: Int?, 
    backList: MutableList<CameraOption>, 
    frontList: MutableList<CameraOption>,
    isPhysical: Boolean = false
) {
    try {
        val chars = manager.getCameraCharacteristics(id)
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLength = focalLengths?.firstOrNull() ?: 0f
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val aperture = apertures?.firstOrNull() ?: 0f
        
        val typeTag = if (isPhysical) "Phys" else "Log"
        val infoText = String.format(Locale.US, "[%s] %.0fmm f/%.1f (ID:%s)", typeTag, focalLength, aperture, id)

        val option = CameraOption(id, infoText)
        when (facing) {
            CameraCharacteristics.LENS_FACING_BACK -> if (backList.none { it.id == id }) backList.add(option)
            CameraCharacteristics.LENS_FACING_FRONT -> if (frontList.none { it.id == id }) frontList.add(option)
        }
    } catch (e: Exception) {
        Log.e("Settings", "Error parsing camera $id", e)
    }
}
