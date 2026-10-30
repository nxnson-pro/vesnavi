package com.nxnson.vesnavi.navcore

import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** One ride, built from the scooter's live data (engine on → engine off). Times are epoch millis. */
data class TripSummary(
    val id: String = UUID.randomUUID().toString(),
    val start: Long,
    val end: Long = start,
    /** Integrated rear-wheel speed (falls back to the odometer delta when samples were missing). */
    val distanceKm: Double = 0.0,
    /** Time spent above walking speed. */
    val movingSeconds: Double = 0.0,
    /** Integrated from the ECU's fuel rate (L/h). */
    val fuelLiters: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val maxRPM: Int = 0,
    val avgCoolantC: Double? = null,
    val maxCoolantC: Int? = null,
    val avgAmbientC: Double? = null,
    val startOdometerKm: Int? = null,
    val endOdometerKm: Int? = null,
) {
    /** Seconds. */
    val duration: Double get() = (end - start) / 1000.0
    /** Average while moving, like a trip computer. */
    val avgSpeedKmh: Double get() = if (movingSeconds > 0) distanceKm / (movingSeconds / 3600) else 0.0
    val litersPer100Km: Double? get() = if (distanceKm >= 0.5) fuelLiters / distanceKm * 100 else null
}

/** Turns the telemetry stream into trips. Pure logic: the app feeds samples and persists the results. */
class TripRecorder {
    companion object {
        /** Engine off for this long ends the trip (short stops at lights or a shop don't). */
        const val ENGINE_OFF_TIMEOUT_MS = 180_000L
        /** A gap between samples longer than this is not integrated (link lost, app suspended). */
        private const val MAX_SAMPLE_GAP_S = 5.0
        /** Trips shorter than this are discarded (engine started in the garage, etc.). */
        private const val MIN_DISTANCE_KM = 0.2
    }

    var current: TripSummary? = null
        private set
    private var lastSample: Long? = null
    private var lastEngineOn: Long? = null
    private var coolantSum = 0.0
    private var coolantCount = 0
    private var ambientSum = 0.0
    private var ambientCount = 0

    /** Feed every telemetry sample; returns a trip when one has just ended. */
    fun ingest(t: ScooterTelemetry, now: Long): TripSummary? {
        val running = t.engineRunning
        if (current == null) {
            if (!running) return null
            current = TripSummary(start = now, startOdometerKm = t.odometerKm)
            lastSample = now
        }
        var trip = current ?: return null

        var dt = lastSample?.let { (now - it) / 1000.0 } ?: 0.0
        if (dt < 0 || dt > MAX_SAMPLE_GAP_S) dt = 0.0
        lastSample = now

        val speed = t.rearWheelKmh ?: 0.0
        trip = trip.copy(
            distanceKm = trip.distanceKm + speed * dt / 3600,
            movingSeconds = trip.movingSeconds + if (speed > 3) dt else 0.0,
            maxSpeedKmh = max(trip.maxSpeedKmh, speed),
        )
        if (running) {
            lastEngineOn = now
            trip = trip.copy(
                fuelLiters = trip.fuelLiters + (t.fuelLitersPerHour ?: 0.0) * dt / 3600,
                maxRPM = max(trip.maxRPM, t.rpm ?: 0),
            )
            t.coolantC?.let { c ->
                coolantSum += c
                coolantCount++
                trip = trip.copy(maxCoolantC = max(trip.maxCoolantC ?: c, c))
            }
        }
        t.ambientC?.let {
            ambientSum += it
            ambientCount++
        }
        trip = trip.copy(endOdometerKm = t.odometerKm ?: trip.endOdometerKm, end = now)
        current = trip

        val on = lastEngineOn
        if (!running && on != null && now - on >= ENGINE_OFF_TIMEOUT_MS) return finish()
        return null
    }

    /** Ends the current trip (link lost, app closing); null when there is nothing worth keeping. */
    fun finish(): TripSummary? {
        var trip = current
        val on = lastEngineOn
        val result = if (trip == null) {
            null
        } else {
            if (on != null) trip = trip.copy(end = on) // don't count the parked minutes
            if (coolantCount > 0) trip = trip.copy(avgCoolantC = coolantSum / coolantCount)
            if (ambientCount > 0) trip = trip.copy(avgAmbientC = ambientSum / ambientCount)
            // Missed samples (app suspended, link dropped): the odometer still counted the kilometres.
            val s = trip.startOdometerKm
            val e = trip.endOdometerKm
            if (s != null && e != null && (e - s).toDouble() > trip.distanceKm + 1) trip = trip.copy(distanceKm = (e - s).toDouble())
            if (trip.distanceKm >= MIN_DISTANCE_KM) trip else null
        }
        reset()
        return result
    }

    private fun reset() {
        current = null
        lastSample = null
        lastEngineOn = null
        coolantSum = 0.0
        coolantCount = 0
        ambientSum = 0.0
        ambientCount = 0
    }
}

/**
 * Spots a fill-up from the fuel gauge: a rise of at least 2 bars that stays put
 * (the float sloshes while riding, so a reading must hold for a few seconds).
 */
class RefuelDetector(stable: Int?) {
    companion object {
        const val STABLE_SAMPLES = 12 // ~3 s of data frames
        const val MIN_RISE = 2
    }

    data class Reading(val bars: Int, val rose: Boolean)

    var stable: Int? = stable
        private set
    private var candidate: Int? = null
    private var count = 0

    /** Returns the new reading when it just became stable (`rose` = looks like a fill-up). */
    fun update(bars: Int): Reading? {
        if (bars == candidate) {
            count++
        } else {
            candidate = bars
            count = 1
        }
        if (count < STABLE_SAMPLES || bars == stable) return null
        val old = stable
        stable = bars
        return Reading(bars, old?.let { bars >= it + MIN_RISE } ?: false)
    }
}

/** Fuel bookkeeping since the last fill-up. */
data class FuelState(
    /** GTS 300 HPE tank. */
    val tankLiters: Double = 8.5,
    val filledAt: Long? = null,
    /** Fuel in the tank right after the fill (full tank, or a level the rider set by hand). */
    val litersAtFill: Double = 8.5,
    val odometerAtFill: Int? = null,
    /** Measured by the recorded trips since the fill. */
    val litersMeasured: Double = 0.0,
    val kmMeasured: Double = 0.0,
    val lastOdometer: Int? = null,
    /** Last stable fuel-gauge reading. */
    val lastFuelBars: Int? = null,
    /** Bars of a full tank on the TFT gauge (null = 7, the 3-bit maximum). */
    val fuelBarsMax: Int? = null,
) {
    val barsMax: Int get() = fuelBarsMax ?: 7

    /** Litres implied by the TFT gauge. */
    fun litersFromBars(bars: Int): Double = tankLiters * min(1.0, bars.toDouble() / max(1, barsMax))

    fun fill(toLiters: Double, at: Long): FuelState = copy(
        filledAt = at,
        litersAtFill = min(max(0.0, toLiters), tankLiters),
        odometerAtFill = lastOdometer,
        litersMeasured = 0.0,
        kmMeasured = 0.0,
    )

    fun add(trip: TripSummary): FuelState {
        val filled = filledAt ?: return this
        if (trip.end <= filled) return this
        return copy(
            litersMeasured = litersMeasured + trip.fuelLiters,
            kmMeasured = kmMeasured + trip.distanceKm,
            lastOdometer = trip.endOdometerKm ?: lastOdometer,
        )
    }
}

data class FuelEstimate(
    val litersLeft: Double,
    val fraction: Double,
    val litersPer100Km: Double,
    /** Kilometres until the tank is empty. */
    val rangeKm: Double,
    /** Average riding per day over the recent trips (null without history). */
    val kmPerDay: Double?,
    /** When the tank reaches the reserve at the usual riding pace (epoch millis). */
    val refuelBy: Long?,
)

object FuelEstimator {
    /** Typical GTS 300 HPE consumption, used until enough trips are recorded. */
    const val DEFAULT_LITERS_PER_100KM = 3.3
    /** Refuel when this much is left (the TFT's reserve light comes on around here). */
    const val RESERVE_LITERS = 1.5
    private const val DAY_MS = 86_400_000.0

    fun estimate(s: FuelState, history: List<TripSummary>, now: Long): FuelEstimate? {
        val rate = consumption(s, history)
        val left = when {
            s.filledAt != null -> {
                // Kilometres ridden without the app (odometer moved more than the recorded trips).
                val a = s.odometerAtFill
                val b = s.lastOdometer
                val unmeasuredKm = if (a != null && b != null) max(0.0, (b - a) - s.kmMeasured) else 0.0
                max(0.0, s.litersAtFill - (s.litersMeasured + unmeasuredKm * rate / 100))
            }
            // No fill-up recorded yet: go by the gauge.
            s.lastFuelBars != null -> s.litersFromBars(s.lastFuelBars)
            else -> return null
        }
        val kmPerDay = averageKmPerDay(history, now)
        val refuelBy = if (kmPerDay != null && kmPerDay > 0.5) {
            val kmToReserve = max(0.0, left - RESERVE_LITERS) / rate * 100
            now + (kmToReserve / kmPerDay * DAY_MS).toLong()
        } else {
            null
        }
        return FuelEstimate(
            litersLeft = left,
            fraction = if (s.tankLiters > 0) left / s.tankLiters else 0.0,
            litersPer100Km = rate,
            rangeKm = left / rate * 100,
            kmPerDay = kmPerDay,
            refuelBy = refuelBy,
        )
    }

    /** L/100 km: this tank's measured trips, else the recent history, else the typical value. */
    internal fun consumption(s: FuelState, history: List<TripSummary>): Double {
        if (s.kmMeasured >= 5 && s.litersMeasured > 0) return s.litersMeasured / s.kmMeasured * 100
        val recent = history.takeLast(30)
        val km = recent.sumOf { it.distanceKm }
        val liters = recent.sumOf { it.fuelLiters }
        if (km >= 5 && liters > 0) return liters / km * 100
        return DEFAULT_LITERS_PER_100KM
    }

    /** Kilometres per day over the last 14 days of trips. */
    internal fun averageKmPerDay(history: List<TripSummary>, now: Long): Double? {
        val recent = history.filter { now - it.end <= 14 * DAY_MS }
        val first = recent.minOfOrNull { it.start } ?: return null
        val days = max(1.0, (now - first) / DAY_MS)
        return recent.sumOf { it.distanceKm } / days
    }
}
