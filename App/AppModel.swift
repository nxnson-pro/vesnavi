import Foundation
import MapKit
import Observation
import SwiftUI
import UIKit
import NavCore

@MainActor
@Observable
final class AppModel {
    enum Phase: Equatable { case idle, routing, preview, navigating }

    let location: LocationService
    let search: SearchService
    let vespa: VespaBLE
    let trips: TripStore
    let places: PlacesStore
    let maintenance: MaintenanceStore
    let speedLimits: SpeedLimitService
    var dashboard: DashboardSink { vespa }

    private(set) var phase: Phase = .idle
    private(set) var destination: MKMapItem?
    private(set) var route: Route?
    /// Converted once per route: the map would otherwise re-map thousands of points on every redraw.
    private(set) var routeCoordinates: [CLLocationCoordinate2D] = []
    private(set) var navState: NavigationState?
    private(set) var isSimulating = false
    private(set) var simulatedPosition: Coordinate?
    private(set) var lastDashboardMessage: String?
    /// Non-fatal info about the route, e.g. "GraphHopper failed → using OSRM".
    private(set) var routeNote: String?
    var errorMessage: String?

    @ObservationIgnored private var engine: NavigationEngine?
    @ObservationIgnored private var throttle = DashboardThrottle()
    @ObservationIgnored private var simTask: Task<Void, Never>?
    @ObservationIgnored private var isRerouting = false
    @ObservationIgnored private var lastRerouteAt = Date.distantPast

    init() {
        location = LocationService()
        search = SearchService()
        vespa = VespaBLE()
        trips = TripStore()
        places = PlacesStore()
        maintenance = MaintenanceStore()
        speedLimits = SpeedLimitService()
        vespa.onTelemetry = { [weak self] t in self?.scooterData(t) }
        vespa.onDataLinkEnded = { [weak self] in self?.scooterLinkEnded() }
        location.onUpdate = { [weak self] loc in self?.handle(loc) }
        location.requestPermission()
        location.setMode(.browsing)
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "demoMode") { startDemo() }
        #endif
    }

    /// Battery: with the app in the background and no route, stop GPS entirely.
    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            vespa.isForeground = true
            if self.phase != .navigating { location.setMode(.browsing) }
        case .background:
            vespa.isForeground = false
            if self.phase != .navigating { location.setMode(.off) }
            vespa.stopScan()
        default:
            break
        }
    }

    // MARK: - Destination & routing

    func select(_ completion: MKLocalSearchCompletion) async {
        do {
            let item = try await search.resolve(completion)
            await planRoute(to: item)
        } catch {
            errorMessage = L("Không tìm thấy địa điểm: \(error.localizedDescription)", "Place not found: \(error.localizedDescription)")
        }
    }

    func planRoute(to item: MKMapItem) async {
        guard let here = location.lastLocation else {
            errorMessage = L("Chưa có vị trí GPS. Hãy bật quyền vị trí cho app và thử lại.", "No GPS position yet. Allow location access for the app and try again.")
            return
        }
        destination = item
        phase = .routing
        do {
            let (route, note) = try await fetchRoute(from: Coordinate(here.coordinate),
                                                     to: Coordinate(item.placemark.coordinate))
            setRoute(route)
            routeNote = note
            phase = .preview
            places.addRecent(SavedPlace(item))
        } catch {
            destination = nil
            phase = .idle
            errorMessage = Self.message(for: error)
        }
    }

    /// GraphHopper if an API key is set (falls back to OSRM on error), otherwise OSRM.
    private func fetchRoute(from: Coordinate, to: Coordinate) async throws -> (Route, String?) {
        let fetch: HTTPFetcher = { url in
            let (data, _) = try await URLSession.shared.data(from: url)
            return data
        }
        let defaults = UserDefaults.standard
        let key = (defaults.string(forKey: SettingsKey.graphHopperKey) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let osrm = OSRMProvider(fetch: fetch)
        guard !key.isEmpty else {
            return (try await osrm.route(from: from, to: to), nil)
        }
        let profile = defaults.string(forKey: SettingsKey.graphHopperProfile) ?? "car"
        let graphHopper = { (profile: String) in GraphHopperProvider(apiKey: key, profile: profile, fetch: fetch) }
        do {
            return (try await graphHopper(profile).route(from: from, to: to), nil)
        } catch RoutingError.server(let msg) where profile != "car" && msg.contains("profile") {
            // Free GraphHopper plans only offer car/bike/foot: switch to car for good and retry.
            defaults.set("car", forKey: SettingsKey.graphHopperProfile)
            if let route = try? await graphHopper("car").route(from: from, to: to) {
                return (route, L("Gói GraphHopper miễn phí không có kiểu Xe tay ga → đã chuyển sang Ô tô",
                                 "The free GraphHopper plan has no Scooter profile → switched to Car"))
            }
            return (try await osrm.route(from: from, to: to),
                    L("GraphHopper lỗi → đang dùng OSRM", "GraphHopper failed → using OSRM"))
        } catch {
            let route = try await osrm.route(from: from, to: to)
            let msg = Self.message(for: error)
            return (route, L("GraphHopper lỗi (\(msg)) → đang dùng OSRM", "GraphHopper failed (\(msg)) → using OSRM"))
        }
    }

    /// Routing errors in the app language (other errors come from iOS in the iPhone's language).
    private static func message(for error: Error) -> String {
        (error as? RoutingError)?.message(appLanguage) ?? error.localizedDescription
    }

    private func setRoute(_ r: Route?) {
        route = r
        routeCoordinates = r?.polyline.map(\.clCoordinate) ?? []
    }

    // MARK: - Scooter data

    @ObservationIgnored private var parkingClearedThisRide = false

    private func scooterData(_ t: ScooterTelemetry) {
        trips.ingest(t)
        if let odo = t.odometerKm { maintenance.updateOdometer(odo) }
        if t.engineRunning, !parkingClearedThisRide {
            // Riding away: the old parking spot is no longer where the scooter is.
            parkingClearedThisRide = true
            if places.parking != nil { places.clearParking() }
        }
        remindMaintenanceIfDue()
    }

    private func scooterLinkEnded() {
        trips.linkEnded()
        parkingClearedThisRide = false
        maintenanceChecked = false
        rememberParkingSpot()
    }

    /// The scooter was switched off (or left behind): note where the phone is.
    private func rememberParkingSpot() {
        guard UserDefaults.standard.object(forKey: SettingsKey.rememberParking) as? Bool ?? true else { return }
        let endedAt = Date()
        location.requestPreciseFix { [weak self] fix in
            self?.saveParking(fix, linkEndedAt: endedAt)
        }
    }

    /// Re-measure the parking spot where the rider stands now (next to the scooter).
    func setParkingHere() {
        guard location.isAuthorized else {
            errorMessage = L("Cần quyền vị trí cho VesNavi (Cài đặt iPhone → VesNavi → Vị trí).",
                             "VesNavi needs location access (iPhone Settings → VesNavi → Location).")
            return
        }
        location.requestPreciseFix(timeout: 15) { [weak self] fix in
            guard let self else { return }
            if let fix {
                self.places.setParking(fix.coordinate, accuracy: fix.horizontalAccuracy)
            } else {
                self.errorMessage = L("Không lấy được vị trí GPS, thử lại ở chỗ thoáng hơn.",
                                      "Couldn't get a GPS fix, try again somewhere more open.")
            }
        }
    }

    /// A fix counts for the parking spot only within this many seconds of the link ending: before
    /// that the rider was still riding, after it they may be walking away.
    private static let parkingFixWindow: TimeInterval = 10

    /// The precise fix, or a fix the running GPS took around the moment the scooter was switched off
    /// (e.g. while navigating) if that one is more accurate. With neither, nothing is saved rather
    /// than a spot from earlier in the ride.
    private func saveParking(_ fix: CLLocation?, linkEndedAt: Date) {
        let window = Self.parkingFixWindow
        let aroundStop = location.lastLocation.flatMap { last in
            abs(last.timestamp.timeIntervalSince(linkEndedAt)) <= window ? last : nil
        }
        let candidates = [fix, aroundStop].compactMap { $0 }.filter {
            $0.horizontalAccuracy >= 0 && $0.timestamp >= linkEndedAt.addingTimeInterval(-window)
        }
        guard let best = candidates.min(by: { $0.horizontalAccuracy < $1.horizontalAccuracy }) else { return }
        places.setParking(best.coordinate, at: best.timestamp, accuracy: best.horizontalAccuracy)
    }

    /// Once a day, when the scooter is connected: pop up the most urgent overdue service on the TFT.
    /// Checked once per connection (not on every data frame).
    @ObservationIgnored private var maintenanceChecked = false

    private func remindMaintenanceIfDue() {
        guard !maintenanceChecked, vespa.isReady, maintenance.odometer != nil else { return }
        maintenanceChecked = true
        let today = Calendar.current.startOfDay(for: Date()).timeIntervalSince1970
        guard UserDefaults.standard.double(forKey: SettingsKey.maintenancePopupDay) != today,
              let item = maintenance.dueItems.first else { return }
        UserDefaults.standard.set(today, forKey: SettingsKey.maintenancePopupDay)
        vespa.showPopup(L("Bao duong", "Service due"), item.popupText(appLanguage))
    }

    // MARK: - Navigation

    func startNavigation(simulate: Bool) {
        guard let route else { return }
        engine = NavigationEngine(route: route)
        throttle.reset()
        speedLimits.reset()
        isSimulating = simulate
        phase = .navigating
        // Off by default: the arrows are on the scooter TFT, the phone can stay in a pocket.
        UIApplication.shared.isIdleTimerDisabled = UserDefaults.standard.bool(forKey: SettingsKey.keepScreenOn)
        // Precise GPS in the background also keeps the app (and the simulation timer) alive
        // with the screen off.
        location.setMode(.navigating)
        if simulate {
            startSimulation(route)
        } else if let loc = location.lastLocation {
            handle(loc)
        }
    }

    /// Also used to cancel the route preview.
    func stopNavigation() {
        simTask?.cancel()
        simTask = nil
        arrivalTask?.cancel()
        arrivalTask = nil
        engine = nil
        isSimulating = false
        simulatedPosition = nil
        navState = nil
        setRoute(nil)
        destination = nil
        routeNote = nil
        lastDashboardMessage = nil
        dashboard.clear()
        let inForeground = UIApplication.shared.applicationState == .active
        location.setMode(inForeground ? .browsing : .off)
        UIApplication.shared.isIdleTimerDisabled = false
        phase = .idle
    }

    private func handle(_ loc: CLLocation) {
        guard phase == .navigating, !isSimulating else { return }
        guard loc.horizontalAccuracy >= 0, loc.horizontalAccuracy <= 100 else { return }
        process(Coordinate(loc.coordinate), accuracy: loc.horizontalAccuracy)
    }

    private func process(_ coord: Coordinate, accuracy: Double) {
        guard let engine else { return }
        engine.language = appLanguage
        var state = engine.update(location: coord, accuracy: accuracy)
        if let limit = speedLimits.limit(for: state, at: state.snappedPosition ?? coord) {
            state.speedLimit = limit.kmh
            state.speedLimitEstimated = limit.estimated
        } else {
            state.speedLimit = nil
        }
        navState = state
        if state.hasArrived { finishAfterArrival() }
        if throttle.shouldSend(state, now: Date().timeIntervalSinceReferenceDate) {
            dashboard.send(state)
            lastDashboardMessage = dashboard.lastMessage
        }
        if state.isOffRoute { reroute(from: coord) }
    }

    @ObservationIgnored private var arrivalTask: Task<Void, Never>?

    /// Arrived: show "Đến nơi" for a while, then end guidance on its own — otherwise precise GPS
    /// and the TFT navigation screen keep running (battery) until the rider remembers to tap Stop.
    private func finishAfterArrival() {
        guard arrivalTask == nil else { return }
        arrivalTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            guard let self, !Task.isCancelled, self.phase == .navigating else { return }
            self.stopNavigation()
        }
    }

    private func reroute(from coord: Coordinate) {
        guard !isSimulating, !isRerouting, let destination,
              Date().timeIntervalSince(lastRerouteAt) > 15 else { return }
        isRerouting = true
        lastRerouteAt = Date()
        let target = Coordinate(destination.placemark.coordinate)
        Task { [weak self] in
            guard let self else { return }
            defer { self.isRerouting = false }
            do {
                let (newRoute, note) = try await self.fetchRoute(from: coord, to: target)
                guard self.phase == .navigating else { return }
                self.setRoute(newRoute)
                self.routeNote = note
                self.engine = NavigationEngine(route: newRoute)
                self.throttle.reset()
            } catch {
                let msg = Self.message(for: error)
                self.routeNote = L("Tìm lại đường thất bại: \(msg)", "Rerouting failed: \(msg)")
            }
        }
    }

    // MARK: - Simulation (test without riding)

    private func startSimulation(_ route: Route) {
        let sim = RouteSimulator(route: route)
        let kmh = UserDefaults.standard.double(forKey: SettingsKey.simulationSpeedKmh)
        let metersPerTick = (kmh > 0 ? kmh : 30) / 3.6  // 1 tick = 1 s
        simTask = Task { [weak self] in
            var distance = 0.0
            while !Task.isCancelled {
                guard let self else { return }
                let p = sim.coordinate(atDistance: distance)
                self.simulatedPosition = p
                self.process(p, accuracy: 5)
                if distance >= sim.length { return }
                distance += metersPerTick
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }
}

#if DEBUG
extension AppModel {
    /// README recordings (UI tests launch with `-demoMode YES`): a connected scooter, ride history,
    /// service items and saved places around central Saigon. Debug builds only.
    private func startDemo() {
        let odometer = 12_480
        let defaults = UserDefaults.standard
        defaults.set(45.0, forKey: SettingsKey.simulationSpeedKmh)
        vespa.idleDisplay = .text
        places.setHome(SavedPlace(name: L("Nhà", "Home"), subtitle: "Chợ Bến Thành, Quận 1",
                                  coordinate: CLLocationCoordinate2D(latitude: 10.77246, longitude: 106.69805)))
        places.setWork(SavedPlace(name: L("Công ty", "Work"), subtitle: "Bitexco, Quận 1",
                                  coordinate: CLLocationCoordinate2D(latitude: 10.77165, longitude: 106.70441)))
        places.setParking(CLLocationCoordinate2D(latitude: 10.78010, longitude: 106.69960),
                          at: Date().addingTimeInterval(-25 * 60), accuracy: 6)
        maintenance.updateOdometer(odometer)
        let monthsAgo = { (months: Int) in Calendar.current.date(byAdding: .month, value: -months, to: Date()) ?? Date() }
        for (id, km, months) in [("engineOil", 9_700, 5), ("gearOil", 7_200, 7), ("airFilter", 3_100, 11),
                                 ("sparkPlug", 3_100, 11), ("brakePads", 11_900, 1), ("belt", 3_100, 11),
                                 ("coolant", 3_100, 11), ("brakeFluid", 1_200, 26)] {
            maintenance.markDone(id, atKm: km, on: monthsAgo(months))
        }
        trips.seedDemo(odometer: odometer)
        // The demo scooter is already running: keep the parking spot and skip the service pop-up.
        parkingClearedThisRide = true
        maintenanceChecked = true
        vespa.startDemo { [weak self] in
            guard let self, self.isSimulating, self.navState?.hasArrived == false else { return 0 }
            return 38
        }
    }
}
#endif
