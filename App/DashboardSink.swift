import Foundation
import NavCore

/// Where navigation instructions go besides the phone screen (implemented by `VespaBLE`).
@MainActor
protocol DashboardSink: AnyObject {
    var name: String { get }
    /// Human-readable copy of the last thing sent (shown in the HUD for debugging).
    var lastMessage: String? { get }
    func send(_ state: NavigationState)
    func clear()
}
