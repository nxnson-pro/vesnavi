package com.nxnson.vesnavi.navcore

import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.roundToInt

/**
 * Where speed limits come from. All free: OpenStreetMap via the public Overpass API (no key),
 * optionally completed with Vietnam's default limits, or GraphHopper's route data (needs the free key).
 * `key` matches the iOS raw values.
 */
enum class SpeedLimitSource(val key: String) {
    OFF("off"),
    /** OSM `maxspeed` tags only. */
    OSM("osm"),
    /** OSM tags, else the Vietnamese default for that kind of road (marked as an estimate). */
    OSM_WITH_VIETNAM_DEFAULTS("osmWithVietnamDefaults"),
    /** GraphHopper route details (`max_speed`), else OSM + Vietnamese defaults. */
    GRAPH_HOPPER("graphHopper");

    companion object {
        fun fromKey(key: String?): SpeedLimitSource? = entries.firstOrNull { it.key == key }
    }
}

data class SpeedLimitResult(
    val kmh: Int,
    /** Inferred from the road type, not tagged on the road. */
    val estimated: Boolean,
    val roadName: String? = null,
)

/** OSM way as returned by Overpass (`out tags`). */
data class OSMWay(val id: Long, val tags: Map<String, String>)

object SpeedLimitResolver {
    /** Public Overpass endpoints, tried in order. */
    val overpassEndpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    /** Ways with a `highway` tag within `radius` metres. */
    fun overpassURL(c: Coordinate, radius: Int = 20, endpoint: String = overpassEndpoints[0]): String {
        val q = "[out:json][timeout:10];way(around:$radius,${c.latitude},${c.longitude})[highway];out tags;"
        return "$endpoint?data=${URLEncoder.encode(q, "UTF-8")}"
    }

    fun parseOverpass(data: ByteArray): List<OSMWay> {
        val elements = JSONObject(String(data, Charsets.UTF_8)).getJSONArray("elements")
        return (0 until elements.length()).mapNotNull { i ->
            val e = elements.getJSONObject(i)
            if (e.getString("type") != "way") return@mapNotNull null
            val tags = e.optJSONObject("tags")
            OSMWay(e.getLong("id"), tags?.keys()?.asSequence()?.associateWith { tags.getString(it) } ?: emptyMap())
        }
    }

    /**
     * Picks the way the rider is on (same name as the current street if known, else the most
     * important road nearby) and returns its limit for motorcycles.
     */
    fun resolve(ways: List<OSMWay>, street: String?, vietnamDefaults: Boolean): SpeedLimitResult? {
        val roads = ways.filter { rideable(it.tags["highway"]) }
        if (roads.isEmpty()) return null
        val wanted = street?.let(::normalize)
        val named = wanted?.let { w -> roads.filter { normalize(it.tags["name"] ?: "") == w } }
        val pool = if (!named.isNullOrEmpty()) named else roads
        val way = pool.maxByOrNull(::rank) ?: return null
        val name = way.tags["name"]
        for (key in listOf("maxspeed:motorcycle", "maxspeed")) {
            val v = way.tags[key]?.let(::parseMaxspeed)
            if (v != null) return SpeedLimitResult(v, estimated = false, roadName = name)
        }
        if (!vietnamDefaults) return null
        val v = vietnamDefault(way.tags) ?: return null
        return SpeedLimitResult(v, estimated = true, roadName = name)
    }

    /** "50", "50 km/h", "30 mph", "VN:urban"… */
    fun parseMaxspeed(raw: String): Int? {
        val s = raw.trim().lowercase()
        when (s) {
            "vn:urban" -> return 50
            "vn:rural" -> return 60
            "vn:motorway" -> return null // motorcycles are not allowed on expressways
        }
        val n = s.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        if (n <= 0) return null
        return if ("mph" in s) (n * 1.609).roundToInt() else n
    }

    /**
     * Default limits for motorcycles in built-up areas (Thông tư 38/2024/TT-BGTVT, Điều 6):
     * 60 km/h on divided roads / one-way roads with ≥ 2 lanes, 50 km/h otherwise.
     */
    fun vietnamDefault(tags: Map<String, String>): Int? {
        val highway = tags["highway"]
        if (!rideable(highway)) return null
        val lanes = tags["lanes"]?.toIntOrNull() ?: 1
        val oneway = tags["oneway"] == "yes"
        val divided = tags["dual_carriageway"] == "yes"
        return if (divided || (oneway && lanes >= 2)) 60 else 50
    }

    private val excluded = setOf(
        "motorway", "motorway_link", "footway", "path", "cycleway", "steps",
        "pedestrian", "track", "bridleway", "construction", "proposed", "corridor",
    )

    private fun rideable(highway: String?) = highway != null && highway !in excluded

    private val order = mapOf(
        "trunk" to 9, "primary" to 8, "secondary" to 7, "tertiary" to 6, "trunk_link" to 5, "primary_link" to 5,
        "secondary_link" to 4, "unclassified" to 3, "residential" to 2, "living_street" to 1, "service" to 0,
    )

    private fun rank(w: OSMWay): Int = (order[w.tags["highway"]] ?: 0) * 2 + if (w.tags["maxspeed"] != null) 1 else 0

    private val stopWords = setOf("duong", "pho", "street", "road")

    internal fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd')
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && it !in stopWords }
            .joinToString(" ")
}
