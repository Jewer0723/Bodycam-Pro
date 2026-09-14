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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.jewer.bodycam.backend.functions.getKeyRecordingStatus
import com.jewer.bodycam.backend.functions.initSettings
import com.jewer.bodycam.backend.functions.orientationFlow
import com.jewer.bodycam.backend.functions.setFullScreen
import com.jewer.bodycam.backend.services.RecordService
import com.jewer.bodycam.frontend.screens.PermissionScreen

class MainActivity : ComponentActivity() {

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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (getKeyRecordingStatus(this)) {
            val isRecording = RecordService.isRecordingRunning.value
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    // 只有在【未錄影】狀態下才觸發開始錄影，防止重複錄影
                    if (!isRecording) {
                        val intent = Intent(applicationContext, RecordService::class.java).apply {
                            action = RecordService.START_RECORDING
                        }
                        startForegroundService(intent)
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    // 只有在【錄影中】狀態下才觸發停止錄影
                    if (isRecording) {
                        val intent = Intent(applicationContext, RecordService::class.java).apply {
                            action = RecordService.STOP_RECORDING
                        }
                        startService(intent)
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
