package org.fossify.messages.androbox

import java.io.File

object FileManager {
    fun listFiles(path: String): String {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return "Invalid path"
        return dir.listFiles()?.joinToString("\n") { it.name } ?: "Empty"
    }
    fun deleteFile(path: String): Boolean {
        val file = File(path)
        return if (file.exists()) file.delete() else false
    }
    fun getFileBytes(file: File): ByteArray? {
        return if (file.exists() && file.isFile) file.readBytes() else null
    }
}