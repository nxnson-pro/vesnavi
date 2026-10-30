package com.nxnson.vesnavi

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nxnson.vesnavi.navcore.FuelEstimate
import com.nxnson.vesnavi.navcore.FuelEstimator
import com.nxnson.vesnavi.navcore.FuelState
import com.nxnson.vesnavi.navcore.MaintenanceItem
import com.nxnson.vesnavi.navcore.MaintenancePlanner
import com.nxnson.vesnavi.navcore.MaintenanceStatus
import com.nxnson.vesnavi.navcore.ParkingSpot
import com.nxnson.vesnavi.navcore.Persistence
import com.nxnson.vesnavi.navcore.Places
import com.nxnson.vesnavi.navcore.RefuelDetector
import com.nxnson.vesnavi.navcore.SavedPlace
import com.nxnson.vesnavi.navcore.ScooterTelemetry
import com.nxnson.vesnavi.navcore.TripRecorder
import com.nxnson.vesnavi.navcore.TripSummary

/** Recorded trips + fuel bookkeeping, saved as JSON in the app's private storage (on-device only). */
class TripStore(context: Context) {
    private val file = JsonFile(context, "trips.json")
    private val recorder = TripRecorder()

    var trips by mutableStateOf<List<TripSummary>>(emptyList())
        private set
    var fuel by mutableStateOf(FuelState())
        private set
    /** The ride being recorded right now (live). */
    var current by mutableStateOf<TripSummary?>(null)
        private set
    /** Set when the fuel gauge showed a fill-up (shown once in the app). */
    var refuelDetectedAt by mutableStateOf<Long?>(null)
    /** Recomputed when the inputs change, not on every recomposition (it scans the trip history). */
    var fuelEstimate by mutableStateOf<FuelEstimate?>(null)
        private set

    private var gauge: RefuelDetector
    private var lastPublish = 0L

    init {
        file.read()?.let { runCatching { Persistence.decodeTrips(it) }.getOrNull() }?.let {
            trips = it.trips
            fuel = it.fuel
        }
        gauge = RefuelDetector(fuel.lastFuelBars)
        refreshEstimate()
    }

    private fun refreshEstimate() {
        val e = FuelEstimator.estimate(fuel, trips, System.currentTimeMillis())
        if (e != fuelEstimate) fuelEstimate = e
    }

    fun ingest(t: ScooterTelemetry) {
        val now = System.currentTimeMillis()
        val odo = t.odometerKm
        if (odo != null && odo != fuel.lastOdometer) {
            fuel = fuel.copy(lastOdometer = odo)
            refreshEstimate()
        }
        // Only read the gauge standing still: the float sloshes while riding.
        val bars = t.fuelBars
        if (bars != null && (t.rearWheelKmh ?: 0.0) < 1) {
            gauge.update(bars)?.let { reading ->
                fuel = fuel.copy(lastFuelBars = reading.bars)
                if (reading.rose) {
                    // The gauge jumped up → the tank was filled.
                    fuel = fuel.fill(fuel.litersFromBars(reading.bars), now)
                    refuelDetectedAt = now
                }
                save()
            }
        }
        val finished = recorder.ingest(t, now)
        if (finished != null) {
            store(finished)
        } else if (now - lastPublish >= 2_000 || (current == null) != (recorder.current == null)) {
            // The live trip card doesn't need 4 updates a second.
            lastPublish = now
            current = recorder.current
        }
    }

    /** The data link ended (ignition off, out of range): close the trip. */
    fun linkEnded() {
        recorder.finish()?.let(::store)
        current = null
    }

    fun filledUp() {
        fuel = fuel.fill(fuel.tankLiters, System.currentTimeMillis())
        save()
    }

    /** The rider read the gauge: set the current level (0…1 of the tank), tank size and gauge scale. */
    fun setFuel(fraction: Double, tankLiters: Double, barsMax: Int) {
        fuel = fuel.copy(tankLiters = tankLiters, fuelBarsMax = barsMax).let { it.fill(it.tankLiters * fraction, System.currentTimeMillis()) }
        save()
    }

    fun delete(id: String) {
        trips = trips.filterNot { it.id == id }
        save()
    }

    private fun store(trip: TripSummary) {
        trips = (trips + trip).takeLast(1000)
        fuel = fuel.add(trip)
        current = null
        save()
    }

    private fun save() {
        refreshEstimate()
        file.write(Persistence.encodeTrips(Persistence.TripsFile(trips, fuel)))
    }
}

/** Service items and the last known odometer (from the scooter), saved on the device. */
class MaintenanceStore(context: Context) {
    private val file = JsonFile(context, "maintenance.json")

    var items by mutableStateOf(MaintenanceItem.defaults)
        private set
    /** Latest odometer seen from the scooter (kept when disconnected). */
    var odometer by mutableStateOf<Int?>(null)
        private set

    init {
        file.read()?.let { runCatching { Persistence.decodeMaintenance(it) }.getOrNull() }?.let {
            items = it.items
            odometer = it.odometer
        }
    }

    data class Entry(val item: MaintenanceItem, val status: MaintenanceStatus)

    fun status(item: MaintenanceItem) = MaintenancePlanner.status(item, odometer, System.currentTimeMillis())

    /** Most urgent first (due, soon, ok, unknown). */
    val sorted: List<Entry>
        get() = items.map { Entry(it, status(it)) }
            .sortedWith(compareByDescending<Entry> { it.status.level }.thenByDescending { it.status.progress })

    val dueItems: List<MaintenanceItem> get() = sorted.filter { it.status.level == MaintenanceStatus.Level.DUE }.map { it.item }

    fun updateOdometer(km: Int) {
        // The odometer only changes once per kilometre, so saving on change is cheap.
        if (km == odometer) return
        odometer = km
        save()
    }

    fun markDone(id: String, atKm: Int?, on: Long = System.currentTimeMillis()) =
        update(id) { it.copy(lastKm = atKm ?: odometer, lastDate = on) }

    fun update(id: String, change: (MaintenanceItem) -> MaintenanceItem) {
        items = items.map { if (it.id == id) change(it) else it }
        save()
    }

    private fun save() = file.write(Persistence.encodeMaintenance(Persistence.MaintenanceFile(items, odometer)))
}

/** Home, work, favourites, recent destinations and the parking spot — saved on the device. */
class PlacesStore(context: Context) {
    private val file = JsonFile(context, "places.json")

    var places by mutableStateOf(Places())
        private set

    init {
        file.read()?.let { runCatching { Persistence.decodePlaces(it) }.getOrNull() }?.let { places = it }
    }

    private fun change(f: (Places) -> Places) {
        places = f(places)
        file.write(Persistence.encodePlaces(places))
    }

    fun setHome(p: SavedPlace?) = change { it.copy(home = p) }
    fun setWork(p: SavedPlace?) = change { it.copy(work = p) }
    fun toggleFavorite(p: SavedPlace) = change { it.toggleFavorite(p) }
    fun addRecent(p: SavedPlace) = change { it.addRecent(p) }
    fun removeRecent(id: String) = change { it.removeRecent(id) }
    fun removeFavorite(id: String) = change { it.removeFavorite(id) }
    fun setParking(latitude: Double, longitude: Double, at: Long = System.currentTimeMillis(), accuracy: Double? = null) =
        change { it.copy(parking = ParkingSpot(latitude, longitude, at, accuracy)) }
    fun clearParking() = change { it.copy(parking = null) }
}
