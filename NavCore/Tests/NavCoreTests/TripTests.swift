import XCTest
@testable import NavCore

final class TripTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    private func sample(rpm: Int, kmh: Double, fuel: Double, coolant: Int = 80, odo: Int = 1000) -> ScooterTelemetry {
        var t = ScooterTelemetry()
        t.rpm = rpm
        t.rearWheelKmh = kmh
        t.fuelLitersPerHour = fuel
        t.coolantC = coolant
        t.ambientC = 30
        t.odometerKm = odo
        return t
    }

    func testRideIsIntegrated() throws {
        let r = TripRecorder()
        // 10 minutes at 36 km/h, 1.2 L/h, one sample per second.
        for i in 0...600 {
            XCTAssertNil(r.ingest(sample(rpm: 4000, kmh: 36, fuel: 1.2, odo: 1000 + i / 100), at: t0.addingTimeInterval(Double(i))))
        }
        let trip = try XCTUnwrap(r.finish())
        XCTAssertEqual(trip.distanceKm, 6, accuracy: 0.01)
        XCTAssertEqual(trip.fuelLiters, 0.2, accuracy: 0.001)
        XCTAssertEqual(trip.avgSpeedKmh, 36, accuracy: 0.1)
        XCTAssertEqual(trip.litersPer100Km ?? 0, 3.33, accuracy: 0.01)
        XCTAssertEqual(trip.maxRPM, 4000)
        XCTAssertEqual(trip.avgCoolantC ?? 0, 80, accuracy: 0.001)
        XCTAssertEqual(trip.duration, 600, accuracy: 0.001)
    }

    func testEngineOffEndsTripAfterTimeout() {
        let r = TripRecorder()
        for i in 0...300 { _ = r.ingest(sample(rpm: 3000, kmh: 30, fuel: 1), at: t0.addingTimeInterval(Double(i))) }
        var ended: TripSummary?
        for i in 1...200 {
            if let trip = r.ingest(sample(rpm: 0, kmh: 0, fuel: 0), at: t0.addingTimeInterval(300 + Double(i))) {
                ended = trip
                XCTAssertEqual(i, 180, "ends after the engine has been off for 3 minutes")
                break
            }
        }
        XCTAssertEqual(ended?.end, t0.addingTimeInterval(300), "parked minutes are not part of the trip")
        XCTAssertNil(r.current)
    }

    func testGapsAreNotIntegratedButOdometerCovers() throws {
        let r = TripRecorder()
        _ = r.ingest(sample(rpm: 3000, kmh: 40, fuel: 1, odo: 1000), at: t0)
        // App suspended for 10 minutes; the odometer moved 7 km meanwhile.
        _ = r.ingest(sample(rpm: 3000, kmh: 40, fuel: 1, odo: 1007), at: t0.addingTimeInterval(600))
        let trip = try XCTUnwrap(r.finish())
        XCTAssertEqual(trip.distanceKm, 7)
        XCTAssertEqual(trip.fuelLiters, 0)
    }

    func testShortTripsAreDiscarded() {
        let r = TripRecorder()
        for i in 0...10 { _ = r.ingest(sample(rpm: 1700, kmh: 0, fuel: 0.6), at: t0.addingTimeInterval(Double(i))) }
        XCTAssertNil(r.finish())
    }

    func testFuelEstimate() throws {
        var s = FuelState()
        s.lastOdometer = 1000
        s.fill(toLiters: 8.5, at: t0)
        var trip = TripSummary(start: t0.addingTimeInterval(3600))
        trip.end = t0.addingTimeInterval(7200)
        trip.distanceKm = 60
        trip.fuelLiters = 2.0
        trip.endOdometerKm = 1080  // 20 km ridden without the app
        s.add(trip)
        let now = t0.addingTimeInterval(86_400)
        let e = try XCTUnwrap(FuelEstimator.estimate(s, history: [trip], now: now))
        XCTAssertEqual(e.litersPer100Km, 2.0 / 60 * 100, accuracy: 0.001)
        XCTAssertEqual(e.litersLeft, 8.5 - 2.0 - 20 * e.litersPer100Km / 100, accuracy: 0.001)
        XCTAssertEqual(e.rangeKm, e.litersLeft / e.litersPer100Km * 100, accuracy: 0.001)
        XCTAssertNotNil(e.kmPerDay)
        XCTAssertNotNil(e.refuelBy)
        XCTAssertGreaterThan(e.refuelBy ?? now, now)
    }

    func testNoEstimateBeforeFirstFill() {
        XCTAssertNil(FuelEstimator.estimate(FuelState(), history: [], now: t0))
    }

    func testTripsBeforeTheFillDontCount() {
        var s = FuelState()
        s.fill(toLiters: 8.5, at: t0)
        var old = TripSummary(start: t0.addingTimeInterval(-7200))
        old.end = t0.addingTimeInterval(-3600)
        old.fuelLiters = 1
        old.distanceKm = 30
        s.add(old)
        XCTAssertEqual(s.litersMeasured, 0)
    }
}
