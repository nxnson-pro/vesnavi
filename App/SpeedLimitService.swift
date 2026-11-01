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
    /// Lookup outcomes for the Bluetooth log (bug reports): changes of the limit and failures, no positions.
    @ObservationIgnored var onLog: ((String) -> Void)?

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
            let (result, problem) = await Self.query(p, street: street, vietnamDefaults: vietnamDefaults)
            guard let self else { return }
            self.inFlight = false
            if let result {
                if result.kmh != self.current?.kmh || result.estimated != self.current?.estimated {
                    self.onLog?(L("Giới hạn tốc độ: \(result.kmh) km/h\(result.estimated ? " (ước tính)" : "")",
                                  "Speed limit: \(result.kmh) km/h\(result.estimated ? " (estimated)" : "")"))
                }
                self.current = result
            } else {
                self.onLog?(L("⚠️ Không tra được giới hạn tốc độ: \(problem)", "⚠️ No speed limit: \(problem)"))
                // Keep the previous value when the lookup failed (no network): limits rarely change.
                if streetChanged { self.current = nil }
            }
        }
    }

    /// The limit, or why there is none (for the log).
    private static func query(_ p: Coordinate, street: String?, vietnamDefaults: Bool) async -> (SpeedLimitResult?, String) {
        var problems: [String] = []
        for endpoint in SpeedLimitResolver.overpassEndpoints {
            let url = SpeedLimitResolver.overpassURL(at: p, endpoint: endpoint)
            var request = URLRequest(url: url, timeoutInterval: 10)
            request.setValue("VesNavi/1.0 (personal iOS app)", forHTTPHeaderField: "User-Agent")
            let host = endpoint.host ?? "?"
            let reply: (Data, URLResponse)
            do {
                reply = try await URLSession.shared.data(for: request)
            } catch {
                problems.append("\(host): \((error as? URLError)?.code.rawValue.description ?? "error") \(error.localizedDescription)")
                continue
            }
            let status = (reply.1 as? HTTPURLResponse)?.statusCode ?? 0
            guard status == 200 else {
                problems.append("\(host): HTTP \(status)")
                continue
            }
            guard let ways = try? SpeedLimitResolver.parseOverpass(reply.0) else {
                problems.append("\(host): " + L("dữ liệu lạ", "unreadable reply"))
                continue
            }
            if let r = SpeedLimitResolver.resolve(ways, street: street, vietnamDefaults: vietnamDefaults) { return (r, "") }
            return (nil, L("không có đường xe máy trong \(ways.count) đường quanh 20 m, hoặc đường không ghi tốc độ",
                           "no rideable road among \(ways.count) ways within 20 m, or no tagged limit"))
        }
        return (nil, problems.joined(separator: "; "))
    }
}
