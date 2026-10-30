package com.nxnson.vesnavi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GPS through the platform LocationManager (no Google Play services needed).
 * Battery: precise GPS only while navigating; coarse in the foreground; nothing in the background.
 */
class LocationService(private val context: Context, private val scope: CoroutineScope) {
    enum class Mode { OFF, BROWSING, NAVIGATING }

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    var lastLocation by mutableStateOf<Location?>(null)
        private set
    var onUpdate: ((Location) -> Unit)? = null
    private var mode = Mode.OFF

    private val listener = LocationListener { location ->
        lastLocation = location
        onUpdate?.invoke(location)
    }

    val isAuthorized: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Call after the permission was granted, so the current mode starts. */
    fun permissionChanged() {
        val m = mode
        mode = Mode.OFF
        setMode(m)
    }

    @SuppressLint("MissingPermission")
    fun setMode(newMode: Mode) {
        if (newMode == mode) return
        mode = newMode
        manager.removeUpdates(listener)
        if (!isAuthorized || newMode == Mode.OFF) return
        runCatching {
            if (lastLocation == null) {
                lastLocation = providers().mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
            }
            when (newMode) {
                Mode.BROWSING -> providers().forEach { manager.requestLocationUpdates(it, 5_000L, 50f, listener, Looper.getMainLooper()) }
                Mode.NAVIGATING -> manager.requestLocationUpdates(gpsOrBest(), 1_000L, 0f, listener, Looper.getMainLooper())
                Mode.OFF -> Unit
            }
        }
    }

    private fun providers(): List<String> =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    private fun gpsOrBest() =
        if (runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER

    /**
     * The most accurate fix within `timeoutMs` (sooner once one is within `goodEnough` m), from a
     * separate full-accuracy GPS session — the browsing fix can be 100 m off. Runs in the background
     * while the scooter service keeps the app alive.
     */
    @SuppressLint("MissingPermission")
    fun requestPreciseFix(timeoutMs: Long = 20_000, goodEnough: Float = 10f, completion: (Location?) -> Unit) {
        if (!isAuthorized) {
            completion(null)
            return
        }
        val started = System.currentTimeMillis()
        var best: Location? = null
        var done = false
        var timeout: Job? = null
        lateinit var fixListener: LocationListener
        fun finish() {
            if (done) return
            done = true
            timeout?.cancel()
            manager.removeUpdates(fixListener)
            completion(best)
        }
        fixListener = LocationListener { fix ->
            // Skip cached fixes from before the request: they are where the phone was, not is.
            if (fix.time >= started - 2_000 && fix.hasAccuracy() && fix.accuracy < (best?.accuracy ?: Float.MAX_VALUE)) best = fix
            if ((best?.accuracy ?: Float.MAX_VALUE) <= goodEnough) finish()
        }
        val ok = runCatching { manager.requestLocationUpdates(gpsOrBest(), 0L, 0f, fixListener, Looper.getMainLooper()) }.isSuccess
        if (!ok) {
            completion(null)
            return
        }
        timeout = scope.launch {
            delay(timeoutMs)
            finish()
        }
    }
}
