package org.fossify.messages.androbox

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecorder : Service() {

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isRecording = false
    private var videoFile: File? = null
    private var currentOrigin: String = "user"

    companion object {
        private var instance: ScreenRecorder? = null
        private var pendingResultCode: Int = 0
        private var pendingData: Intent? = null
        private var isStartPending = false
        private var pendingOrigin = "user"

        fun setMediaProjection(resultCode: Int, data: Intent) {
            pendingResultCode = resultCode
            pendingData = data
            instance?.initMediaProjection(resultCode, data)
            if (isStartPending) {
                instance?.currentOrigin = pendingOrigin
                instance?.startRecordingInternal()
                isStartPending = false
            }
        }

        fun startRecording(origin: String = "user") {
            val inst = instance
            if (inst == null) {
                Log.e("ScreenRecorder", "Service not started")
                return
            }
            if (inst.mediaProjection == null) {
                isStartPending = true
                pendingOrigin = origin
                ForegroundService.requestMediaProjectionFor("screen_recorder", origin)
                return
            }
            inst.currentOrigin = origin
            inst.startRecordingInternal()
        }

        fun stopRecording() {
            instance?.stopRecordingInternal()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(2, getNotification("Screen recorder ready"))
        Log.d("ScreenRecorder", "Service started (foreground)")

        if (pendingResultCode != 0 && pendingData != null) {
            initMediaProjection(pendingResultCode, pendingData!!)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        Log.d("ScreenRecorder", "MediaProjection initialized: ${mediaProjection != null}")
    }

    private fun startRecordingInternal() {
        if (mediaProjection == null) {
            Log.e("ScreenRecorder", "MediaProjection is NULL! Cannot start.")
            return
        }
        if (isRecording) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e("ScreenRecorder", "POST_NOTIFICATIONS missing")
                return
            }
        }

        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        windowManager.defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ScreenRecords")
        if (!dir.exists()) dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        videoFile = File(dir, "screen_$timestamp.mp4")

        try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(5_000_000)
                setOutputFile(videoFile?.absolutePath)
                prepare()
                start()
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecorder", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )

            isRecording = true
            updateNotification("Recording...")
            Log.d("ScreenRecorder", "Recording started -> ${videoFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("ScreenRecorder", "Start error: ${e.message}")
            stopRecordingInternal()
        }
    }

    private fun stopRecordingInternal() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e("ScreenRecorder", "Stop error: ${e.message}")
        }
        isRecording = false
        mediaRecorder = null
        virtualDisplay = null
        mediaProjection = null

        videoFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                Log.d("ScreenRecorder", "Video saved, size=${file.length()}")
                ForegroundService.uploadScreenRecord(file, currentOrigin)
            } else {
                file?.delete()
            }
        }
        videoFile = null
        updateNotification("Idle")
    }

    private fun getNotification(text: String): Notification {
        val channelId = "screen_recorder_channel"
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "screen_recorder_channel",
                "Screen Recorder",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(2, getNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingInternal()
        isStartPending = false
        instance = null
    }
}