import Foundation

public struct Coordinate: Equatable, Hashable, Codable, Sendable {
    public var latitude: Double
    public var longitude: Double

    public init(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
    }
}

/// Turn types we can show — kept small on purpose so it maps cleanly
/// onto the pictogram set of the Vespa TFT dashboard (step 2).
public enum Maneuver: Equatable, Hashable, Sendable {
    case depart
    case straight
    case slightLeft, left, sharpLeft
    case slightRight, right, sharpRight
    case keepLeft, keepRight
    case uTurnLeft, uTurnRight
    case roundabout(exit: Int?)
    case arrive
}

public struct RouteStep: Equatable, Sendable {
    public var maneuver: Maneuver
    public var streetName: String?
    /// Index into `Route.polyline` of the point where this maneuver happens.
    public var pointIndex: Int
    /// Roundabouts: angle between the entry and the exit road, degrees, + = right, − = left
    /// (filled in by `RouteAnnotator`). Picks the TFT roundabout pictogram.
    public var exitTurnDegrees: Double?

    public init(maneuver: Maneuver, streetName: String?, pointIndex: Int, exitTurnDegrees: Double? = nil) {
        self.maneuver = maneuver
        self.streetName = streetName
        self.pointIndex = pointIndex
        self.exitTurnDegrees = exitTurnDegrees
    }
}

/// Legal speed limit over a stretch of the route polyline (point indices, inclusive start).
public struct SpeedLimitSpan: Equatable, Sendable {
    public var from: Int
    public var to: Int
    public var kmh: Int

    public init(from: Int, to: Int, kmh: Int) {
        self.from = from
        self.to = to
        self.kmh = kmh
    }
}

public struct Route: Equatable, Sendable {
    public var polyline: [Coordinate]
    /// Sorted by `pointIndex`. First is usually `.depart`, last `.arrive`.
    public var steps: [RouteStep]
    /// Meters.
    public var distance: Double
    /// Seconds.
    public var duration: Double
    /// Human-readable source, e.g. "GraphHopper", "OSRM".
    public var provider: String
    /// Speed limits along the route when the provider returns them (GraphHopper `max_speed`).
    public var speedLimits: [SpeedLimitSpan]

    public init(polyline: [Coordinate], steps: [RouteStep], distance: Double, duration: Double, provider: String,
                speedLimits: [SpeedLimitSpan] = []) {
        self.polyline = polyline
        self.steps = steps
        self.distance = distance
        self.duration = duration
        self.provider = provider
        self.speedLimits = speedLimits
    }
}

/// What the rider should see right now — this is what gets pushed to the dashboard.
public struct NavigationState: Equatable, Sendable {
    public var maneuver: Maneuver
    public var streetName: String?
    public var instruction: String
    /// Meters to the next maneuver.
    public var distanceToManeuver: Double
    /// Meters to destination.
    public var remainingDistance: Double
    /// Seconds to destination (scaled from the route estimate).
    public var remainingDuration: Double
    public var isOffRoute: Bool
    public var hasArrived: Bool
    /// Position snapped onto the route (nil when off-route).
    public var snappedPosition: Coordinate?
    /// See `RouteStep.exitTurnDegrees`.
    public var roundaboutTurnDegrees: Double? = nil
    /// km/h; filled in by the app from the route or a lookup (sent to the TFT as tag 02).
    public var speedLimit: Int? = nil
    /// True when the limit is inferred from Vietnamese default rules, not tagged on the road.
    public var speedLimitEstimated: Bool = false
    /// Index of the route segment the rider is on (for per-segment data such as speed limits).
    public var segmentIndex: Int = 0
    /// The street the rider is on now (`streetName` is the one after the next turn).
    public var currentStreetName: String? = nil
}
