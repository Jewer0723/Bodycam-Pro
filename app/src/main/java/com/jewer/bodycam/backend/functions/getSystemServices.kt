package com.jewer.bodycam.backend.functions

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jewer.bodycam.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator ?: @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
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

// SoundPool 緩存與低延遲零卡頓音效播放器
object SoundPoolManager {
    private var soundPool: SoundPool? = null
    private val soundMap = ConcurrentHashMap<Int, Int>()
    private val loadedSet = ConcurrentHashMap.newKeySet<Int>()
    private val soundExecutor = Executors.newSingleThreadExecutor()

    private fun getOrCreateSoundPool(): SoundPool {
        return soundPool ?: synchronized(this) {
            soundPool ?: run {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                SoundPool.Builder()
                    .setMaxStreams(10)
                    .setAudioAttributes(attributes)
                    .build().also { sp ->
                        sp.setOnLoadCompleteListener { _, sampleId, status ->
                            if (status == 0) {
                                loadedSet.add(sampleId)
                            }
                        }
                        soundPool = sp
                    }
            }
        }
    }

    fun play(context: Context, resourceId: Int) {
        val appContext = context.applicationContext
        soundExecutor.execute {
            try {
                val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val userVolumePercent = getBeepVolume(appContext)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetVol = (maxVol * (userVolumePercent / 100f)).toInt().coerceIn(1, maxVol)

                // 在專屬背景執行緒同步媒體系統音量，絕不阻塞主線程 Binder IPC (避免 AudioService checkDispatchVolumeKeyEvent ANR)
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                } catch (e: Exception) {
                    Log.e("SoundPoolManager", "Error adjusting stream volume", e)
                }

                val sp = getOrCreateSoundPool()
                val sampleId = soundMap.computeIfAbsent(resourceId) { resId ->
                    sp.load(appContext, resId, 1)
                }

                val volumeFraction = (userVolumePercent / 100f).coerceIn(0.05f, 1.0f)

                var streamId = 0
                if (loadedSet.contains(sampleId)) {
                    streamId = sp.play(sampleId, volumeFraction, volumeFraction, 1, 0, 1.0f)
                }

                // 若 SoundPool 尚未加載完或播送失敗，立即調用 MediaPlayer 備援保障播送
                if (streamId == 0) {
                    playMediaPlayerFallback(appContext, resourceId, volumeFraction)
                }
            } catch (e: Exception) {
                Log.e("SoundPoolManager", "Error playing sound", e)
                playMediaPlayerFallback(appContext, resourceId, 1.0f)
            }
        }
    }

    private fun playMediaPlayerFallback(context: Context, resourceId: Int, volumeFraction: Float) {
        try {
            val mediaPlayer = MediaPlayer.create(context.applicationContext, resourceId) ?: return
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            mediaPlayer.setAudioAttributes(attributes)
            mediaPlayer.setVolume(volumeFraction, volumeFraction)
            mediaPlayer.setOnCompletionListener { mp ->
                try { mp.release() } catch (_: Exception) {}
            }
            mediaPlayer.setOnErrorListener { mp, _, _ ->
                try { mp.release() } catch (_: Exception) {}
                true
            }
            mediaPlayer.start()
        } catch (e: Exception) {
            Log.e("SoundPoolManager", "MediaPlayer fallback failed", e)
        }
    }
}

// 撥放音檔及音量控制 (使用 SoundPool 免系統全域音量衝突與解碼卡頓)
fun playSound(context: Context, resourceId: Int) {
    SoundPoolManager.play(context, resourceId)
}

// 檢查儲存空間，若小於安全臨界值 (預設 1GB)，自動由舊至新刪除最舊的錄影檔案 (全自動執行)
fun checkAndCleanStorage(context: Context, minFreeBytes: Long = 1024L * 1024L * 1024L) {
    try {
        val path = Environment.getExternalStorageDirectory()
        val stat = StatFs(path.path)
        var availableBytes = stat.availableBytes

        if (availableBytes >= minFreeBytes) return

        Log.w("StorageCleanup", "Storage space low ($availableBytes bytes free < $minFreeBytes required). Auto deleting oldest videos...")
        val appName = context.getString(R.string.app_name)

        // 1. 透過 MediaStore 查詢 Movies/Bodycam 中已完成之錄影檔案 (依 DATE_ADDED 遞增排序，舊檔在前)
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE
        )

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0"
        } else {
            "${MediaStore.Video.Media.DATA} LIKE ?"
        }

        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("Movies/$appName/%")
        } else {
            arrayOf("%/Movies/$appName/%")
        }

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} ASC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)

            while (cursor.moveToNext() && availableBytes < minFreeBytes) {
                val id = cursor.getLong(idColumn)
                val size = cursor.getLong(sizeColumn)
                val name = cursor.getString(nameColumn)
                val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                try {
                    val deletedRows = context.contentResolver.delete(contentUri, null, null)
                    if (deletedRows > 0) {
                        availableBytes += size
                        Log.i("StorageCleanup", "Deleted oldest video: $name (size: $size bytes). Updated free space: $availableBytes bytes")
                    }
                } catch (e: Exception) {
                    Log.e("StorageCleanup", "Failed to delete video via MediaStore: $name", e)
                }
            }
        }

        // 2. 實體目錄層級雙重檢查清理 (若 MediaStore 未能清理乾淨)
        if (availableBytes < minFreeBytes) {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val bodycamDir = File(moviesDir, appName)
            if (bodycamDir.exists() && bodycamDir.isDirectory) {
                val files = bodycamDir.listFiles { file -> file.isFile && file.name.endsWith(".mp4") }
                files?.sortBy { it.lastModified() }
                files?.forEach { file ->
                    if (availableBytes >= minFreeBytes) return@forEach
                    val fileSize = file.length()
                    if (file.delete()) {
                        availableBytes += fileSize
                        Log.i("StorageCleanup", "Deleted file directly from disk: ${file.name}")
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e("StorageCleanup", "Error during storage cleanup", e)
    }
}