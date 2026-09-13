package com.jewer.bodycam.backend.functions

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Locale

// 獲取裝置名稱
fun getPhoneName(): String {
    return "${Build.MODEL} ${Build.ID}"
}

// 獲取現在時間
fun getCurrentTime(): String {
    val timeFormat = "yyyy-MM-dd HH:mm:ss"
    return SimpleDateFormat(timeFormat, Locale.US).format(System.currentTimeMillis())
}

// 獲取現在電量
fun getCurrentBatteryLevel(context: Context): Int {
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}

// 震動秒數控制
fun vibrateOnce(context: Context, duration: Long) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
}

// 全螢幕控制 (隱藏狀態列與導航列)
fun setFullScreen(context: Context, isFullScreen: Boolean) {
    val activity = context as? Activity ?: return
    val window = activity.window
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    if (isFullScreen) {
        // 隱藏系統欄 (狀態列與導航列)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // 設定為滑動後暫時顯示，一段時間後自動隱藏 (沉浸模式)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        // 顯示系統欄
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

// 螢幕亮度控制 (最低亮度模式)
fun setScreenBrightness(context: Context, isLow: Boolean) {
    val activity = context as? Activity ?: return
    val layoutParams = activity.window.attributes
    // 0.01f 是最低亮度，BRIGHTNESS_OVERRIDE_NONE (-1.0f) 表示恢復系統自動調整
    layoutParams.screenBrightness = if (isLow) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    activity.window.attributes = layoutParams
}

// 撥放音檔及音量控制
fun playSound(context: Context, resourceId: Int) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 1. 記錄當前的媒體音量
    val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    // 2. 獲取使用者設定的音量百分比 (Low: 30, Medium: 60, High: 100)
    val userVolumePercent = getBeepVolume(context)
    
    // 3. 獲取系統最大音量並計算目標音量
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val targetVolume = (maxVolume * (userVolumePercent / 100f)).toInt()
    
    audioManager.setStreamVolume(
        AudioManager.STREAM_MUSIC,
        targetVolume,
        0
    )

    // 4. 使用一個單獨的引用來防止被過早回收
    val mediaPlayer = MediaPlayer.create(context, resourceId)

    mediaPlayer?.let { mp ->
        mp.setOnCompletionListener {
            // 稍微延遲一點再切回音量，確保尾音播放完整
            postDelayed {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                it.release()
            }
        }
        mp.setOnErrorListener { _, _, _ ->
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            mp.release()
            true 
        }
        mp.start()
    } ?: run {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
    }
}

// 輔助擴展函式，用於延遲執行
private fun postDelayed(action: () -> Unit) {
    Handler(Looper.getMainLooper()).postDelayed(action, 300L)
}