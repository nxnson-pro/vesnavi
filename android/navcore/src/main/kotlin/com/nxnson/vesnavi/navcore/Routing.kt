package com.nxnson.vesnavi.navcore

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Injected network call, so navcore stays free of HTTP clients and parsing can be tested offline. */
fun interface HttpFetcher {
    suspend fun fetch(url: String): ByteArray
}

interface RoutingProvider {
    val name: String
    suspend fun route(from: Coordinate, to: Coordinate): Route
}

sealed class RoutingError(private val debug: String) : Exception(debug) {
    object NoRoute : RoutingError("noRoute")
    object InvalidResponse : RoutingError("invalidResponse")
    data class Server(val msg: String) : RoutingError(msg)

    override val message: String get() = message(Language.VI)

    fun message(language: Language): String = when (this) {
        NoRoute -> language.pick("Không tìm được đường đi.", "No route found.")
        InvalidResponse -> language.pick("Dữ liệu dẫn đường trả về không hợp lệ.", "The routing service returned invalid data.")
        is Server -> language.pick("Lỗi máy chủ dẫn đường: $msg", "Routing server error: $msg")
    }
}

private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }

private fun JSONArray.toCoordinates(): List<Coordinate> = (0 until length()).mapNotNull { i ->
    val pair = getJSONArray(i)
    if (pair.length() >= 2) Coordinate(latitude = pair.getDouble(1), longitude = pair.getDouble(0)) else null
}

/**
 * OSRM — no API key needed. Default is the public demo server (car profile only,
 * "fair use", fine for personal testing). Can be pointed at a self-hosted server.
 */
class OSRMProvider(
    private val baseURL: String = "https://router.project-osrm.org",
    private val fetch: HttpFetcher,
) : RoutingProvider {
    override val name = "OSRM"

    fun url(from: Coordinate, to: Coordinate): String =
        "${baseURL.trimEnd('/')}/route/v1/driving/${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
            "?overview=false&steps=true&geometries=geojson"

    override suspend fun route(from: Coordinate, to: Coordinate): Route = parse(fetch.fetch(url(from, to)))

    companion object {
        fun parse(data: ByteArray): Route {
            val polyline = mutableListOf<Coordinate>()
            val steps = mutableListOf<RouteStep>()
            val distance: Double
            val duration: Double
            try {
                val json = JSONObject(String(data, Charsets.UTF_8))
                val code = json.getString("code")
                val routes = json.optJSONArray("routes")
                if (code != "Ok" || routes == null || routes.length() == 0) {
                    if (code == "NoRoute") throw RoutingError.NoRoute
                    throw RoutingError.Server(json.optStringOrNull("message") ?: code)
                }
                val r = routes.getJSONObject(0)
                distance = r.getDouble("distance")
                duration = r.getDouble("duration")
                val legs = r.getJSONArray("legs")
                // Stitch step geometries into one polyline; each step starts where the previous ended.
                for (l in 0 until legs.length()) {
                    val legSteps = legs.getJSONObject(l).getJSONArray("steps")
                    for (s in 0 until legSteps.length()) {
                        val step = legSteps.getJSONObject(s)
                        val coords = step.getJSONObject("geometry").getJSONArray("coordinates").toCoordinates()
                        val index = if (polyline.isEmpty()) {
                            polyline += coords
                            0
                        } else {
                            val i = polyline.size - 1
                            polyline += coords.drop(1)
                            i
                        }
                        steps += RouteStep(maneuver(step.getJSONObject("maneuver")), step.optStringOrNull("name"), max(0, index))
                    }
                }
            } catch (e: JSONException) {
                throw RoutingError.InvalidResponse
            }
            if (polyline.size < 2) throw RoutingError.NoRoute
            return RouteAnnotator.annotateRoundabouts(Route(polyline, steps, distance, duration, "OSRM"))
        }

        internal fun maneuver(m: JSONObject): Maneuver {
            val type = m.getString("type")
            val modifier = m.optStringOrNull("modifier")
            when (type) {
                "depart" -> return Maneuver.Depart
                "arrive" -> return Maneuver.Arrive
                "roundabout", "rotary", "roundabout turn" ->
                    return Maneuver.Roundabout(if (m.has("exit") && !m.isNull("exit")) m.getInt("exit") else null)
                "exit roundabout", "exit rotary" -> return Maneuver.Straight
            }
            val isFork = type == "fork" || type == "merge" || type.endsWith("ramp")
            return when (modifier) {
                "uturn" -> Maneuver.UTurnLeft
                "sharp left" -> Maneuver.SharpLeft
                "left" -> Maneuver.Left
                "slight left" -> if (isFork) Maneuver.KeepLeft else Maneuver.SlightLeft
                "slight right" -> if (isFork) Maneuver.KeepRight else Maneuver.SlightRight
                "right" -> Maneuver.Right
                "sharp right" -> Maneuver.SharpRight
                else -> Maneuver.Straight
            }
        }
    }
}

/**
 * GraphHopper Directions API (OpenStreetMap data). Free plan: ~500 requests/day.
 * Docs: https://docs.graphhopper.com/#operation/getRoute
 */
class GraphHopperProvider(
    private val apiKey: String,
    /** e.g. "car", "scooter". The free plan only offers car, bike and foot. */
    val profile: String = "car",
    private val fetch: HttpFetcher,
) : RoutingProvider {
    override val name get() = "GraphHopper ($profile)"

    fun url(from: Coordinate, to: Coordinate): String =
        "https://graphhopper.com/api/1/route?point=${from.latitude},${from.longitude}" +
            "&point=${to.latitude},${to.longitude}&profile=${encode(profile)}&locale=vi&instructions=true" +
            "&points_encoded=false&details=max_speed&key=${encode(apiKey)}"

    override suspend fun route(from: Coordinate, to: Coordinate): Route = parse(fetch.fetch(url(from, to)), name)

    companion object {
        fun parse(data: ByteArray, provider: String = "GraphHopper"): Route {
            try {
                val json = JSONObject(String(data, Charsets.UTF_8))
                val paths = json.optJSONArray("paths")
                if (paths == null || paths.length() == 0) {
                    json.optStringOrNull("message")?.let { throw RoutingError.Server(it) }
                    throw RoutingError.NoRoute
                }
                val path = paths.getJSONObject(0)
                val polyline = path.getJSONObject("points").getJSONArray("coordinates").toCoordinates()
                if (polyline.size < 2) throw RoutingError.NoRoute
                val instructions = path.getJSONArray("instructions")
                val steps = (0 until instructions.length()).map { i ->
                    val ins = instructions.getJSONObject(i)
                    val interval = ins.getJSONArray("interval")
                    val exit = if (ins.has("exit_number") && !ins.isNull("exit_number")) ins.getInt("exit_number") else null
                    RouteStep(
                        maneuver = if (i == 0) Maneuver.Depart else maneuver(ins.getInt("sign"), exit),
                        streetName = ins.optStringOrNull("street_name"),
                        pointIndex = min(if (interval.length() > 0) interval.getInt(0) else 0, polyline.size - 1),
                    )
                }
                // details.max_speed: [[fromIndex, toIndex, km/h or null], …]
                val rows = path.optJSONObject("details")?.optJSONArray("max_speed")
                val limits = if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { i ->
                    val row = rows.getJSONArray(i)
                    if (row.length() < 3 || row.isNull(0) || row.isNull(1) || row.isNull(2)) return@mapNotNull null
                    val kmh = row.getDouble(2)
                    if (kmh > 0) SpeedLimitSpan(row.getInt(0), row.getInt(1), kmh.roundToInt()) else null
                }
                return RouteAnnotator.annotateRoundabouts(
                    Route(polyline, steps, path.getDouble("distance"), path.getDouble("time") / 1000, provider, limits),
                )
            } catch (e: JSONException) {
                throw RoutingError.InvalidResponse
            }
        }

        /** GraphHopper "sign" codes → Maneuver. */
        fun maneuver(sign: Int, exit: Int?): Maneuver = when (sign) {
            -98, -8 -> Maneuver.UTurnLeft
            -7 -> Maneuver.KeepLeft
            -3 -> Maneuver.SharpLeft
            -2 -> Maneuver.Left
            -1 -> Maneuver.SlightLeft
            1 -> Maneuver.SlightRight
            2 -> Maneuver.Right
            3 -> Maneuver.SharpRight
            4 -> Maneuver.Arrive
            6 -> Maneuver.Roundabout(exit)
            7 -> Maneuver.KeepRight
            8 -> Maneuver.UTurnRight
            else -> Maneuver.Straight // 0 continue, 5 via point, -6 leave roundabout
        }
    }
}

/** Post-processing shared by every routing provider. */
object RouteAnnotator {
    /** How far before the entry / after the exit we look to get a stable road direction. */
    private const val PROBE_METERS = 25.0

    /**
     * Fills `exitTurnDegrees` for roundabout steps: the direction of the road we leave on,
     * relative to the road we arrive on. The exit point is where the next step starts.
     */
    fun annotateRoundabouts(route: Route): Route {
        val pts = route.polyline
        if (pts.size < 2) return route
        val steps = route.steps.mapIndexed { i, step ->
            if (step.maneuver !is Maneuver.Roundabout || i + 1 >= route.steps.size) return@mapIndexed step
            val entry = step.pointIndex
            val exit = route.steps[i + 1].pointIndex
            if (exit <= entry || entry <= 0 || exit >= pts.size - 1) return@mapIndexed step
            val before = point(pts, entry, PROBE_METERS, forward = false)
            val after = point(pts, exit, PROBE_METERS, forward = true)
            step.copy(exitTurnDegrees = Geo.turn(Geo.bearing(before, pts[entry]), Geo.bearing(pts[exit], after)))
        }
        return route.copy(steps = steps)
    }

    /** Walks the polyline from `index` until `meters` are covered (or the end is reached). */
    private fun point(pts: List<Coordinate>, index: Int, meters: Double, forward: Boolean): Coordinate {
        var covered = 0.0
        var i = index
        while (true) {
            val j = if (forward) i + 1 else i - 1
            if (j < 0 || j >= pts.size) return pts[i]
            covered += Geo.distance(pts[i], pts[j])
            if (covered >= meters) return pts[j]
            i = j
        }
    }

    /**
     * TFT roundabout pictogram for right-hand traffic (counter-clockwise): 100 + exit direction —
     * 0 U-turn, 1 sharp right, 2 right, 3 slight right, 4 straight, 5 slight left, 6 left, 7 sharp left.
     */
    fun roundaboutIcon(turnDegrees: Double): Int {
        val a = abs(turnDegrees)
        val n = when {
            a > 157.5 -> 0
            a <= 22.5 -> 4
            turnDegrees > 0 -> if (a > 112.5) 1 else if (a > 67.5) 2 else 3
            else -> if (a > 112.5) 7 else if (a > 67.5) 6 else 5
        }
        return 100 + n
    }
}
