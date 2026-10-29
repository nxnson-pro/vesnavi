import CoreLocation
import Observation
import UIKit

@MainActor
@Observable
final class LocationService: NSObject, CLLocationManagerDelegate {
    private(set) var lastLocation: CLLocation?
    private(set) var authorization: CLAuthorizationStatus = .notDetermined
    @ObservationIgnored var onUpdate: (@MainActor (CLLocation) -> Void)?

    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private var mode: Mode = .off
    @ObservationIgnored private var preciseFixes: [PreciseFix] = []

    /// Battery: precise GPS only while navigating; coarse in the foreground; nothing in the background.
    enum Mode { case off, browsing, navigating }

    override init() {
        super.init()
        authorization = manager.authorizationStatus
        manager.delegate = self
        manager.activityType = .otherNavigation
    }

    func requestPermission() {
        manager.requestWhenInUseAuthorization()
    }

    /// "Always" lets the app take one GPS fix when the scooter is switched off with the phone
    /// in a pocket (parking spot). Without it the last known position is used.
    func requestAlways() {
        manager.requestAlwaysAuthorization()
    }

    var hasAlways: Bool { authorization == .authorizedAlways }

    /// The most accurate fix within `timeout` seconds (sooner once one is within `goodEnough` m), from
    /// a separate GPS session at full accuracy — the browsing fix can be 100 m off. Works in the
    /// background with "Always" permission (the scooter switched off, phone in a pocket).
    func requestPreciseFix(timeout: TimeInterval = 20, goodEnough: Double = 10,
                           _ completion: @escaping (CLLocation?) -> Void) {
        guard isAuthorized else {
            completion(nil)
            return
        }
        var request: PreciseFix?
        request = PreciseFix(timeout: timeout, goodEnough: goodEnough, background: hasAlways) { [weak self] fix in
            self?.preciseFixes.removeAll { $0 === request }
            completion(fix)
        }
        if let request { preciseFixes.append(request) }
    }

    /// `.navigating` keeps precise GPS running with the screen off (UIBackgroundModes=location).
    func setMode(_ newMode: Mode) {
        guard newMode != mode else { return }
        mode = newMode
        switch newMode {
        case .off:
            manager.stopUpdatingLocation()
            manager.allowsBackgroundLocationUpdates = false
            manager.showsBackgroundLocationIndicator = false
        case .browsing:
            manager.allowsBackgroundLocationUpdates = false
            manager.showsBackgroundLocationIndicator = false
            manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
            manager.distanceFilter = 50
            manager.pausesLocationUpdatesAutomatically = true
            if isAuthorized { manager.startUpdatingLocation() }
        case .navigating:
            manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
            manager.distanceFilter = kCLDistanceFilterNone
            manager.pausesLocationUpdatesAutomatically = false
            manager.allowsBackgroundLocationUpdates = true
            manager.showsBackgroundLocationIndicator = true
            if isAuthorized { manager.startUpdatingLocation() }
        }
    }

    var isAuthorized: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    // MARK: CLLocationManagerDelegate (called on the main thread — manager was created there)

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        MainActor.assumeIsolated {
            authorization = status
            if isAuthorized, mode != .off {
                self.manager.startUpdatingLocation()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        MainActor.assumeIsolated {
            lastLocation = location
            onUpdate?(location)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Transient failures (e.g. kCLErrorLocationUnknown) are expected; keep listening.
    }
}

/// One full-accuracy GPS session: keeps the best fix until it is good enough or time runs out.
@MainActor
private final class PreciseFix: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let goodEnough: Double
    private let started = Date()
    private var best: CLLocation?
    private var completion: ((CLLocation?) -> Void)?
    private var timeout: Task<Void, Never>?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    init(timeout seconds: TimeInterval, goodEnough: Double, background: Bool,
         completion: @escaping (CLLocation?) -> Void) {
        self.goodEnough = goodEnough
        self.completion = completion
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .other
        manager.pausesLocationUpdatesAutomatically = false
        if background { manager.allowsBackgroundLocationUpdates = true }
        // Woken in the background by the scooter disconnecting: ask iOS for time to finish.
        backgroundTask = UIApplication.shared.beginBackgroundTask { [weak self] in
            MainActor.assumeIsolated { self?.finish() }
        }
        manager.startUpdatingLocation()
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            self?.finish()
        }
    }

    private func finish() {
        guard let completion else { return }
        self.completion = nil
        timeout?.cancel()
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        completion(best)
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        MainActor.assumeIsolated {
            // Skip cached fixes from before the request: they are where the phone was, not is.
            for fix in locations where fix.horizontalAccuracy >= 0 && fix.timestamp >= started.addingTimeInterval(-2) {
                if fix.horizontalAccuracy < best?.horizontalAccuracy ?? .infinity { best = fix }
            }
            if let best, best.horizontalAccuracy <= goodEnough { finish() }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // kCLErrorLocationUnknown etc. are transient; keep trying until the timeout.
    }
}
