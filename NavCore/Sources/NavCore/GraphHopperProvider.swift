import Foundation

/// GraphHopper Directions API (OpenStreetMap data). Free plan: ~500 requests/day.
/// Docs: https://docs.graphhopper.com/#operation/getRoute
public struct GraphHopperProvider: RoutingProvider {
    public let apiKey: String
    /// e.g. "car", "scooter". The free plan only offers car, bike and foot.
    public let profile: String
    public let fetch: HTTPFetcher
    public var name: String { "GraphHopper (\(profile))" }

    public init(apiKey: String, profile: String = "car", fetch: @escaping HTTPFetcher) {
        self.apiKey = apiKey
        self.profile = profile
        self.fetch = fetch
    }

    public func url(from: Coordinate, to: Coordinate) -> URL {
        var c = URLComponents(string: "https://graphhopper.com/api/1/route")!
        c.queryItems = [
            URLQueryItem(name: "point", value: "\(from.latitude),\(from.longitude)"),
            URLQueryItem(name: "point", value: "\(to.latitude),\(to.longitude)"),
            URLQueryItem(name: "profile", value: profile),
            URLQueryItem(name: "locale", value: "vi"),
            URLQueryItem(name: "instructions", value: "true"),
            URLQueryItem(name: "points_encoded", value: "false"),
            URLQueryItem(name: "details", value: "max_speed"),
            URLQueryItem(name: "key", value: apiKey),
        ]
        return c.url!
    }

    public func route(from: Coordinate, to: Coordinate) async throws -> Route {
        try Self.parse(try await fetch(url(from: from, to: to)), provider: name)
    }

    // MARK: - Parsing

    struct Response: Decodable {
        struct Path: Decodable {
            struct Points: Decodable { let coordinates: [[Double]] }
            struct Instruction: Decodable {
                let sign: Int
                let interval: [Int]
                let street_name: String?
                let exit_number: Int?
            }
            let distance: Double
            let time: Double  // milliseconds
            let points: Points
            let instructions: [Instruction]
            /// `details.max_speed`: [[fromIndex, toIndex, km/h or null], …]
            let details: Details?
        }
        struct Details: Decodable {
            let max_speed: [[Double?]]?
        }
        let paths: [Path]?
        let message: String?
    }

    public static func parse(_ data: Data, provider: String = "GraphHopper") throws -> Route {
        let response: Response
        do {
            response = try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw RoutingError.invalidResponse
        }
        guard let path = response.paths?.first else {
            if let msg = response.message { throw RoutingError.server(msg) }
            throw RoutingError.noRoute
        }
        let polyline = path.points.coordinates.compactMap { pair -> Coordinate? in
            pair.count >= 2 ? Coordinate(latitude: pair[1], longitude: pair[0]) : nil
        }
        guard polyline.count >= 2 else { throw RoutingError.noRoute }

        let steps = path.instructions.enumerated().map { i, ins in
            RouteStep(
                maneuver: i == 0 ? .depart : maneuver(sign: ins.sign, exit: ins.exit_number),
                streetName: ins.street_name?.nilIfEmpty,
                pointIndex: min(ins.interval.first ?? 0, polyline.count - 1)
            )
        }
        let limits: [SpeedLimitSpan] = (path.details?.max_speed ?? []).compactMap { row in
            guard row.count >= 3, let from = row[0], let to = row[1], let kmh = row[2], kmh > 0 else { return nil }
            return SpeedLimitSpan(from: Int(from), to: Int(to), kmh: Int(kmh.rounded()))
        }
        return RouteAnnotator.annotateRoundabouts(
            Route(polyline: polyline, steps: steps, distance: path.distance,
                  duration: path.time / 1000, provider: provider, speedLimits: limits))
    }

    /// GraphHopper "sign" codes → Maneuver.
    static func maneuver(sign: Int, exit: Int?) -> Maneuver {
        switch sign {
        case -98, -8: return .uTurnLeft
        case -7: return .keepLeft
        case -3: return .sharpLeft
        case -2: return .left
        case -1: return .slightLeft
        case 1: return .slightRight
        case 2: return .right
        case 3: return .sharpRight
        case 4: return .arrive
        case 6: return .roundabout(exit: exit)
        case 7: return .keepRight
        case 8: return .uTurnRight
        default: return .straight  // 0 continue, 5 via point, -6 leave roundabout
        }
    }
}
