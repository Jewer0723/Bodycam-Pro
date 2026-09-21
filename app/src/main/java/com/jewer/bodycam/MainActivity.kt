package com.jewer.bodycam

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.jewer.bodycam.backend.functions.getKeyOperationMode
import com.jewer.bodycam.backend.functions.getKeyRecordingStatus
import com.jewer.bodycam.backend.functions.initSettings
import com.jewer.bodycam.backend.functions.orientationFlow
import com.jewer.bodycam.backend.functions.setFullScreen
import com.jewer.bodycam.backend.services.RecordService
import com.jewer.bodycam.frontend.screens.PermissionScreen

class MainActivity : ComponentActivity(), CameraXConfig.Provider {

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR)
            .build()
    }

    private lateinit var appUpdateManager: AppUpdateManager
    
    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            // 如果更新被取消或失敗，再次檢查更新 (這會導致更新彈窗再次出現，達成強制更新效果)
            Log.d("Update", "Update flow failed! Result code: ${result.resultCode}")
            checkForUpdates()
            Toast.makeText(this, "The app must be updated to continue.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 初始化 App 內更新管理器
        appUpdateManager = AppUpdateManagerFactory.create(this)
        checkForUpdates()

        // 初始化設定 Flow，讀取最後保存的方向
        initSettings(this)
        
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setFullScreen(this, true)

        setContent {
            // 即時監聽 orientationFlow，一旦在設定中改變模式，這裡會立即觸發轉向
            val currentMode by orientationFlow.collectAsStateWithLifecycle()

            LaunchedEffect(currentMode) {
                val target = if (currentMode == 1) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT 
                             else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                
                if (requestedOrientation != target) {
                    requestedOrientation = target
                }
            }
            
            PermissionScreen()
        }
    }

    // 檢查 Google Play 是否有新版本
    private fun checkForUpdates() {
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo

        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                // 如果有新版本且支援「立即更新」，則啟動更新流程
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        updateLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                } catch (e: Exception) {
                    Log.e("Update", "Failed to start update flow", e)
                }
            }
        }
    }

    // 當使用者從更新介面返回後 (或 App 從背景回來)，確保更新流程仍在進行
    override fun onResume() {
        super.onResume()
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                // 如果更新正在進行中，則重新啟動更新畫面
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    updateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                )
            }
        }
    }

    private var lastVolumeUpTime = 0L

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (getKeyRecordingStatus(this)) {
            val isRecording = RecordService.isRecordingRunning.value
            val isSimulated = getKeyOperationMode(this) == "Simulated"

            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (isSimulated) {
                        val now = System.currentTimeMillis()
                        // 仿真模式：短按兩次音量+
                        if (now - lastVolumeUpTime < 400L) {
                            lastVolumeUpTime = 0L
                            if (!isRecording) {
                                val intent = Intent(applicationContext, RecordService::class.java).apply {
                                    action = RecordService.START_RECORDING
                                }
                                startForegroundService(intent)
                            }
                        } else {
                            lastVolumeUpTime = now
                        }
                    } else {
                        // 預設模式：單按一次音量+ 開始錄影
                        if (!isRecording) {
                            val intent = Intent(applicationContext, RecordService::class.java).apply {
                                action = RecordService.START_RECORDING
                            }
                            startForegroundService(intent)
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (isSimulated) {
                        // 仿真模式：追蹤長按手勢
                        event?.startTracking()
                    } else {
                        // 預設模式：單按一次音量- 停止錄影
                        if (isRecording) {
                            val intent = Intent(applicationContext, RecordService::class.java).apply {
                                action = RecordService.STOP_RECORDING
                            }
                            startService(intent)
                        }
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (getKeyRecordingStatus(this) && getKeyOperationMode(this) == "Simulated") {
            val isRecording = RecordService.isRecordingRunning.value
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                // 仿真模式長按一次音量-：觸發停止錄影
                if (isRecording) {
                    val intent = Intent(applicationContext, RecordService::class.java).apply {
                        action = RecordService.STOP_RECORDING
                    }
                    startService(intent)
                }
                return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }
}
