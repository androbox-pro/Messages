package org.fossify.messages.androbox

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object AppList {

    /**
     * ডিভাইসে ইনস্টল করা সব অ্যাপ্লিকেশনের তালিকা তৈরি করে এবং একটি টেক্সট ফাইল হিসেবে রিটার্ন করে।
     */
    fun collectApps(context: Context): File {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val sb = StringBuilder()
        sb.appendLine("***** INSTALLED APPLICATIONS *****\n")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.appendLine("Generated: ${dateFormat.format(Date())}\n")

        // অ্যাপগুলো নাম অনুযায়ী সাজানো
        val sortedApps = apps.sortedBy { pm.getApplicationLabel(it).toString() }

        for (app in sortedApps) {
            val appName = pm.getApplicationLabel(app).toString()
            val packageName = app.packageName
            val isSystem = if ((app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) "System" else "User"
            sb.appendLine("$appName ($packageName) - $isSystem")
        }

        // ফাইলটি ক্যাশে ডিরেক্টরিতে সংরক্ষণ
        val file = File(context.cacheDir, "applist.txt")
        FileOutputStream(file).use {
            it.write(sb.toString().toByteArray())
        }
        return file
    }
}