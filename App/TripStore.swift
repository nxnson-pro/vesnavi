import Foundation
import Observation
import NavCore

/// Recorded trips + fuel bookkeeping, saved as JSON in Application Support (on-device only).
@MainActor
@Observable
final class TripStore {
    private(set) var trips: [TripSummary] = []
    private(set) var fuel = FuelState()
    /// The ride being recorded right now (live).
    private(set) var current: TripSummary?
    /// Set when the fuel gauge showed a fill-up (shown once in the app).
    var refuelDetectedAt: Date?

    @ObservationIgnored private let recorder = TripRecorder()
    @ObservationIgnored private var gauge = RefuelDetector(stable: nil)
    @ObservationIgnored private let url: URL
    @ObservationIgnored private var lastPublish = Date.distantPast

    private struct Saved: Codable {
        var trips: [TripSummary]
        var fuel: FuelState
    }

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("trips.json")
        if let data = try? Data(contentsOf: url), let saved = try? JSONDecoder().decode(Saved.self, from: data) {
            trips = saved.trips
            fuel = saved.fuel
        }
        gauge = RefuelDetector(stable: fuel.lastFuelBars)
        refreshEstimate()
    }

    /// Recomputed when the inputs change, not on every view refresh (it scans the trip history).
    private(set) var fuelEstimate: FuelEstimate?

    private func refreshEstimate() {
        let e = FuelEstimator.estimate(fuel, history: trips, now: Date())
        if e != fuelEstimate { fuelEstimate = e }
    }

    // MARK: - Feed

    func ingest(_ t: ScooterTelemetry) {
        let now = Date()
        if let odo = t.odometerKm, odo != fuel.lastOdometer {
            fuel.lastOdometer = odo
            refreshEstimate()
        }
        // Only read the gauge standing still: the float sloshes while riding, and a fill-up
        // happens at the pump anyway.
        if let bars = t.fuelBars, (t.rearWheelKmh ?? 0) < 1, let reading = gauge.update(bars) {
            fuel.lastFuelBars = reading.bars
            if reading.rose {
                // Like the official app: the gauge jumped up → the tank was filled.
                fuel.fill(toLiters: fuel.litersFromBars(reading.bars), at: now)
                refuelDetectedAt = now
            }
            save()
            refreshEstimate()
        }
        if let finished = recorder.ingest(t, at: now) {
            store(finished)
        } else if now.timeIntervalSince(lastPublish) >= 2 || (current == nil) != (recorder.current == nil) {
            // The live trip card doesn't need 4 updates a second.
            lastPublish = now
            current = recorder.current
        }
    }

    /// The data link ended (ignition off, out of range): close the trip.
    func linkEnded() {
        if let finished = recorder.finish() { store(finished) }
        current = nil
    }

    // MARK: - Fuel

    func filledUp() {
        fuel.fill(toLiters: fuel.tankLiters, at: Date())
        save()
    }

    /// The rider read the gauge: set the current level (0…1 of the tank).
    func setFuelLevel(_ fraction: Double) {
        fuel.fill(toLiters: fuel.tankLiters * fraction, at: Date())
        save()
    }

    func setTankLiters(_ liters: Double) {
        fuel.tankLiters = liters
        save()
    }

    /// Bars of a full tank on the TFT gauge.
    func setBarsMax(_ bars: Int) {
        fuel.fuelBarsMax = bars
        save()
    }

    // MARK: - Trips

    func delete(_ ids: Set<UUID>) {
        trips.removeAll { ids.contains($0.id) }
        save()
    }

    private func store(_ trip: TripSummary) {
        trips.append(trip)
        if trips.count > 1000 { trips.removeFirst(trips.count - 1000) }
        fuel.add(trip)
        current = nil
        save()
    }

    private func save() {
        refreshEstimate()
        guard let data = try? JSONEncoder().encode(Saved(trips: trips, fuel: fuel)) else { return }
        try? data.write(to: url, options: .atomic)
    }
}

#if DEBUG
extension TripStore {
    /// README recordings (`-demoMode YES`): ten days of plausible rides and a fill-up three days ago.
    func seedDemo(odometer: Int) {
        let now = Date()
        var odo = odometer - 260
        var fuelState = FuelState()
        fuelState.fuelBarsMax = 7
        var rides: [TripSummary] = []
        for i in 0..<14 {
            let start = now.addingTimeInterval(-Double(13 - i) * 17 * 3600 - 2 * 3600)
            var trip = TripSummary(start: start)
            let km = [8.4, 12.1, 5.6, 21.3, 9.8, 15.2, 6.7][i % 7]
            let minutes = km * 2.6
            trip.end = start.addingTimeInterval(minutes * 60)
            trip.distanceKm = km
            trip.movingSeconds = minutes * 60 * 0.82
            trip.fuelLiters = km * (0.031 + 0.004 * Double(i % 3))
            trip.maxSpeedKmh = 48 + Double((i * 7) % 15)
            trip.maxRPM = 6_900 + (i * 130) % 900
            trip.avgCoolantC = 84 + Double(i % 4)
            trip.maxCoolantC = 92 + i % 5
            trip.avgAmbientC = 30 + Double(i % 4)
            trip.startOdometerKm = odo
            odo += Int(km.rounded())
            trip.endOdometerKm = odo
            if i == 9 { fuelState.lastOdometer = trip.endOdometerKm; fuelState.fill(toLiters: 8.5, at: trip.end) }
            rides.append(trip)
        }
        rides.filter { $0.start > fuelState.filledAt ?? now }.forEach { fuelState.add($0) }
        fuelState.lastOdometer = odometer
        fuelState.lastFuelBars = 5
        trips = rides
        fuel = fuelState
        gauge = RefuelDetector(stable: 5)
        save()
    }
}
#endif
