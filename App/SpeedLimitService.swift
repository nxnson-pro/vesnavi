import Foundation
import Observation
import NavCore

/// Current legal speed limit while navigating. Free sources first: the route itself
/// (GraphHopper), OpenStreetMap through the public Overpass API, Vietnamese default rules.
@MainActor
@Observable
final class SpeedLimitService {
    private(set) var current: SpeedLimitResult?

    var source: SpeedLimitSource {
        get { sourceValue }
        set {
            sourceValue = newValue
            UserDefaults.standard.set(newValue.rawValue, forKey: SettingsKey.speedLimitSource)
            current = nil
        }
    }
    private var sourceValue: SpeedLimitSource

    @ObservationIgnored private var lastQueryAt = Date.distantPast
    @ObservationIgnored private var lastQueryPoint: Coordinate?
    @ObservationIgnored private var lastStreet: String?
    @ObservationIgnored private var inFlight = false

    /// Ask again after this distance, or when the street changes.
    static let requeryMeters = 250.0
    static let minInterval: TimeInterval = 8

    init() {
        sourceValue = UserDefaults.standard.string(forKey: SettingsKey.speedLimitSource)
            .flatMap(SpeedLimitSource.init(rawValue:)) ?? .osmWithVietnamDefaults
    }

    func reset() {
        current = nil
        lastQueryPoint = nil
        lastStreet = nil
    }

    /// Call on every navigation update; returns the limit to show right now.
    func limit(for state: NavigationState, at position: Coordinate) -> SpeedLimitResult? {
        switch source {
        case .off:
            return nil
        case .graphHopper:
            if let kmh = state.speedLimit {
                let r = SpeedLimitResult(kmh: kmh, estimated: false, roadName: state.currentStreetName)
                if r != current { current = r }
                return r
            }
            lookUp(state, at: position, vietnamDefaults: true)
        case .osm:
            lookUp(state, at: position, vietnamDefaults: false)
        case .osmWithVietnamDefaults:
            lookUp(state, at: position, vietnamDefaults: true)
        }
        return current
    }

    private func lookUp(_ state: NavigationState, at p: Coordinate, vietnamDefaults: Bool) {
        let street = state.currentStreetName
        let moved = lastQueryPoint.map { Geo.distance($0, p) } ?? .infinity
        let streetChanged = street != nil && street != lastStreet
        guard !inFlight, Date().timeIntervalSince(lastQueryAt) >= Self.minInterval,
              moved >= Self.requeryMeters || streetChanged else { return }
        inFlight = true
        lastQueryAt = Date()
        lastQueryPoint = p
        lastStreet = street
        Task { [weak self] in
            let result = await Self.query(p, street: street, vietnamDefaults: vietnamDefaults)
            guard let self else { return }
            self.inFlight = false
            // Keep the previous value when the lookup failed (no network): limits rarely change.
            if let result { self.current = result } else if streetChanged { self.current = nil }
        }
    }

    private static func query(_ p: Coordinate, street: String?, vietnamDefaults: Bool) async -> SpeedLimitResult? {
        for endpoint in SpeedLimitResolver.overpassEndpoints {
            let url = SpeedLimitResolver.overpassURL(at: p, endpoint: endpoint)
            var request = URLRequest(url: url, timeoutInterval: 10)
            request.setValue("VesNavi/1.0 (personal iOS app)", forHTTPHeaderField: "User-Agent")
            guard let reply = try? await URLSession.shared.data(for: request),
                  (reply.1 as? HTTPURLResponse)?.statusCode == 200,
                  let ways = try? SpeedLimitResolver.parseOverpass(reply.0) else { continue }
            return SpeedLimitResolver.resolve(ways, street: street, vietnamDefaults: vietnamDefaults)
        }
        return nil
    }
}
