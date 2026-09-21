package com.jewer.bodycam.backend.functions

import android.content.Context
import androidx.core.content.edit
import com.jewer.bodycam.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**********************************************************************************************************/

// 用於即時監聽方向變更的 Flow
private val mutableOrientationFlow = MutableStateFlow(0)
val orientationFlow = mutableOrientationFlow.asStateFlow()

// 初始化 Flow (在 App 啟動時呼叫一次)
fun initSettings(context: Context) {
    mutableOrientationFlow.value = getOrientationMode(context)
}

// 重新命名使用者名稱
fun updateUserName(context: Context, newUserName: String) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putString("userName", newUserName)
    }
}

// 讀取使用者名稱
fun getUserName(context: Context): String {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getString("userName", "User") ?: ""
}

/**********************************************************************************************************/

/**********************************************************************************************************/

// 更新震動布林狀態
fun updateVibrateStatus(context: Context, isEnabled: Boolean) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putBoolean("vibrate", isEnabled)
    }
}

// 讀取震動布林狀態
fun getVibrateStatus(context: Context): Boolean {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getBoolean("vibrate", false)
}

/******************************************************************************************************************/

/**********************************************************************************************************/

// 更新嗶聲布林狀態
fun updateBeepSoundStatus(context: Context, isEnabled: Boolean) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putBoolean("beep", isEnabled)
    }
}

// 讀取嗶聲布林狀態
fun getBeepSoundStatus(context: Context): Boolean {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getBoolean("beep", false)
}

/******************************************************************************************************************/

/**********************************************************************************************************/

// 更新嗶聲/震動時間間隔
fun updateVibrateAndBeepTimeInterval(context: Context, timeInterval: Long) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putLong("timeInterval", timeInterval)
    }
}

// 讀取嗶聲/震動時間間隔
fun getVibrateAndBeepTimeInterval(context: Context): Long {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getLong("timeInterval", 120000L)
}

/******************************************************************************************************************/

/**********************************************************************************************************/

// 更新音量百分比 (0-100)
fun updateBeepVolume(context: Context, volume: Int) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putInt("beepVolume", volume)
    }
}

// 讀取音量百分比 (預設 30)
fun getBeepVolume(context: Context): Int {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getInt("beepVolume", 30)
}

// 更新錄影提示音種類 ("New" [buttontouchedsound] 或 "Old" [axonstartrecordsound])
fun updateRecordSoundType(context: Context, type: String) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putString("recordSoundType", type)
    }
}

// 讀取錄影提示音種類 (預設 "New")
fun getRecordSoundType(context: Context): String {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getString("recordSoundType", "New") ?: "New"
}

// 根據品牌與設定獲取開始/循環錄影提示音資源 ID
fun getStartRecordSoundRes(context: Context): Int {
    val brand = getBodycamBrand(context)
    if (brand == "MOTOROLA") {
        return R.raw.motorolastartrecordsound
    }
    val type = getRecordSoundType(context)
    return if (type == "Old") R.raw.axonstartrecordsound else R.raw.buttontouchedsound
}

// 根據品牌與設定獲取結束錄影提示音資源 ID (保持不變)
fun getStopRecordSoundRes(context: Context): Int {
    val brand = getBodycamBrand(context)
    return if (brand == "MOTOROLA") R.raw.motorolastoprecordsound else R.raw.axonstoprecordsound
}

/******************************************************************************************************************/

/**********************************************************************************************************/

// 更新說明書對話框顯示布林狀態
fun updateInstructionAlertDialogStatus(context: Context, isEnabled: Boolean) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putBoolean("instruction", isEnabled)
    }
}

// 讀取說明書對話框布林狀態
fun getInstructionAlertDialogStatus(context: Context): Boolean {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getBoolean("instruction", true)
}

/******************************************************************************************************************/

/**********************************************************************************************************/

// 更新密錄器品牌
fun updateBodycamBrand(context: Context, brand: String) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putString("brand", brand)
    }
}

// 讀取密錄器品牌
fun getBodycamBrand(context: Context): String? {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getString("brand", "AXON")
}

/******************************************************************************************************************/

/**********************************************************************************************************/

// 更新最低亮度螢幕布林狀態
fun updateLowBrightnessStatus(context: Context, isEnabled: Boolean) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putBoolean("lowBrightness", isEnabled)
    }
}

// 讀取最低亮度螢幕布林狀態
fun getLowBrightnessStatus(context: Context): Boolean {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getBoolean("lowBrightness", false)
}

// 更新靜音錄影模式布林狀態 (只錄影像沒有聲音)
fun updateSilentVideoStatus(context: Context, isEnabled: Boolean) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putBoolean("silentVideo", isEnabled)
    }
}

// 讀取靜音錄影模式布林狀態 (預設 false)
fun getSilentVideoStatus(context: Context): Boolean {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getBoolean("silentVideo", false)
}

// 更新錄影前幾秒靜音秒數設定 (0, 5, 10, 20, 30 秒)
fun updateMuteFirstSeconds(context: Context, seconds: Int) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putInt("muteFirstSeconds", seconds)
    }
}

// 讀取錄影前幾秒靜音秒數設定 (預設 999 即 Full Mute)
fun getMuteFirstSeconds(context: Context): Int {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getInt("muteFirstSeconds", 999)
}

/******************************************************************************************************************/

/**********************************************************************************************************/

// 更新手電筒布林狀態
fun updateFlashlightStatus(context: Context, isEnabled: Boolean) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putBoolean("flashlight", isEnabled)
    }
}

// 讀取手電筒布林狀態
fun getFlashlightStatus(context: Context): Boolean {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getBoolean("flashlight", false)
}

/******************************************************************************************************************/

/**********************************************************************************************************/

// 更新全螢幕相機預覽布林狀態
fun updateFullScreenPreviewStatus(context: Context, isEnabled: Boolean) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putBoolean("fullScreenPreview", isEnabled)
    }
}

// 讀取全螢幕相機預覽布林狀態 (預設 false: FIT_CENTER 原比例, true: FILL_CENTER 全螢幕)
fun getFullScreenPreviewStatus(context: Context): Boolean {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getBoolean("fullScreenPreview", false)
}

/******************************************************************************************************************/

/**********************************************************************************************************/

// 更新按鍵錄影布林狀態
fun updateKeyRecordingStatus(context: Context, isEnabled: Boolean) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putBoolean("keyRecording", isEnabled)
    }
}

// 讀取按鍵錄影布林狀態
fun getKeyRecordingStatus(context: Context): Boolean {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getBoolean("keyRecording", false)
}

// 更新按鍵操作方式 ("Default" 預設按一次, "Simulated" 仿真雙擊+/長按-)
fun updateKeyOperationMode(context: Context, mode: String) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putString("keyOperationMode", mode)
    }
}

// 讀取按鍵操作方式 (預設 "Default")
fun getKeyOperationMode(context: Context): String {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getString("keyOperationMode", "Default") ?: "Default"
}

/******************************************************************************************************************/



// 更新顯示方向模式 (0: 水平, 1: 垂直)
fun updateOrientationMode(context: Context, mode: Int) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putInt("orientationMode", mode)
    }
    mutableOrientationFlow.value = mode
}

// 讀取顯示方向模式 (預設 0: 水平)
fun getOrientationMode(context: Context): Int {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getInt("orientationMode", 0)
}

/**********************************************************************************************************/

// 更新模擬廣角模式
fun updateSimulatedWideAngleStatus(context: Context, isEnabled: Boolean) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putBoolean("simulatedWideAngle", isEnabled)
    }
}

// 讀取模擬廣角模式
fun getSimulatedWideAngleStatus(context: Context): Boolean {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getBoolean("simulatedWideAngle", false)
}

/******************************************************************************************************************/

// 更新選擇的後置相機 ID
fun updateSelectedBackCameraId(context: Context, cameraId: String) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putString("selectedBackCameraId", cameraId)
    }
}

// 讀取選擇的後置相機 ID
fun getSelectedBackCameraId(context: Context): String {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getString("selectedBackCameraId", "") ?: ""
}

// 更新選擇的前置相機 ID
fun updateSelectedFrontCameraId(context: Context, cameraId: String) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putString("selectedFrontCameraId", cameraId)
    }
}

// 讀取選擇的前置相機 ID
fun getSelectedFrontCameraId(context: Context): String {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getString("selectedFrontCameraId", "") ?: ""
}

/******************************************************************************************************************/

// 更新最後後置縮放倍率
fun updateLastBackZoomRatio(context: Context, ratio: Float) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putFloat("lastBackZoomRatio", ratio)
    }
}

// 讀取最後後置縮放倍率
fun getLastBackZoomRatio(context: Context): Float {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getFloat("lastBackZoomRatio", -1f)
}

// 更新最後前置縮放倍率
fun updateLastFrontZoomRatio(context: Context, ratio: Float) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putFloat("lastFrontZoomRatio", ratio)
    }
}

// 讀取最後前置縮放倍率
fun getLastFrontZoomRatio(context: Context): Float {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getFloat("lastFrontZoomRatio", -1f)
}

/******************************************************************************************************************/

// 更新魚眼 K 值
fun updateFisheyeK(context: Context, k: Float) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putFloat("fisheyeK", k)
    }
}

// 讀取魚眼 K 值 (預設 0.45)
fun getFisheyeK(context: Context): Float {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getFloat("fisheyeK", 0.23f)
}

// 更新魚眼縮放值 (原 distortedPos 係數)
fun updateFisheyeScale(context: Context, scale: Float) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putFloat("fisheyeScale", scale)
    }
}

// 讀取魚眼縮放值 (預設 0.6)
fun getFisheyeScale(context: Context): Float {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getFloat("fisheyeScale", 0.67f)
}

/******************************************************************************************************************/

// 更新相機影格率 (FPS)
fun updateCameraFps(context: Context, fps: Int) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putInt("cameraFps", fps)
    }
}

// 讀取相機影格率 (FPS, 預設 30)
fun getCameraFps(context: Context): Int {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getInt("cameraFps", 30)
}

/******************************************************************************************************************/

// 更新錄影畫質 ("SD", "HD", "FHD")
fun updateVideoQuality(context: Context, quality: String) {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        putString("videoQuality", quality)
    }
}

// 讀取錄影畫質 (預設 "SD")
fun getVideoQuality(context: Context): String {
    val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    return sharedPreferences.getString("videoQuality", "SD") ?: "SD"
}

/******************************************************************************************************************/
