import Foundation

/// Post-processing shared by every routing provider.
public enum RouteAnnotator {
    /// How far before the entry / after the exit we look to get a stable road direction.
    static let probeMeters = 25.0

    /// Fills `exitTurnDegrees` for roundabout steps: the direction of the road we leave on,
    /// relative to the road we arrive on. The exit point is where the next step starts
    /// (OSRM "exit roundabout", GraphHopper's next instruction).
    public static func annotateRoundabouts(_ route: Route) -> Route {
        var r = route
        let pts = route.polyline
        guard pts.count >= 2 else { return r }
        for i in r.steps.indices {
            guard case .roundabout = r.steps[i].maneuver, i + 1 < r.steps.count else { continue }
            let entry = r.steps[i].pointIndex
            let exit = r.steps[i + 1].pointIndex
            guard exit > entry, entry > 0, exit < pts.count - 1 else { continue }
            let before = point(pts, from: entry, meters: probeMeters, forward: false)
            let after = point(pts, from: exit, meters: probeMeters, forward: true)
            let inBearing = Geo.bearing(before, pts[entry])
            let outBearing = Geo.bearing(pts[exit], after)
            r.steps[i].exitTurnDegrees = Geo.turn(from: inBearing, to: outBearing)
        }
        return r
    }

    /// Walks the polyline from `index` until `meters` are covered (or the end is reached).
    static func point(_ pts: [Coordinate], from index: Int, meters: Double, forward: Bool) -> Coordinate {
        var covered = 0.0
        var i = index
        while true {
            let j = forward ? i + 1 : i - 1
            guard j >= 0, j < pts.count else { return pts[i] }
            covered += Geo.distance(pts[i], pts[j])
            if covered >= meters { return pts[j] }
            i = j
        }
    }

    /// TFT roundabout pictogram for right-hand traffic (counter-clockwise): 100 + exit direction —
    /// 0 U-turn, 1 sharp right, 2 right, 3 slight right, 4 straight, 5 slight left, 6 left, 7 sharp left.
    public static func roundaboutIcon(turnDegrees d: Double) -> UInt8 {
        let a = abs(d)
        let n: UInt8
        if a > 157.5 { n = 0 }
        else if a <= 22.5 { n = 4 }
        else if d > 0 { n = a > 112.5 ? 1 : (a > 67.5 ? 2 : 3) }
        else { n = a > 112.5 ? 7 : (a > 67.5 ? 6 : 5) }
        return 100 + n
    }
}
