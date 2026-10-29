import Foundation

public enum Geo {
    public static let earthRadius = 6_371_000.0

    @inline(__always) static func rad(_ deg: Double) -> Double { deg * .pi / 180 }

    /// Great-circle distance in meters (haversine).
    public static func distance(_ a: Coordinate, _ b: Coordinate) -> Double {
        let dLat = rad(b.latitude - a.latitude)
        let dLon = rad(b.longitude - a.longitude)
        let h = sin(dLat / 2) * sin(dLat / 2)
            + cos(rad(a.latitude)) * cos(rad(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadius * asin(min(1, sqrt(h)))
    }

    /// Running total of distances along the polyline; `result[i]` = meters from start to point i.
    public static func cumulativeDistances(_ points: [Coordinate]) -> [Double] {
        guard !points.isEmpty else { return [] }
        var result = [0.0]
        result.reserveCapacity(points.count)
        for i in 1..<points.count {
            result.append(result[i - 1] + distance(points[i - 1], points[i]))
        }
        return result
    }

    /// Projects `p` onto segment a→b using a local flat-earth approximation
    /// (accurate to well under a meter at segment lengths we deal with).
    /// Returns the fraction along the segment (0...1) and the distance from `p` to that point.
    public static func project(_ p: Coordinate, ontoSegment a: Coordinate, _ b: Coordinate) -> (t: Double, distance: Double) {
        let k = cos(rad(a.latitude))
        let bx = rad(b.longitude - a.longitude) * k * earthRadius
        let by = rad(b.latitude - a.latitude) * earthRadius
        let px = rad(p.longitude - a.longitude) * k * earthRadius
        let py = rad(p.latitude - a.latitude) * earthRadius
        let len2 = bx * bx + by * by
        let t = len2 > 0 ? max(0, min(1, (px * bx + py * by) / len2)) : 0
        let dx = px - t * bx
        let dy = py - t * by
        return (t, sqrt(dx * dx + dy * dy))
    }

    /// Initial bearing a → b, degrees clockwise from north (0…360).
    public static func bearing(_ a: Coordinate, _ b: Coordinate) -> Double {
        let lat1 = rad(a.latitude), lat2 = rad(b.latitude)
        let dLon = rad(b.longitude - a.longitude)
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let deg = atan2(y, x) * 180 / .pi
        return deg < 0 ? deg + 360 : deg
    }

    /// Signed difference `to − from` in −180…180 (+ = clockwise / right turn).
    public static func turn(from: Double, to: Double) -> Double {
        var d = (to - from).truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 }
        if d < -180 { d += 360 }
        return d
    }

    public static func interpolate(_ a: Coordinate, _ b: Coordinate, t: Double) -> Coordinate {
        Coordinate(latitude: a.latitude + (b.latitude - a.latitude) * t,
                   longitude: a.longitude + (b.longitude - a.longitude) * t)
    }
}
