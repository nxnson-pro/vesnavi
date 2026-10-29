import Foundation

/// OSRM — no API key needed. Default is the public demo server (car profile only,
/// "fair use", fine for personal testing). Can be pointed at a self-hosted server.
public struct OSRMProvider: RoutingProvider {
    public let baseURL: URL
    public let fetch: HTTPFetcher
    public var name: String { "OSRM" }

    public init(baseURL: URL = URL(string: "https://router.project-osrm.org")!, fetch: @escaping HTTPFetcher) {
        self.baseURL = baseURL
        self.fetch = fetch
    }

    public func url(from: Coordinate, to: Coordinate) -> URL {
        let path = "route/v1/driving/\(from.longitude),\(from.latitude);\(to.longitude),\(to.latitude)"
        var c = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        c.queryItems = [
            URLQueryItem(name: "overview", value: "false"),
            URLQueryItem(name: "steps", value: "true"),
            URLQueryItem(name: "geometries", value: "geojson"),
        ]
        return c.url!
    }

    public func route(from: Coordinate, to: Coordinate) async throws -> Route {
        try Self.parse(try await fetch(url(from: from, to: to)))
    }

    // MARK: - Parsing

    struct Response: Decodable {
        struct OSRMRoute: Decodable {
            struct Leg: Decodable { let steps: [Step] }
            let distance: Double
            let duration: Double
            let legs: [Leg]
        }
        struct Step: Decodable {
            struct Geometry: Decodable { let coordinates: [[Double]] }
            struct StepManeuver: Decodable {
                let type: String
                let modifier: String?
                let exit: Int?
            }
            let geometry: Geometry
            let maneuver: StepManeuver
            let name: String?
        }
        let code: String
        let message: String?
        let routes: [OSRMRoute]?
    }

    public static func parse(_ data: Data) throws -> Route {
        let response: Response
        do {
            response = try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw RoutingError.invalidResponse
        }
        guard response.code == "Ok", let r = response.routes?.first else {
            if response.code == "NoRoute" { throw RoutingError.noRoute }
            throw RoutingError.server(response.message ?? response.code)
        }

        // Stitch step geometries into one polyline; each step starts where the previous ended.
        var polyline: [Coordinate] = []
        var steps: [RouteStep] = []
        for step in r.legs.flatMap(\.steps) {
            let coords = step.geometry.coordinates.compactMap { pair -> Coordinate? in
                pair.count >= 2 ? Coordinate(latitude: pair[1], longitude: pair[0]) : nil
            }
            let index: Int
            if polyline.isEmpty {
                index = 0
                polyline = coords
            } else {
                index = polyline.count - 1
                polyline.append(contentsOf: coords.dropFirst())
            }
            steps.append(RouteStep(maneuver: maneuver(step.maneuver),
                                   streetName: step.name?.nilIfEmpty,
                                   pointIndex: max(0, index)))
        }
        guard polyline.count >= 2 else { throw RoutingError.noRoute }
        return RouteAnnotator.annotateRoundabouts(
            Route(polyline: polyline, steps: steps, distance: r.distance, duration: r.duration, provider: "OSRM"))
    }

    static func maneuver(_ m: Response.Step.StepManeuver) -> Maneuver {
        switch m.type {
        case "depart": return .depart
        case "arrive": return .arrive
        case "roundabout", "rotary", "roundabout turn": return .roundabout(exit: m.exit)
        case "exit roundabout", "exit rotary": return .straight
        default: break
        }
        let isFork = m.type == "fork" || m.type == "merge" || m.type.hasSuffix("ramp")
        switch m.modifier {
        case "uturn": return .uTurnLeft
        case "sharp left": return .sharpLeft
        case "left": return .left
        case "slight left": return isFork ? .keepLeft : .slightLeft
        case "slight right": return isFork ? .keepRight : .slightRight
        case "right": return .right
        case "sharp right": return .sharpRight
        default: return .straight
        }
    }
}
