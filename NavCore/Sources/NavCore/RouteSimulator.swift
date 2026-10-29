import Foundation

/// Positions along a route by distance — used for the in-app "Mô phỏng" mode
/// so navigation (and later the dashboard link) can be tested without riding.
public struct RouteSimulator {
    public let route: Route
    private let cumulative: [Double]

    public init(route: Route) {
        self.route = route
        self.cumulative = Geo.cumulativeDistances(route.polyline)
    }

    public var length: Double { cumulative.last ?? 0 }

    public func coordinate(atDistance d: Double) -> Coordinate {
        let pts = route.polyline
        guard let first = pts.first else { return Coordinate(latitude: 0, longitude: 0) }
        if d <= 0 { return first }
        if d >= length { return pts[pts.count - 1] }
        // Binary search for the segment containing d.
        var lo = 0, hi = cumulative.count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) / 2
            if cumulative[mid] <= d { lo = mid } else { hi = mid }
        }
        let segLen = cumulative[hi] - cumulative[lo]
        let t = segLen > 0 ? (d - cumulative[lo]) / segLen : 0
        return Geo.interpolate(pts[lo], pts[hi], t: t)
    }
}
