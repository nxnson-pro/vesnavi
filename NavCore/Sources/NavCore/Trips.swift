import Foundation

/// One ride, built from the scooter's live data (engine on → engine off).
public struct TripSummary: Codable, Equatable, Sendable, Identifiable {
    public var id: UUID
    public var start: Date
    public var end: Date
    /// Integrated rear-wheel speed (falls back to the odometer delta when samples were missing).
    public var distanceKm: Double
    /// Time spent above walking speed.
    public var movingSeconds: Double
    /// Integrated from the ECU's fuel rate (L/h).
    public var fuelLiters: Double
    public var maxSpeedKmh: Double
    public var maxRPM: Int
    public var avgCoolantC: Double?
    public var maxCoolantC: Int?
    public var avgAmbientC: Double?
    public var startOdometerKm: Int?
    public var endOdometerKm: Int?

    public init(id: UUID = UUID(), start: Date) {
        self.id = id
        self.start = start
        end = start
        distanceKm = 0
        movingSeconds = 0
        fuelLiters = 0
        maxSpeedKmh = 0
        maxRPM = 0
    }

    public var duration: TimeInterval { end.timeIntervalSince(start) }
    /// Average while moving, like a trip computer.
    public var avgSpeedKmh: Double { movingSeconds > 0 ? distanceKm / (movingSeconds / 3600) : 0 }
    public var litersPer100Km: Double? { distanceKm >= 0.5 ? fuelLiters / distanceKm * 100 : nil }
}

/// Turns the telemetry stream into trips. Pure logic: the app feeds samples and persists the results.
public final class TripRecorder {
    /// Engine off for this long ends the trip (short stops at lights or a shop don't).
    public static let engineOffTimeout: TimeInterval = 180
    /// A gap between samples longer than this is not integrated (link lost, app suspended).
    static let maxSampleGap: TimeInterval = 5
    /// Trips shorter than this are discarded (engine started in the garage, etc.).
    static let minDistanceKm = 0.2

    public private(set) var current: TripSummary?
    private var lastSample: Date?
    private var lastEngineOn: Date?
    private var coolantSum = 0.0, coolantCount = 0
    private var ambientSum = 0.0, ambientCount = 0

    public init() {}

    /// Feed every telemetry sample; returns a trip when one has just ended.
    public func ingest(_ t: ScooterTelemetry, at now: Date) -> TripSummary? {
        let running = t.engineRunning
        if current == nil {
            guard running else { return nil }
            var trip = TripSummary(start: now)
            trip.startOdometerKm = t.odometerKm
            current = trip
            lastSample = now
        }
        guard var trip = current else { return nil }

        var dt = lastSample.map { now.timeIntervalSince($0) } ?? 0
        if dt < 0 || dt > Self.maxSampleGap { dt = 0 }
        lastSample = now

        let speed = t.rearWheelKmh ?? 0
        trip.distanceKm += speed * dt / 3600
        if speed > 3 { trip.movingSeconds += dt }
        trip.maxSpeedKmh = max(trip.maxSpeedKmh, speed)
        if running {
            lastEngineOn = now
            trip.fuelLiters += (t.fuelLitersPerHour ?? 0) * dt / 3600
            trip.maxRPM = max(trip.maxRPM, t.rpm ?? 0)
            if let c = t.coolantC {
                coolantSum += Double(c)
                coolantCount += 1
                trip.maxCoolantC = max(trip.maxCoolantC ?? c, c)
            }
        }
        if let a = t.ambientC {
            ambientSum += Double(a)
            ambientCount += 1
        }
        if let odo = t.odometerKm { trip.endOdometerKm = odo }
        trip.end = now
        current = trip

        if !running, let on = lastEngineOn, now.timeIntervalSince(on) >= Self.engineOffTimeout {
            return finish()
        }
        return nil
    }

    /// Ends the current trip (link lost, app closing); nil when there is nothing worth keeping.
    public func finish() -> TripSummary? {
        defer { reset() }
        guard var trip = current else { return nil }
        if let on = lastEngineOn { trip.end = on }  // don't count the parked minutes
        if coolantCount > 0 { trip.avgCoolantC = coolantSum / Double(coolantCount) }
        if ambientCount > 0 { trip.avgAmbientC = ambientSum / Double(ambientCount) }
        // Missed samples (app suspended, link dropped): the odometer still counted the kilometres.
        if let s = trip.startOdometerKm, let e = trip.endOdometerKm, Double(e - s) > trip.distanceKm + 1 {
            trip.distanceKm = Double(e - s)
        }
        return trip.distanceKm >= Self.minDistanceKm ? trip : nil
    }

    private func reset() {
        current = nil
        lastSample = nil
        lastEngineOn = nil
        coolantSum = 0
        coolantCount = 0
        ambientSum = 0
        ambientCount = 0
    }
}

/// Spots a fill-up from the fuel gauge: a rise of at least 2 bars that stays put
/// (the float sloshes while riding, so a reading must hold for a few seconds).
public struct RefuelDetector: Sendable {
    public static let stableSamples = 12  // ~3 s of data frames
    public static let minRise = 2

    public private(set) var stable: Int?
    private var candidate: Int?
    private var count = 0

    public init(stable: Int?) {
        self.stable = stable
    }

    /// Returns the new reading when it just became stable (`rose` = looks like a fill-up).
    public mutating func update(_ bars: Int) -> (bars: Int, rose: Bool)? {
        if bars == candidate {
            count += 1
        } else {
            candidate = bars
            count = 1
        }
        guard count >= Self.stableSamples, bars != stable else { return nil }
        let old = stable
        stable = bars
        return (bars, old.map { bars >= $0 + Self.minRise } ?? false)
    }
}

/// Fuel bookkeeping since the last fill-up.
public struct FuelState: Codable, Equatable, Sendable {
    /// GTS 300 HPE tank.
    public var tankLiters: Double = 8.5
    public var filledAt: Date?
    /// Fuel in the tank right after the fill (full tank, or a level the rider set by hand).
    public var litersAtFill: Double = 8.5
    public var odometerAtFill: Int?
    /// Measured by the recorded trips since the fill.
    public var litersMeasured: Double = 0
    public var kmMeasured: Double = 0
    public var lastOdometer: Int?
    /// Last stable fuel-gauge reading (optional fields so older saved files still decode).
    public var lastFuelBars: Int?
    /// Bars of a full tank on the TFT gauge (nil = 7, the 3-bit maximum).
    public var fuelBarsMax: Int?

    public init() {}

    public var barsMax: Int { fuelBarsMax ?? 7 }

    /// Litres implied by the TFT gauge.
    public func litersFromBars(_ bars: Int) -> Double {
        tankLiters * min(1, Double(bars) / Double(max(1, barsMax)))
    }

    public mutating func fill(toLiters liters: Double, at date: Date) {
        filledAt = date
        litersAtFill = min(max(0, liters), tankLiters)
        odometerAtFill = lastOdometer
        litersMeasured = 0
        kmMeasured = 0
    }

    public mutating func add(_ trip: TripSummary) {
        guard let filledAt, trip.end > filledAt else { return }
        litersMeasured += trip.fuelLiters
        kmMeasured += trip.distanceKm
        if let odo = trip.endOdometerKm { lastOdometer = odo }
    }
}

public struct FuelEstimate: Equatable, Sendable {
    public var litersLeft: Double
    public var fraction: Double
    public var litersPer100Km: Double
    /// Kilometres until the tank is empty.
    public var rangeKm: Double
    /// Average riding per day over the recent trips (nil without history).
    public var kmPerDay: Double?
    /// When the tank reaches the reserve at the usual riding pace.
    public var refuelBy: Date?
}

public enum FuelEstimator {
    /// Typical GTS 300 HPE consumption, used until enough trips are recorded.
    public static let defaultLitersPer100Km = 3.3
    /// Refuel when this much is left (the TFT's reserve light comes on around here).
    public static let reserveLiters = 1.5

    public static func estimate(_ s: FuelState, history: [TripSummary], now: Date) -> FuelEstimate? {
        let rate = consumption(s, history: history)
        let left: Double
        if s.filledAt != nil {
            // Kilometres ridden without the app (odometer moved more than the recorded trips).
            var unmeasuredKm = 0.0
            if let a = s.odometerAtFill, let b = s.lastOdometer {
                unmeasuredKm = max(0, Double(b - a) - s.kmMeasured)
            }
            let used = s.litersMeasured + unmeasuredKm * rate / 100
            left = max(0, s.litersAtFill - used)
        } else if let bars = s.lastFuelBars {
            // No fill-up recorded yet: go by the gauge.
            left = s.litersFromBars(bars)
        } else {
            return nil
        }
        let kmPerDay = averageKmPerDay(history, now: now)
        var refuelBy: Date?
        if let kmPerDay, kmPerDay > 0.5 {
            let kmToReserve = max(0, left - reserveLiters) / rate * 100
            refuelBy = now.addingTimeInterval(kmToReserve / kmPerDay * 86_400)
        }
        return FuelEstimate(litersLeft: left, fraction: s.tankLiters > 0 ? left / s.tankLiters : 0,
                            litersPer100Km: rate, rangeKm: left / rate * 100,
                            kmPerDay: kmPerDay, refuelBy: refuelBy)
    }

    /// L/100 km: this tank's measured trips, else the recent history, else the typical value.
    static func consumption(_ s: FuelState, history: [TripSummary]) -> Double {
        if s.kmMeasured >= 5, s.litersMeasured > 0 { return s.litersMeasured / s.kmMeasured * 100 }
        let recent = history.suffix(30)
        let km = recent.reduce(0) { $0 + $1.distanceKm }
        let liters = recent.reduce(0) { $0 + $1.fuelLiters }
        if km >= 5, liters > 0 { return liters / km * 100 }
        return defaultLitersPer100Km
    }

    /// Kilometres per day over the last 14 days of trips.
    static func averageKmPerDay(_ history: [TripSummary], now: Date) -> Double? {
        let window: TimeInterval = 14 * 86_400
        let recent = history.filter { now.timeIntervalSince($0.end) <= window }
        guard let first = recent.map(\.start).min() else { return nil }
        let days = max(1, now.timeIntervalSince(first) / 86_400)
        return recent.reduce(0) { $0 + $1.distanceKm } / days
    }
}
