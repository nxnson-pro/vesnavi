package com.nxnson.vesnavi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nxnson.vesnavi.navcore.Coordinate
import com.nxnson.vesnavi.navcore.Geo
import com.nxnson.vesnavi.navcore.NavigationState
import com.nxnson.vesnavi.navcore.SpeedLimitResolver
import com.nxnson.vesnavi.navcore.SpeedLimitResult
import com.nxnson.vesnavi.navcore.SpeedLimitSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Current legal speed limit while navigating. Free sources first: the route itself
 * (GraphHopper), OpenStreetMap through the public Overpass API, Vietnamese default rules.
 */
class SpeedLimitService(private val settings: Settings, private val scope: CoroutineScope) {
    var current by mutableStateOf<SpeedLimitResult?>(null)
        private set

    private var lastQueryAt = 0L
    private var lastQueryPoint: Coordinate? = null
    private var lastStreet: String? = null
    private var inFlight = false

    companion object {
        /** Ask again after this distance, or when the street changes. */
        private const val REQUERY_METERS = 250.0
        private const val MIN_INTERVAL_MS = 8_000L
    }

    fun reset() {
        current = null
        lastQueryPoint = null
        lastStreet = null
    }

    /** Call on every navigation update; returns the limit to show right now. */
    fun limit(state: NavigationState, position: Coordinate): SpeedLimitResult? {
        when (settings.speedLimitSource) {
            SpeedLimitSource.OFF -> return null
            SpeedLimitSource.GRAPH_HOPPER -> {
                val kmh = state.speedLimit
                if (kmh != null) {
                    val r = SpeedLimitResult(kmh, false, state.currentStreetName)
                    if (r != current) current = r
                    return r
                }
                lookUp(state, position, vietnamDefaults = true)
            }
            SpeedLimitSource.OSM -> lookUp(state, position, vietnamDefaults = false)
            SpeedLimitSource.OSM_WITH_VIETNAM_DEFAULTS -> lookUp(state, position, vietnamDefaults = true)
        }
        return current
    }

    private fun lookUp(state: NavigationState, p: Coordinate, vietnamDefaults: Boolean) {
        val street = state.currentStreetName
        val moved = lastQueryPoint?.let { Geo.distance(it, p) } ?: Double.POSITIVE_INFINITY
        val streetChanged = street != null && street != lastStreet
        val now = System.currentTimeMillis()
        if (inFlight || now - lastQueryAt < MIN_INTERVAL_MS || (moved < REQUERY_METERS && !streetChanged)) return
        inFlight = true
        lastQueryAt = now
        lastQueryPoint = p
        lastStreet = street
        scope.launch {
            val result = query(p, street, vietnamDefaults)
            inFlight = false
            // Keep the previous value when the lookup failed (no network): limits rarely change.
            if (result != null) current = result else if (streetChanged) current = null
        }
    }

    private suspend fun query(p: Coordinate, street: String?, vietnamDefaults: Boolean): SpeedLimitResult? {
        for (endpoint in SpeedLimitResolver.overpassEndpoints) {
            val ways = runCatching {
                SpeedLimitResolver.parseOverpass(Network.get(SpeedLimitResolver.overpassURL(p, endpoint = endpoint), timeoutMs = 10_000))
            }.getOrNull() ?: continue
            return SpeedLimitResolver.resolve(ways, street, vietnamDefaults)
        }
        return null
    }
}
