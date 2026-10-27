package com.tailconnect.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

data class DeviceLocationResult(
    val success: Boolean,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val provider: String = "unknown",
    val timestamp: Long = System.currentTimeMillis(),
    val error: String? = null
)

object DeviceLocatorManager {
    private const val TAG = "DeviceLocatorManager"

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun fetchSinglePing(context: Context, callback: (DeviceLocationResult) -> Unit) {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Location permission not granted.")
            callback(DeviceLocationResult(
                success = false,
                error = "Location permission not granted on device"
            ))
            return
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            callback(DeviceLocationResult(
                success = false,
                error = "LocationManager unavailable"
            ))
            return
        }

        val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            callback(DeviceLocationResult(
                success = false,
                error = "Location services disabled on phone"
            ))
            return
        }

        // On Android 11+ (API 30+), use getCurrentLocation for clean single ping with cancellation signal
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = if (isGpsEnabled) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            val cancellationSignal = CancellationSignal()
            val mainExecutor = ContextCompat.getMainExecutor(context)

            val timeoutHandler = Handler(Looper.getMainLooper())
            val isCompleted = AtomicBoolean(false)

            timeoutHandler.postDelayed({
                if (isCompleted.compareAndSet(false, true)) {
                    cancellationSignal.cancel()
                    // Fall back to best last known location if available
                    val fallback = getBestLastKnownLocation(lm)
                    if (fallback != null) {
                        callback(fallbackToResult(fallback))
                    } else {
                        callback(DeviceLocationResult(
                            success = false,
                            error = "GPS fix timed out after 10s"
                        ))
                    }
                }
            }, 10000L)

            try {
                lm.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    mainExecutor
                ) { loc: Location? ->
                    if (isCompleted.compareAndSet(false, true)) {
                        timeoutHandler.removeCallbacksAndMessages(null)
                        if (loc != null) {
                            callback(locationToResult(loc))
                        } else {
                            val fallback = getBestLastKnownLocation(lm)
                            if (fallback != null) {
                                callback(fallbackToResult(fallback))
                            } else {
                                callback(DeviceLocationResult(
                                    success = false,
                                    error = "Failed to resolve GPS coordinates"
                                ))
                            }
                        }
                    }
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentLocation threw exception: ${e.message}, falling back to listener")
            }
        }

        // Fallback for API < 30 or if getCurrentLocation threw
        val provider = when {
            isGpsEnabled -> LocationManager.GPS_PROVIDER
            isNetworkEnabled -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }

        val listenerHandler = Handler(Looper.getMainLooper())
        val isHandled = AtomicBoolean(false)

        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (isHandled.compareAndSet(false, true)) {
                    listenerHandler.removeCallbacksAndMessages(null)
                    try { lm.removeUpdates(this) } catch (_: Exception) {}
                    callback(locationToResult(loc))
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        listenerHandler.postDelayed({
            if (isHandled.compareAndSet(false, true)) {
                try { lm.removeUpdates(listener) } catch (_: Exception) {}
                val fallback = getBestLastKnownLocation(lm)
                if (fallback != null) {
                    callback(fallbackToResult(fallback))
                } else {
                    callback(DeviceLocationResult(
                        success = false,
                        error = "Single ping timed out after 10s"
                    ))
                }
            }
        }, 10000L)

        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            if (isHandled.compareAndSet(false, true)) {
                listenerHandler.removeCallbacksAndMessages(null)
                val fallback = getBestLastKnownLocation(lm)
                if (fallback != null) {
                    callback(fallbackToResult(fallback))
                } else {
                    callback(DeviceLocationResult(
                        success = false,
                        error = "Failed to register location updates: ${e.message}"
                    ))
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(lm: LocationManager): Location? {
        var best: Location? = null
        val providers = lm.getProviders(true)
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.accuracy < best.accuracy) {
                    best = loc
                }
            } catch (_: Exception) {}
        }
        return best
    }

    private fun locationToResult(loc: Location): DeviceLocationResult {
        return DeviceLocationResult(
            success = true,
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy = loc.accuracy,
            altitude = loc.altitude,
            speed = loc.speed,
            bearing = loc.bearing,
            provider = loc.provider ?: "gps",
            timestamp = loc.time
        )
    }

    private fun fallbackToResult(loc: Location): DeviceLocationResult {
        return DeviceLocationResult(
            success = true,
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy = loc.accuracy,
            altitude = loc.altitude,
            speed = loc.speed,
            bearing = loc.bearing,
            provider = "${loc.provider ?: "gps"}_cached",
            timestamp = loc.time
        )
    }
}
