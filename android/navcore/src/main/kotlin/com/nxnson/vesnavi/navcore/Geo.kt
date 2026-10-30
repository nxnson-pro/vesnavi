package com.nxnson.vesnavi.navcore

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    const val EARTH_RADIUS = 6_371_000.0

    private fun rad(deg: Double) = deg * Math.PI / 180

    /** Great-circle distance in meters (haversine). */
    fun distance(a: Coordinate, b: Coordinate): Double {
        val dLat = rad(b.latitude - a.latitude)
        val dLon = rad(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(rad(a.latitude)) * cos(rad(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS * asin(min(1.0, sqrt(h)))
    }

    /** Running total of distances along the polyline; `result[i]` = meters from start to point i. */
    fun cumulativeDistances(points: List<Coordinate>): DoubleArray {
        if (points.isEmpty()) return DoubleArray(0)
        val result = DoubleArray(points.size)
        for (i in 1 until points.size) result[i] = result[i - 1] + distance(points[i - 1], points[i])
        return result
    }

    data class Projection(val t: Double, val distance: Double)

    /**
     * Projects `p` onto segment a→b using a local flat-earth approximation
     * (accurate to well under a meter at segment lengths we deal with).
     */
    fun project(p: Coordinate, a: Coordinate, b: Coordinate): Projection {
        val k = cos(rad(a.latitude))
        val bx = rad(b.longitude - a.longitude) * k * EARTH_RADIUS
        val by = rad(b.latitude - a.latitude) * EARTH_RADIUS
        val px = rad(p.longitude - a.longitude) * k * EARTH_RADIUS
        val py = rad(p.latitude - a.latitude) * EARTH_RADIUS
        val len2 = bx * bx + by * by
        val t = if (len2 > 0) max(0.0, min(1.0, (px * bx + py * by) / len2)) else 0.0
        val dx = px - t * bx
        val dy = py - t * by
        return Projection(t, sqrt(dx * dx + dy * dy))
    }

    /** Initial bearing a → b, degrees clockwise from north (0…360). */
    fun bearing(a: Coordinate, b: Coordinate): Double {
        val lat1 = rad(a.latitude)
        val lat2 = rad(b.latitude)
        val dLon = rad(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = atan2(y, x) * 180 / Math.PI
        return if (deg < 0) deg + 360 else deg
    }

    /** Signed difference `to − from` in −180…180 (+ = clockwise / right turn). */
    fun turn(from: Double, to: Double): Double {
        var d = (to - from) % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    fun interpolate(a: Coordinate, b: Coordinate, t: Double) = Coordinate(
        a.latitude + (b.latitude - a.latitude) * t,
        a.longitude + (b.longitude - a.longitude) * t,
    )
}
