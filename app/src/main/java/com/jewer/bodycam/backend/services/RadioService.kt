package com.jewer.bodycam.backend.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.jewer.bodycam.R
import com.jewer.bodycam.backend.functions.getBeepSoundStatus
import com.jewer.bodycam.backend.functions.getUserName
import com.jewer.bodycam.backend.functions.getVibrateStatus
import com.jewer.bodycam.backend.functions.playSound
import com.jewer.bodycam.backend.functions.vibrateOnce
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import kotlin.time.Duration.Companion.milliseconds

class RadioService : Service() {

    companion object {
        private const val TAG = "RadioService"
        private const val SERVICE_ID = "com.jewer.bodycam.RADIO_CHANNEL"
        private const val CHANNEL_ID = "RadioServiceChannel"
        private const val NOTIFICATION_ID = 2
        
        val isRadioRunning = MutableStateFlow(false)
        val isRadioSearching = MutableStateFlow(false)
        val connectedEndpoints = MutableStateFlow(emptyList<String>().toSet())

        const val ACTION_START = "START_RADIO"
        const val ACTION_STOP = "STOP_RADIO"

        private const val SAMPLE_RATE = 8000 // 提高採樣率至 16k 以獲得更好的語音品質與辨識度
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // 緩衝區設為 8 倍 minBufferSize，平衡延遲與穩定性
        private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT) * 8
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var connectionsClient: ConnectionsClient
    private val endpointNames = mutableMapOf<String, String>()

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private var currentStreamPayload: Payload? = null

    override fun onCreate() {
        super.onCreate()
        connectionsClient = Nearby.getConnectionsClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRadio()
            ACTION_STOP -> stopRadio()
        }
        return START_STICKY
    }

    private fun startRadio() {
        if (isRadioRunning.value) return

        // 啟動時的提示音與震動
        if (getBeepSoundStatus(this)) playSound(this, R.raw.radiostartsound)
        if (getVibrateStatus(this)) vibrateOnce(this, 500)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Searching for devices..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Searching for devices..."))
        }
        
        isRadioRunning.value = true
        isRadioSearching.value = true
        
        startAdvertising()
        startDiscovery()

        // ── 搜尋音效循環邏輯 (背景執行) ──
        serviceScope.launch {
            while (isRadioSearching.value && isRadioRunning.value) {
                delay(1000.milliseconds)
                if (getBeepSoundStatus(this@RadioService)) {
                    playSound(this@RadioService, R.raw.radiowaitingsound)
                }
                delay(1000.milliseconds)
            }
        }

        // 1分鐘後如果沒連線則自動停止
        serviceScope.launch {
            delay(60000.milliseconds)
            if (connectedEndpoints.value.isEmpty() && isRadioRunning.value) {
                Log.d(TAG, "No devices found within 1 minute, auto stopping...")
                stopRadio()
            }
        }
    }

    private fun stopRadio() {
        if (!isRadioRunning.value) return
        
        // 無論手動還是自動停止，都播放結束提示音與震動
        if (getBeepSoundStatus(this)) playSound(this, R.raw.radiooversound)
        if (getVibrateStatus(this)) vibrateOnce(this, 500)

        isRadioRunning.value = false
        isRadioSearching.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        
        stopAudioRecording()
        connectedEndpoints.value = emptySet()
        endpointNames.clear()
        
        stopSelf()
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising(
            getUserName(this), SERVICE_ID, connectionLifecycleCallback, options
        ).addOnFailureListener { e -> Log.e(TAG, "Advertising failed", e) }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, options
        ).addOnFailureListener { e -> Log.e(TAG, "Discovery failed", e) }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Endpoint found: ${info.endpointName}")
            connectionsClient.requestConnection(getUserName(this@RadioService), endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            endpointNames[endpointId] = info.endpointName
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d(TAG, "Connected to $endpointId")
                isRadioSearching.value = false
                connectedEndpoints.value += endpointId
                updateNotification("Connected to ${connectedEndpoints.value.size} devices")
                
                // 確保啟動錄製並將流送給新連線的裝置
                startAudioRecording()
                // 如果已經在錄音了，也要把當前的 Payload 送給新加入的人
                currentStreamPayload?.let {
                    connectionsClient.sendPayload(endpointId, it)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.value -= endpointId
            endpointNames.remove(endpointId)
            if (connectedEndpoints.value.isEmpty()) {
                updateNotification("Searching for devices...")
                isRadioSearching.value = true
                
                // 停止當前錄音，因為已經沒有接收者了，避免 Pipe 殘留錯誤導致下一次連線無聲
                stopAudioRecording()
                
                serviceScope.launch {
                    while (isRadioSearching.value && isRadioRunning.value) {
                        delay(1000.milliseconds)
                        if (getBeepSoundStatus(this@RadioService)) {
                            playSound(this@RadioService, R.raw.radiowaitingsound)
                        }
                        delay(1000.milliseconds)
                    }
                }
            } else {
                updateNotification("Connected to ${connectedEndpoints.value.size} devices")
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.STREAM) {
                serviceScope.launch {
                    handleAudioStream(payload.asStream()!!.asInputStream())
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun startAudioRecording() {
        if (isRecording) return
        
        // 檢查錄音權限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        isRecording = true
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // 改用相容性高且語音清晰的音源
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                val sessionId = audioRecord!!.audioSessionId
                // 1. 回音消除：防止喇叭聲音傳回麥克風產生尖叫
                if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(sessionId)?.enabled = true
                }
                // 2. 自動增益控制 (AGC)：穩定遠近說話的音量，防止忽大忽小
                if (AutomaticGainControl.isAvailable()) {
                    AutomaticGainControl.create(sessionId)?.enabled = true
                }
                // 3. 噪音抑制：濾除背景雜訊，讓語音更集中
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(sessionId)?.enabled = true
                }
            }

            val (inputPipe, outputPipe) = ParcelFileDescriptor.createPipe()
            val payload = Payload.fromStream(inputPipe)
            currentStreamPayload = payload
            
            // 將錄音流送給目前所有連線端
            for (endpointId in connectedEndpoints.value) {
                connectionsClient.sendPayload(endpointId, payload)
            }

            serviceScope.launch {
                val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(outputPipe)
                try {
                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord?.startRecording()
                    }
                    
                    // 使用固定的較小讀取緩衝 (2048 bytes 約 64ms)，減少傳輸抖動導致的斷續
                    val readBuffer = ByteArray(2048)
                    while (isRecording && isRadioRunning.value) {
                        val readCount = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0
                        if (readCount > 0 && connectedEndpoints.value.isNotEmpty()) {
                            try {
                                outputStream.write(readBuffer, 0, readCount)
                            } catch (_: IOException) {
                                break 
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during audio capture", e)
                } finally {
                    try { outputStream.flush(); outputStream.close() } catch (_: Exception) {}
                    isRecording = false
                    currentStreamPayload = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording failed", e)
            isRecording = false
        }
    }

    private fun stopAudioRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun handleAudioStream(inputStream: InputStream) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        // 紀錄原始狀態
        val originalMode = audioManager.mode
        val wasSpeakerphoneOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }

        // 設置為通訊模式
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphone(audioManager, true)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG_OUT)
                .build())
            .setBufferSizeInBytes(BUFFER_SIZE)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack.play()
            }
            
            // 使用較小的讀取塊 (1024 bytes) 從串流中讀取，增加播放的即時性與流暢度
            val playBuffer = ByteArray(1024)
            var readCount: Int
            while (isRadioRunning.value) {
                try {
                    readCount = inputStream.read(playBuffer)
                    if (readCount == -1) break
                    if (readCount > 0) {
                        // 移除手動 setVolume，讓系統根據 MODE_IN_COMMUNICATION 自動調整最佳增益
                        audioTrack.write(playBuffer, 0, readCount)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Stream read interrupted", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback error", e)
        } finally {
            try {
                audioTrack.stop()
                audioTrack.release()
                inputStream.close()
            } catch (_: Exception) {}
            // 恢復原始音訊設定
            setSpeakerphone(audioManager, wasSpeakerphoneOn)
            audioManager.mode = originalMode
        }
    }

    private fun setSpeakerphone(audioManager: AudioManager, on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                speakerDevice?.let { audioManager.setCommunicationDevice(it) }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID, "Radio Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bodycam Radio")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_radio_foreground)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRadio()
        serviceScope.cancel()
        super.onDestroy()
    }
}
