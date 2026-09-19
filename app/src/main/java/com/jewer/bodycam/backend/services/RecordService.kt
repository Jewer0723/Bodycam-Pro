package com.jewer.bodycam.backend.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.jewer.bodycam.MainActivity
import com.jewer.bodycam.R
import com.jewer.bodycam.backend.camera.CameraManager
import com.jewer.bodycam.backend.functions.getBeepSoundStatus
import com.jewer.bodycam.backend.functions.getBodycamBrand
import com.jewer.bodycam.backend.functions.getFisheyeK
import com.jewer.bodycam.backend.functions.getFisheyeScale
import com.jewer.bodycam.backend.functions.getOrientationMode
import com.jewer.bodycam.backend.functions.getSimulatedWideAngleStatus
import com.jewer.bodycam.backend.functions.getUserName
import com.jewer.bodycam.backend.functions.getVibrateAndBeepTimeInterval
import com.jewer.bodycam.backend.functions.getVibrateStatus
import com.jewer.bodycam.backend.functions.getVideoQuality
import com.jewer.bodycam.backend.functions.playSound
import com.jewer.bodycam.backend.functions.vibrateOnce
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class RecordService: Service(), LifecycleOwner {

    override val lifecycle: Lifecycle
        field = LifecycleRegistry(this)

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // 監聽按鍵觸發錄影
        serviceScope.launch {
            triggerStartRecording.collect {
                startRecordingLogic()
            }
        }
        serviceScope.launch {
            triggerStopRecording.collect {
                stopRecordingLogic()
            }
        }
    }

    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(1, notification, type)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("RecordService", "Failed to start foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            stopService()
            return START_NOT_STICKY
        }

        when (intent.action) {
            START_RECORDING -> {
                startRecordingLogic()
            }
            STOP_RECORDING -> {
                stopRecordingLogic()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecordingLogic() {
        if (_isServiceRunning.value) return
        _isServiceRunning.value = true

        val vibrateApproved = getVibrateStatus(applicationContext)
        if (vibrateApproved) {
            vibrateOnce(applicationContext, 1000)
        }

        try {
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            val qualitySetting = getVideoQuality(applicationContext)
            CameraManager.getOrCreateSurfaceProcessor(
                context = applicationContext,
                isPortrait = getOrientationMode(applicationContext) == 1,
                isFrontCamera = false,
                fisheyeK = getFisheyeK(applicationContext),
                fisheyeScale = getFisheyeScale(applicationContext),
                isFisheyeEnabled = getSimulatedWideAngleStatus(applicationContext),
                brand = getBodycamBrand(applicationContext) ?: "AXON",
                userName = getUserName(applicationContext),
                isRecording = true
            )

            CameraManager.bindCamera(
                context = applicationContext,
                cameraProvider = cameraProvider,
                lifecycleOwner = this,
                cameraSelector = CameraManager.currentCameraSelector,
                previewView = CameraManager.currentPreviewView,
                fps = CameraManager.currentFps,
                selectedQuality = qualitySetting
            )
        } catch (e: Exception) {
            Log.e("RecordService", "Error binding camera to RecordService", e)
        }

        val started = startRecordingFile()
        if (!started) {
            _isServiceRunning.value = false
        }
        startPeriodicBeep()
    }

    private fun startRecordingFile(): Boolean {
        val qualitySetting = getVideoQuality(applicationContext)
        val videoCapture = CameraManager.getOrCreateVideoCapture(qualitySetting)
        try {
            val filenameFormat = "yyyy-MM-dd-HH-mm-ss"
            val videoName = SimpleDateFormat(filenameFormat, Locale.US).format(Date()) + ".mp4"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, videoName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/${getString(R.string.app_name)}")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val mediaStoreOutputOptions = MediaStoreOutputOptions
                .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build()

            val recorder = videoCapture.output
            val pendingRecording: PendingRecording = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try {
                    recorder.prepareRecording(this, mediaStoreOutputOptions).withAudioEnabled()
                } catch (e: Exception) {
                    Log.e("RecordService", "withAudioEnabled failed, falling back to silent video", e)
                    recorder.prepareRecording(this, mediaStoreOutputOptions)
                }
            } else {
                recorder.prepareRecording(this, mediaStoreOutputOptions)
            }

            activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        _isServiceRunning.value = true
                    }
                    is VideoRecordEvent.Finalize -> {
                        _isServiceRunning.value = false

                        val outputUri = recordEvent.outputResults.outputUri
                        if (outputUri != Uri.EMPTY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val updateValues = ContentValues().apply {
                                put(MediaStore.Video.Media.IS_PENDING, 0)
                            }
                            try {
                                contentResolver.update(outputUri, updateValues, null, null)
                            } catch (e: Exception) {
                                Log.e("RecordService", "Error clearing IS_PENDING", e)
                            }
                        }

                        if (recordEvent.hasError()) {
                            Log.e("RecordService", "VideoCapture error: ${recordEvent.error}, cause: ${recordEvent.cause}")
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e("RecordService", "Failed to start VideoCapture recording", e)
            return false
        }
    }

    private fun stopRecordingLogic() {
        if (!_isServiceRunning.value && activeRecording == null) return
        try {
            activeRecording?.stop()
            activeRecording = null

            val vibrateApproved = getVibrateStatus(applicationContext)
            if (vibrateApproved) {
                vibrateOnce(applicationContext, 1000)
            }

            val beepApproved = getBeepSoundStatus(applicationContext)
            if (beepApproved) {
                val brand = getBodycamBrand(applicationContext)
                val soundRes = if (brand == "MOTOROLA") R.raw.motorolastoprecordsound else R.raw.axonstoprecordsound
                playSound(applicationContext, soundRes)
            }
        } catch (e: Exception) {
            Log.e("RecordService", "activeRecording.stop failed", e)
        } finally {
            stopService()
        }
    }

    private fun stopService() {
        _isServiceRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startPeriodicBeep() {
        serviceScope.launch {
            while (_isServiceRunning.value) {
                val beepApproved = getBeepSoundStatus(applicationContext)
                val vibrateApproved = getVibrateStatus(applicationContext)
                val brand = getBodycamBrand(applicationContext)
                val interval = getVibrateAndBeepTimeInterval(applicationContext)

                if (beepApproved) {
                    val soundRes = if (brand == "MOTOROLA") R.raw.motorolastartrecordsound else R.raw.axonstartrecordsound
                    playSound(applicationContext, soundRes)
                }
                if (vibrateApproved) {
                    repeat(2) {
                        vibrateOnce(applicationContext, 300)
                        delay(400.milliseconds)
                    }
                }
                delay(interval.milliseconds)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeRecording?.stop()
        activeRecording = null
        _isServiceRunning.value = false
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.coroutineContext.cancelChildren()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordService::class.java).apply {
            action = STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notifyTitle = "Recording..."
        val notifyText = "Tap button to stop recording"

        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stop_record_foreground,
            "Stop Recording",
            stopPendingIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notifyTitle)
            .setContentText(notifyText)
            .setSmallIcon(R.mipmap.ic_water_mark_foreground)
            .setContentIntent(pendingIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Bodycam Recording Channel",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(serviceChannel)
    }

    companion object {
        private const val CHANNEL_ID = "bodycam_recording_channel"
        private val _isServiceRunning = MutableStateFlow(false)
        val isRecordingRunning = _isServiceRunning.asStateFlow()

        private var activeRecording: Recording? = null

        private val _triggerStartRecording = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val triggerStartRecording = _triggerStartRecording.asSharedFlow()

        private val _triggerStopRecording = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val triggerStopRecording = _triggerStopRecording.asSharedFlow()

        const val START_RECORDING = "START_RECORDING"
        const val STOP_RECORDING = "STOP_RECORDING"
    }
}
