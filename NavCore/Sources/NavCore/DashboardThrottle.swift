import Foundation

/// Decides when a new NavigationState is worth pushing to the dashboard.
/// The BLE link to the scooter is slow, so we send only meaningful changes:
/// immediately when the maneuver/street/status changes, otherwise when the
/// rounded distance changes (at most every `minInterval`), plus a periodic
/// heartbeat so the dashboard doesn't time out the navigation screen.
public struct DashboardThrottle {
    public var minInterval: TimeInterval = 1
    public var heartbeat: TimeInterval = 10

    private struct Key: Equatable {
        var maneuver: Maneuver
        var street: String?
        var offRoute: Bool
        var arrived: Bool
        var speedLimit: Int?
    }

    private var lastKey: Key?
    private var lastDistance: Double?
    private var lastSent: TimeInterval = -.infinity

    public init() {}

    public mutating func shouldSend(_ state: NavigationState, now: TimeInterval) -> Bool {
        let key = Key(maneuver: state.maneuver, street: state.streetName,
                      offRoute: state.isOffRoute, arrived: state.hasArrived, speedLimit: state.speedLimit)
        let distance = DistanceFormatter.rounded(state.distanceToManeuver)
        let elapsed = now - lastSent

        let send = key != lastKey
            || (distance != lastDistance && elapsed >= minInterval)
            || elapsed >= heartbeat
        if send {
            lastKey = key
            lastDistance = distance
            lastSent = now
        }
        return send
    }

    public mutating func reset() {
        lastKey = nil
        lastDistance = nil
        lastSent = -.infinity
    }
}
