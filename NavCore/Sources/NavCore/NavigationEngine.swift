import Foundation

/// Turns raw GPS fixes into "what's the next maneuver and how far is it".
/// Snaps each fix onto the route polyline, preferring forward progress so routes
/// that pass the same street twice don't jump around.
public final class NavigationEngine {
    public let route: Route
    /// Meters from the route before a fix counts as off-route (raised by poor GPS accuracy).
    public var offRouteThreshold: Double = 40
    /// Consecutive off-route fixes needed before declaring off-route (filters GPS glitches).
    public var offRouteConfirmCount: Int = 3
    /// Meters before the destination at which we declare arrival.
    public var arrivalThreshold: Double = 25
    /// How far ahead (meters) the local snap search looks before falling back to a full scan.
    public var lookAhead: Double = 1000
    /// Language of `NavigationState.instruction`; can change mid-route.
    public var language: Language = .vi

    private let cumulative: [Double]
    private var segment = 0
    private var offRouteHits = 0
    public private(set) var hasArrived = false

    public init(route: Route) {
        self.route = route
        self.cumulative = Geo.cumulativeDistances(route.polyline)
    }

    private var totalLength: Double { cumulative.last ?? 0 }

    public func update(location: Coordinate, accuracy: Double = 0) -> NavigationState {
        let threshold = max(offRouteThreshold, min(accuracy, 100))
        let lastSeg = route.polyline.count - 2

        // 1) Local search around the last known segment.
        let from = max(0, segment - 2)
        var to = segment
        while to < lastSeg && cumulative[to] - cumulative[segment] < lookAhead { to += 1 }
        var best = bestSnap(location, from...max(from, to))

        // 2) Not close to the expected stretch → scan the whole route (e.g. after a GPS jump).
        if best.distance > threshold {
            let global = bestSnap(location, 0...max(0, lastSeg))
            if global.distance < best.distance { best = global }
        }

        if best.distance > threshold {
            offRouteHits += 1
        } else {
            offRouteHits = 0
            segment = best.segment
        }
        let offRoute = offRouteHits >= offRouteConfirmCount

        // While unconfirmed off-route, keep reporting from the last good position.
        let along: Double
        let snapped: Coordinate?
        if best.distance <= threshold {
            along = best.along
            snapped = best.point
        } else {
            along = cumulative[segment]
            snapped = nil
        }

        let remaining = max(0, totalLength - along)
        if !offRoute && remaining <= arrivalThreshold { hasArrived = true }

        let next = nextStep(after: along)
        let distanceToManeuver = hasArrived ? 0 : max(0, cumulative[next.pointIndex] - along)
        let maneuver: Maneuver = hasArrived ? .arrive : next.maneuver
        let street = hasArrived ? nil : next.streetName
        let duration = totalLength > 0 ? route.duration * remaining / totalLength : 0

        var state = NavigationState(
            maneuver: maneuver,
            streetName: street,
            instruction: InstructionFormatter.text(for: maneuver, street: street, language: language),
            distanceToManeuver: distanceToManeuver,
            remainingDistance: hasArrived ? 0 : remaining,
            remainingDuration: hasArrived ? 0 : duration,
            isOffRoute: offRoute,
            hasArrived: hasArrived,
            snappedPosition: snapped
        )
        state.roundaboutTurnDegrees = hasArrived ? nil : next.exitTurnDegrees
        state.segmentIndex = segment
        state.currentStreetName = route.steps.last { cumulative[$0.pointIndex] <= along + 1 }?.streetName
        state.speedLimit = route.speedLimits.first { $0.from <= segment && segment < max($0.to, $0.from + 1) }?.kmh
        return state
    }

    /// First maneuver strictly ahead of `along` (skipping depart); falls back to arrival.
    private func nextStep(after along: Double) -> RouteStep {
        for step in route.steps where step.maneuver != .depart {
            if cumulative[step.pointIndex] > along + 1 { return step }
        }
        return route.steps.last(where: { $0.maneuver == .arrive })
            ?? RouteStep(maneuver: .arrive, streetName: nil, pointIndex: route.polyline.count - 1)
    }

    private func bestSnap(_ p: Coordinate, _ range: ClosedRange<Int>)
        -> (segment: Int, distance: Double, along: Double, point: Coordinate)
    {
        var best = (segment: range.lowerBound, distance: Double.infinity, along: 0.0, point: p)
        for i in range where i + 1 < route.polyline.count {
            let a = route.polyline[i], b = route.polyline[i + 1]
            let (t, d) = Geo.project(p, ontoSegment: a, b)
            if d < best.distance {
                let along = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
                best = (i, d, along, Geo.interpolate(a, b, t: t))
            }
        }
        return best
    }
}
