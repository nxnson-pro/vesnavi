package com.nxnson.vesnavi.navcore

import kotlin.math.max
import kotlin.math.min

/**
 * Positions along a route by distance — used for the in-app simulation mode
 * so navigation (and the dashboard link) can be tested without riding.
 */
class RouteSimulator(val route: Route) {
    private val cumulative = Geo.cumulativeDistances(route.polyline)

    val length: Double get() = cumulative.lastOrNull() ?: 0.0

    fun coordinate(atDistance: Double): Coordinate {
        val pts = route.polyline
        val first = pts.firstOrNull() ?: return Coordinate(0.0, 0.0)
        if (atDistance <= 0) return first
        if (atDistance >= length) return pts.last()
        // Binary search for the segment containing the distance.
        var lo = 0
        var hi = cumulative.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= atDistance) lo = mid else hi = mid
        }
        val segLen = cumulative[hi] - cumulative[lo]
        val t = if (segLen > 0) (atDistance - cumulative[lo]) / segLen else 0.0
        return Geo.interpolate(pts[lo], pts[hi], t)
    }
}

/**
 * Decides when a new NavigationState is worth pushing to the dashboard.
 * The BLE link to the scooter is slow, so we send only meaningful changes:
 * immediately when the maneuver/street/status changes, otherwise when the
 * rounded distance changes (at most every `minInterval`), plus a periodic
 * heartbeat so the dashboard doesn't time out the navigation screen.
 */
class DashboardThrottle(
    private val minInterval: Double = 1.0,
    private val heartbeat: Double = 10.0,
) {
    private data class Key(
        val maneuver: Maneuver,
        val street: String?,
        val offRoute: Boolean,
        val arrived: Boolean,
        val speedLimit: Int?,
    )

    private var lastKey: Key? = null
    private var lastDistance: Double? = null
    private var lastSent = Double.NEGATIVE_INFINITY

    /** `now` in seconds. */
    fun shouldSend(state: NavigationState, now: Double): Boolean {
        val key = Key(state.maneuver, state.streetName, state.isOffRoute, state.hasArrived, state.speedLimit)
        val distance = DistanceFormatter.rounded(state.distanceToManeuver)
        val elapsed = now - lastSent
        val send = key != lastKey || (distance != lastDistance && elapsed >= minInterval) || elapsed >= heartbeat
        if (send) {
            lastKey = key
            lastDistance = distance
            lastSent = now
        }
        return send
    }

    fun reset() {
        lastKey = null
        lastDistance = null
        lastSent = Double.NEGATIVE_INFINITY
    }
}

/**
 * Turns raw GPS fixes into "what's the next maneuver and how far is it".
 * Snaps each fix onto the route polyline, preferring forward progress so routes
 * that pass the same street twice don't jump around.
 */
class NavigationEngine(val route: Route) {
    /** Meters from the route before a fix counts as off-route (raised by poor GPS accuracy). */
    var offRouteThreshold = 40.0
    /** Consecutive off-route fixes needed before declaring off-route (filters GPS glitches). */
    var offRouteConfirmCount = 3
    /** Meters before the destination at which we declare arrival. */
    var arrivalThreshold = 25.0
    /** How far ahead (meters) the local snap search looks before falling back to a full scan. */
    var lookAhead = 1000.0
    /** Language of `NavigationState.instruction`; can change mid-route. */
    var language = Language.VI

    private val cumulative = Geo.cumulativeDistances(route.polyline)
    private var segment = 0
    private var offRouteHits = 0
    var hasArrived = false
        private set

    private val totalLength get() = cumulative.lastOrNull() ?: 0.0

    private data class Snap(val segment: Int, val distance: Double, val along: Double, val point: Coordinate)

    fun update(location: Coordinate, accuracy: Double = 0.0): NavigationState {
        val threshold = max(offRouteThreshold, min(accuracy, 100.0))
        val lastSeg = route.polyline.size - 2

        // 1) Local search around the last known segment.
        val from = max(0, segment - 2)
        var to = segment
        while (to < lastSeg && cumulative[to] - cumulative[segment] < lookAhead) to++
        var best = bestSnap(location, from, max(from, to))

        // 2) Not close to the expected stretch → scan the whole route (e.g. after a GPS jump).
        if (best.distance > threshold) {
            val global = bestSnap(location, 0, max(0, lastSeg))
            if (global.distance < best.distance) best = global
        }

        if (best.distance > threshold) {
            offRouteHits++
        } else {
            offRouteHits = 0
            segment = best.segment
        }
        val offRoute = offRouteHits >= offRouteConfirmCount

        // While unconfirmed off-route, keep reporting from the last good position.
        val onRoute = best.distance <= threshold
        val along = if (onRoute) best.along else cumulative[segment]
        val snapped = if (onRoute) best.point else null

        val remaining = max(0.0, totalLength - along)
        if (!offRoute && remaining <= arrivalThreshold) hasArrived = true

        val next = nextStep(along)
        val maneuver = if (hasArrived) Maneuver.Arrive else next.maneuver
        val street = if (hasArrived) null else next.streetName
        val duration = if (totalLength > 0) route.duration * remaining / totalLength else 0.0
        val currentStreet = route.steps.lastOrNull { cumulative[it.pointIndex] <= along + 1 }?.streetName
        val limit = route.speedLimits.firstOrNull { it.from <= segment && segment < max(it.to, it.from + 1) }?.kmh

        return NavigationState(
            maneuver = maneuver,
            streetName = street,
            instruction = InstructionFormatter.text(maneuver, street, language),
            distanceToManeuver = if (hasArrived) 0.0 else max(0.0, cumulative[next.pointIndex] - along),
            remainingDistance = if (hasArrived) 0.0 else remaining,
            remainingDuration = if (hasArrived) 0.0 else duration,
            isOffRoute = offRoute,
            hasArrived = hasArrived,
            snappedPosition = snapped,
            roundaboutTurnDegrees = if (hasArrived) null else next.exitTurnDegrees,
            speedLimit = limit,
            segmentIndex = segment,
            currentStreetName = currentStreet,
        )
    }

    /** First maneuver strictly ahead of `along` (skipping depart); falls back to arrival. */
    private fun nextStep(along: Double): RouteStep =
        route.steps.firstOrNull { it.maneuver != Maneuver.Depart && cumulative[it.pointIndex] > along + 1 }
            ?: route.steps.lastOrNull { it.maneuver == Maneuver.Arrive }
            ?: RouteStep(Maneuver.Arrive, null, route.polyline.size - 1)

    private fun bestSnap(p: Coordinate, from: Int, to: Int): Snap {
        var best = Snap(from, Double.POSITIVE_INFINITY, 0.0, p)
        for (i in from..to) {
            if (i + 1 >= route.polyline.size) break
            val a = route.polyline[i]
            val b = route.polyline[i + 1]
            val (t, d) = Geo.project(p, a, b)
            if (d < best.distance) {
                val along = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
                best = Snap(i, d, along, Geo.interpolate(a, b, t))
            }
        }
        return best
    }
}
