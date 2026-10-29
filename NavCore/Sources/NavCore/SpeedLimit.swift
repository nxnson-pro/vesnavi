import Foundation

/// Where speed limits come from. All free: OpenStreetMap via the public Overpass API (no key),
/// optionally completed with Vietnam's default limits, or GraphHopper's route data (needs the free key).
public enum SpeedLimitSource: String, CaseIterable, Codable, Sendable {
    case off
    /// OSM `maxspeed` tags only.
    case osm
    /// OSM tags, else the Vietnamese default for that kind of road (marked as an estimate).
    case osmWithVietnamDefaults
    /// GraphHopper route details (`max_speed`), else OSM + Vietnamese defaults.
    case graphHopper
}

public struct SpeedLimitResult: Equatable, Sendable {
    public var kmh: Int
    /// Inferred from the road type, not tagged on the road.
    public var estimated: Bool
    public var roadName: String?

    public init(kmh: Int, estimated: Bool, roadName: String? = nil) {
        self.kmh = kmh
        self.estimated = estimated
        self.roadName = roadName
    }
}

/// OSM way as returned by Overpass (`out tags`).
public struct OSMWay: Equatable, Sendable {
    public var id: Int
    public var tags: [String: String]

    public init(id: Int, tags: [String: String]) {
        self.id = id
        self.tags = tags
    }
}

public enum SpeedLimitResolver {
    /// Public Overpass endpoints, tried in order.
    public static let overpassEndpoints = [
        URL(string: "https://overpass-api.de/api/interpreter")!,
        URL(string: "https://overpass.kumi.systems/api/interpreter")!,
    ]

    /// Ways with a `highway` tag within `radius` metres.
    public static func overpassURL(at c: Coordinate, radius: Int = 20, endpoint: URL = overpassEndpoints[0]) -> URL {
        let q = "[out:json][timeout:10];way(around:\(radius),\(c.latitude),\(c.longitude))[highway];out tags;"
        var comps = URLComponents(url: endpoint, resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "data", value: q)]
        return comps.url!
    }

    struct OverpassResponse: Decodable {
        struct Element: Decodable {
            let type: String
            let id: Int
            let tags: [String: String]?
        }
        let elements: [Element]
    }

    public static func parseOverpass(_ data: Data) throws -> [OSMWay] {
        let r = try JSONDecoder().decode(OverpassResponse.self, from: data)
        return r.elements.filter { $0.type == "way" }.map { OSMWay(id: $0.id, tags: $0.tags ?? [:]) }
    }

    /// Picks the way the rider is on (same name as the current street if known, else the most
    /// important road nearby) and returns its limit for motorcycles.
    public static func resolve(_ ways: [OSMWay], street: String?, vietnamDefaults: Bool) -> SpeedLimitResult? {
        let roads = ways.filter { rideable($0.tags["highway"]) }
        guard !roads.isEmpty else { return nil }
        let wanted = street.map(normalize)
        let named = wanted.flatMap { w in roads.filter { normalize($0.tags["name"] ?? "") == w } }
        let pool = (named?.isEmpty == false) ? named! : roads
        let way = pool.max { rank($0) < rank($1) }!
        let name = way.tags["name"]
        for key in ["maxspeed:motorcycle", "maxspeed"] {
            if let raw = way.tags[key], let v = parseMaxspeed(raw) {
                return SpeedLimitResult(kmh: v, estimated: false, roadName: name)
            }
        }
        guard vietnamDefaults, let v = vietnamDefault(way.tags) else { return nil }
        return SpeedLimitResult(kmh: v, estimated: true, roadName: name)
    }

    /// "50", "50 km/h", "30 mph", "VN:urban"…
    public static func parseMaxspeed(_ raw: String) -> Int? {
        let s = raw.trimmingCharacters(in: .whitespaces).lowercased()
        switch s {
        case "vn:urban": return 50
        case "vn:rural": return 60
        case "vn:motorway": return nil  // motorcycles are not allowed on expressways
        default: break
        }
        let digits = s.prefix { $0.isNumber }
        guard let n = Int(digits), n > 0 else { return nil }
        return s.contains("mph") ? Int((Double(n) * 1.609).rounded()) : n
    }

    /// Default limits for motorcycles in built-up areas (Thông tư 38/2024/TT-BGTVT, Điều 6):
    /// 60 km/h on divided roads / one-way roads with ≥ 2 lanes, 50 km/h otherwise. Built-up area
    /// is assumed because OSM rarely says otherwise; outside towns the real limit is usually higher.
    public static func vietnamDefault(_ tags: [String: String]) -> Int? {
        guard let highway = tags["highway"], rideable(highway) else { return nil }
        let lanes = Int(tags["lanes"] ?? "") ?? 1
        let oneway = tags["oneway"] == "yes"
        let divided = tags["dual_carriageway"] == "yes"
        if divided || (oneway && lanes >= 2) { return 60 }
        return 50
    }

    static func rideable(_ highway: String?) -> Bool {
        guard let h = highway else { return false }
        let excluded: Set<String> = ["motorway", "motorway_link", "footway", "path", "cycleway", "steps",
                                     "pedestrian", "track", "bridleway", "construction", "proposed", "corridor"]
        return !excluded.contains(h)
    }

    static func rank(_ w: OSMWay) -> Int {
        let order = ["trunk": 9, "primary": 8, "secondary": 7, "tertiary": 6, "trunk_link": 5, "primary_link": 5,
                     "secondary_link": 4, "unclassified": 3, "residential": 2, "living_street": 1, "service": 0]
        let base = order[w.tags["highway"] ?? ""] ?? 0
        return base * 2 + (w.tags["maxspeed"] != nil ? 1 : 0)
    }

    static func normalize(_ s: String) -> String {
        s.lowercased()
            .folding(options: [.diacriticInsensitive], locale: Locale(identifier: "vi_VN"))
            .replacingOccurrences(of: "đ", with: "d")
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { !$0.isEmpty && !["duong", "pho", "street", "road"].contains($0) }
            .joined(separator: " ")
    }
}
