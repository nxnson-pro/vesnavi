package com.nxnson.vesnavi.navcore

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** A search hit the rider can route to. */
data class SearchResult(val name: String, val subtitle: String?, val coordinate: Coordinate)

/**
 * Address / place search on OpenStreetMap data through the public Photon API (komoot, free, no key).
 * Results near the rider rank first.
 */
object PlaceSearch {
    const val PHOTON_URL = "https://photon.komoot.io/api/"

    fun photonURL(query: String, near: Coordinate?, limit: Int = 12): String {
        val bias = near?.let { String.format(Locale.US, "&lat=%.5f&lon=%.5f", it.latitude, it.longitude) } ?: ""
        return "$PHOTON_URL?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=$limit$bias"
    }

    /** GeoJSON features → results; the subtitle is the street address and area, without repeating the name. */
    fun parsePhoton(data: ByteArray): List<SearchResult> {
        val features = JSONObject(String(data, Charsets.UTF_8)).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            if (coords.length() < 2) return@mapNotNull null
            val p = f.optJSONObject("properties") ?: JSONObject()
            fun prop(key: String) = p.optString(key).trim().ifEmpty { null }
            val street = listOfNotNull(prop("housenumber"), prop("street")).joinToString(" ").ifEmpty { null }
            val name = prop("name") ?: street ?: return@mapNotNull null
            val parts = listOfNotNull(street, prop("district"), prop("city") ?: prop("county"), prop("state"))
                .filter { it != name }
                .distinct()
            SearchResult(name, parts.joinToString(", ").ifEmpty { null }, Coordinate(coords.getDouble(1), coords.getDouble(0)))
        }
    }
}
