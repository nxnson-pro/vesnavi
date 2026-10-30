package com.nxnson.vesnavi.navcore

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON for everything the app saves on the device (trips, fuel, service items, places).
 * Missing keys fall back to defaults, so files written by older versions still load.
 */
object Persistence {
    // Helpers

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.putOpt(key: String, value: Any?): JSONObject = if (value == null) this else put(key, value)

    private fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

    private fun <T> List<T>.toArray(f: (T) -> JSONObject) = JSONArray().also { a -> forEach { a.put(f(it)) } }

    // Trips + fuel

    fun trip(t: TripSummary): JSONObject = JSONObject()
        .put("id", t.id).put("start", t.start).put("end", t.end)
        .put("distanceKm", t.distanceKm).put("movingSeconds", t.movingSeconds).put("fuelLiters", t.fuelLiters)
        .put("maxSpeedKmh", t.maxSpeedKmh).put("maxRPM", t.maxRPM)
        .putOpt("avgCoolantC", t.avgCoolantC).putOpt("maxCoolantC", t.maxCoolantC).putOpt("avgAmbientC", t.avgAmbientC)
        .putOpt("startOdometerKm", t.startOdometerKm).putOpt("endOdometerKm", t.endOdometerKm)

    fun trip(o: JSONObject) = TripSummary(
        id = o.getString("id"),
        start = o.getLong("start"),
        end = o.getLong("end"),
        distanceKm = o.dbl("distanceKm") ?: 0.0,
        movingSeconds = o.dbl("movingSeconds") ?: 0.0,
        fuelLiters = o.dbl("fuelLiters") ?: 0.0,
        maxSpeedKmh = o.dbl("maxSpeedKmh") ?: 0.0,
        maxRPM = o.int("maxRPM") ?: 0,
        avgCoolantC = o.dbl("avgCoolantC"),
        maxCoolantC = o.int("maxCoolantC"),
        avgAmbientC = o.dbl("avgAmbientC"),
        startOdometerKm = o.int("startOdometerKm"),
        endOdometerKm = o.int("endOdometerKm"),
    )

    fun fuel(f: FuelState): JSONObject = JSONObject()
        .put("tankLiters", f.tankLiters).putOpt("filledAt", f.filledAt).put("litersAtFill", f.litersAtFill)
        .putOpt("odometerAtFill", f.odometerAtFill).put("litersMeasured", f.litersMeasured).put("kmMeasured", f.kmMeasured)
        .putOpt("lastOdometer", f.lastOdometer).putOpt("lastFuelBars", f.lastFuelBars).putOpt("fuelBarsMax", f.fuelBarsMax)

    fun fuel(o: JSONObject): FuelState {
        val d = FuelState()
        return FuelState(
            tankLiters = o.dbl("tankLiters") ?: d.tankLiters,
            filledAt = o.long("filledAt"),
            litersAtFill = o.dbl("litersAtFill") ?: d.litersAtFill,
            odometerAtFill = o.int("odometerAtFill"),
            litersMeasured = o.dbl("litersMeasured") ?: 0.0,
            kmMeasured = o.dbl("kmMeasured") ?: 0.0,
            lastOdometer = o.int("lastOdometer"),
            lastFuelBars = o.int("lastFuelBars"),
            fuelBarsMax = o.int("fuelBarsMax"),
        )
    }

    data class TripsFile(val trips: List<TripSummary> = emptyList(), val fuel: FuelState = FuelState())

    fun encodeTrips(f: TripsFile): String =
        JSONObject().put("trips", f.trips.toArray(::trip)).put("fuel", fuel(f.fuel)).toString()

    fun decodeTrips(s: String): TripsFile {
        val o = JSONObject(s)
        return TripsFile(o.optJSONArray("trips").mapObjects(::trip), o.optJSONObject("fuel")?.let(::fuel) ?: FuelState())
    }

    // Maintenance

    fun item(i: MaintenanceItem): JSONObject = JSONObject()
        .put("id", i.id).put("name", i.name).putOpt("intervalKm", i.intervalKm).putOpt("intervalMonths", i.intervalMonths)
        .put("tftText", i.tftText).putOpt("lastKm", i.lastKm).putOpt("lastDate", i.lastDate)

    fun item(o: JSONObject) = MaintenanceItem(
        id = o.getString("id"),
        name = o.getString("name"),
        intervalKm = o.int("intervalKm"),
        intervalMonths = o.int("intervalMonths"),
        tftText = o.str("tftText") ?: "",
        lastKm = o.int("lastKm"),
        lastDate = o.long("lastDate"),
    )

    data class MaintenanceFile(val items: List<MaintenanceItem> = MaintenanceItem.defaults, val odometer: Int? = null)

    fun encodeMaintenance(f: MaintenanceFile): String =
        JSONObject().put("items", f.items.toArray(::item)).putOpt("odometer", f.odometer).toString()

    /** Built-in items added in later versions are appended. */
    fun decodeMaintenance(s: String): MaintenanceFile {
        val o = JSONObject(s)
        val saved = o.optJSONArray("items").mapObjects(::item)
        val items = saved + MaintenanceItem.defaults.filter { d -> saved.none { it.id == d.id } }
        return MaintenanceFile(items, o.int("odometer"))
    }

    // Places

    fun place(p: SavedPlace): JSONObject = JSONObject()
        .put("id", p.id).put("name", p.name).putOpt("subtitle", p.subtitle).put("latitude", p.latitude).put("longitude", p.longitude)

    fun place(o: JSONObject) = SavedPlace(
        name = o.getString("name"),
        subtitle = o.str("subtitle"),
        latitude = o.getDouble("latitude"),
        longitude = o.getDouble("longitude"),
        id = o.getString("id"),
    )

    fun encodePlaces(p: Places): String {
        val o = JSONObject()
            .putOpt("home", p.home?.let(::place)).putOpt("work", p.work?.let(::place))
            .put("favorites", p.favorites.toArray(::place)).put("recents", p.recents.toArray(::place))
        p.parking?.let {
            o.put("parking", JSONObject().put("latitude", it.latitude).put("longitude", it.longitude).put("date", it.date).putOpt("accuracy", it.accuracy))
        }
        return o.toString()
    }

    fun decodePlaces(s: String): Places {
        val o = JSONObject(s)
        return Places(
            home = o.optJSONObject("home")?.let(::place),
            work = o.optJSONObject("work")?.let(::place),
            favorites = o.optJSONArray("favorites").mapObjects(::place),
            recents = o.optJSONArray("recents").mapObjects(::place),
            parking = o.optJSONObject("parking")?.let {
                ParkingSpot(it.getDouble("latitude"), it.getDouble("longitude"), it.getLong("date"), it.dbl("accuracy"))
            },
        )
    }

    // PMP3 pairing

    fun pairing(p: PMP3Pairing): String =
        JSONObject().put("deviceID", Hex.encode(p.deviceID, "")).putOpt("key", p.key?.let { Hex.encode(it, "") }).toString()

    fun pairing(s: String): PMP3Pairing? = runCatching {
        val o = JSONObject(s)
        val id = Hex.decode(o.getString("deviceID")) ?: return null
        PMP3Pairing(id, o.str("key")?.let(Hex::decode))
    }.getOrNull()
}
