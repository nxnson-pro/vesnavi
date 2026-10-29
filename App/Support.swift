import CoreLocation
import NavCore

extension Coordinate {
    init(_ c: CLLocationCoordinate2D) {
        self.init(latitude: c.latitude, longitude: c.longitude)
    }

    var clCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

extension Maneuver {
    /// SF Symbol shown in the in-app HUD (the TFT has its own pictograms).
    var symbolName: String {
        switch self {
        case .depart: return "location.north.fill"
        case .straight: return "arrow.up"
        case .slightLeft, .keepLeft: return "arrow.up.left"
        case .left, .sharpLeft: return "arrow.turn.up.left"
        case .slightRight, .keepRight: return "arrow.up.right"
        case .right, .sharpRight: return "arrow.turn.up.right"
        case .uTurnLeft: return "arrow.uturn.left"
        case .uTurnRight: return "arrow.uturn.right"
        case .roundabout: return "arrow.triangle.2.circlepath"
        case .arrive: return "flag.checkered"
        }
    }
}

enum DurationFormatter {
    /// "8 phút", "1 giờ 05 phút" / "8 min", "1 h 05 min"
    static func text(_ seconds: Double) -> String {
        let minutes = max(1, Int((seconds / 60).rounded()))
        if minutes < 60 { return "\(minutes) \(L("phút", "min"))" }
        return String(format: "%d \(L("giờ", "h")) %02d \(L("phút", "min"))", minutes / 60, minutes % 60)
    }
}

/// UserDefaults keys shared by SettingsView (@AppStorage) and AppModel.
enum SettingsKey {
    static let graphHopperKey = "graphHopperApiKey"
    static let graphHopperProfile = "graphHopperProfile"
    static let simulationSpeedKmh = "simulationSpeedKmh"
    static let keepScreenOn = "keepScreenOn"
    static let speedLimitSource = "speedLimitSource"
    static let rememberParking = "rememberParking"
    static let maintenancePopupDay = "maintenancePopupDay"
    static let appLanguage = "appLanguage"
    static let safetyNoticeAccepted = "safetyNoticeAccepted"
}
