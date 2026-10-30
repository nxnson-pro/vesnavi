package com.nxnson.vesnavi.navcore

/** Raw bytes as unsigned values 0…255, so comparisons and concatenation read like the protocol notes. */
typealias Bytes = List<Int>

fun ByteArray.toBytes(): Bytes = map { it.toInt() and 0xFF }

fun Bytes.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }

data class Coordinate(val latitude: Double, val longitude: Double)

/**
 * Turn types we can show — kept small on purpose so it maps cleanly
 * onto the pictogram set of the Vespa TFT dashboard.
 */
sealed class Maneuver {
    object Depart : Maneuver()
    object Straight : Maneuver()
    object SlightLeft : Maneuver()
    object Left : Maneuver()
    object SharpLeft : Maneuver()
    object SlightRight : Maneuver()
    object Right : Maneuver()
    object SharpRight : Maneuver()
    object KeepLeft : Maneuver()
    object KeepRight : Maneuver()
    object UTurnLeft : Maneuver()
    object UTurnRight : Maneuver()
    data class Roundabout(val exit: Int?) : Maneuver()
    object Arrive : Maneuver()

    override fun toString(): String = this::class.simpleName ?: "Maneuver"
}

data class RouteStep(
    val maneuver: Maneuver,
    val streetName: String?,
    /** Index into [Route.polyline] of the point where this maneuver happens. */
    val pointIndex: Int,
    /**
     * Roundabouts: angle between the entry and the exit road, degrees, + = right, − = left
     * (filled in by [RouteAnnotator]). Picks the TFT roundabout pictogram.
     */
    val exitTurnDegrees: Double? = null,
)

/** Legal speed limit over a stretch of the route polyline (point indices, inclusive start). */
data class SpeedLimitSpan(val from: Int, val to: Int, val kmh: Int)

data class Route(
    val polyline: List<Coordinate>,
    /** Sorted by `pointIndex`. First is usually Depart, last Arrive. */
    val steps: List<RouteStep>,
    /** Meters. */
    val distance: Double,
    /** Seconds. */
    val duration: Double,
    /** Human-readable source, e.g. "GraphHopper", "OSRM". */
    val provider: String,
    /** Speed limits along the route when the provider returns them (GraphHopper `max_speed`). */
    val speedLimits: List<SpeedLimitSpan> = emptyList(),
)

/** What the rider should see right now — this is what gets pushed to the dashboard. */
data class NavigationState(
    val maneuver: Maneuver,
    val streetName: String?,
    val instruction: String,
    /** Meters to the next maneuver. */
    val distanceToManeuver: Double,
    /** Meters to destination. */
    val remainingDistance: Double,
    /** Seconds to destination (scaled from the route estimate). */
    val remainingDuration: Double,
    val isOffRoute: Boolean,
    val hasArrived: Boolean,
    /** Position snapped onto the route (null when off-route). */
    val snappedPosition: Coordinate?,
    /** See [RouteStep.exitTurnDegrees]. */
    val roundaboutTurnDegrees: Double? = null,
    /** km/h; filled in by the app from the route or a lookup (sent to the TFT as tag 02). */
    val speedLimit: Int? = null,
    /** True when the limit is inferred from Vietnamese default rules, not tagged on the road. */
    val speedLimitEstimated: Boolean = false,
    /** Index of the route segment the rider is on (for per-segment data such as speed limits). */
    val segmentIndex: Int = 0,
    /** The street the rider is on now (`streetName` is the one after the next turn). */
    val currentStreetName: String? = null,
)
