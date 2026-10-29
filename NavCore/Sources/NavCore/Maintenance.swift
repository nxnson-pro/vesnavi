import Foundation

/// A service item tracked against the scooter's odometer and/or the calendar.
public struct MaintenanceItem: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    /// SF Symbol for the app.
    public var icon: String
    public var intervalKm: Int?
    public var intervalMonths: Int?
    /// ASCII text for the TFT pop-up ("THAY NHOT MAY").
    public var tftText: String
    public var lastKm: Int?
    public var lastDate: Date?

    public init(id: String, name: String, icon: String, intervalKm: Int?, intervalMonths: Int?, tftText: String,
                lastKm: Int? = nil, lastDate: Date? = nil) {
        self.id = id
        self.name = name
        self.icon = icon
        self.intervalKm = intervalKm
        self.intervalMonths = intervalMonths
        self.tftText = tftText
        self.lastKm = lastKm
        self.lastDate = lastDate
    }

    /// Common practice in Vietnam for a GTS 300 HPE (shorter than the factory schedule because of
    /// heat and stop-and-go traffic). Every interval can be changed in the app.
    public static let defaults: [MaintenanceItem] = [
        MaintenanceItem(id: "engineOil", name: "Nhớt máy", icon: "drop.fill",
                        intervalKm: 3_000, intervalMonths: 6, tftText: "Thay nhot may"),
        MaintenanceItem(id: "gearOil", name: "Nhớt hộp số (láp)", icon: "gearshape.2.fill",
                        intervalKm: 6_000, intervalMonths: 12, tftText: "Thay nhot lap"),
        MaintenanceItem(id: "airFilter", name: "Lọc gió", icon: "wind",
                        intervalKm: 10_000, intervalMonths: 12, tftText: "Thay loc gio"),
        MaintenanceItem(id: "sparkPlug", name: "Bugi", icon: "bolt.fill",
                        intervalKm: 10_000, intervalMonths: 24, tftText: "Thay bugi"),
        MaintenanceItem(id: "brakePads", name: "Má phanh (kiểm tra)", icon: "circle.slash",
                        intervalKm: 6_000, intervalMonths: 12, tftText: "Kiem tra ma phanh"),
        MaintenanceItem(id: "belt", name: "Dây curoa + bi nồi", icon: "arrow.triangle.2.circlepath",
                        intervalKm: 20_000, intervalMonths: 24, tftText: "Thay day curoa"),
        MaintenanceItem(id: "coolant", name: "Nước làm mát", icon: "thermometer.snowflake",
                        intervalKm: 20_000, intervalMonths: 24, tftText: "Thay nuoc mat"),
        MaintenanceItem(id: "brakeFluid", name: "Dầu phanh", icon: "drop.triangle",
                        intervalKm: nil, intervalMonths: 24, tftText: "Thay dau phanh"),
    ]

    /// English name + TFT text of the built-in items (the stored `name`/`tftText` are Vietnamese).
    private static let english: [String: (name: String, tft: String)] = [
        "engineOil": ("Engine oil", "Change engine oil"),
        "gearOil": ("Gear oil", "Change gear oil"),
        "airFilter": ("Air filter", "Change air filter"),
        "sparkPlug": ("Spark plug", "Change spark plug"),
        "brakePads": ("Brake pads (check)", "Check brake pads"),
        "belt": ("Drive belt + rollers", "Change drive belt"),
        "coolant": ("Coolant", "Change coolant"),
        "brakeFluid": ("Brake fluid", "Change brake fluid"),
    ]

    public func displayName(_ language: Language) -> String {
        language == .en ? Self.english[id]?.name ?? name : name
    }

    /// TFT pop-up line (ASCII, upper-cased when sent).
    public func popupText(_ language: Language) -> String {
        language == .en ? Self.english[id]?.tft ?? tftText : tftText
    }
}

public struct MaintenanceStatus: Equatable, Sendable {
    public enum Level: Int, Comparable, Sendable {
        case unknown, ok, soon, due
        public static func < (a: Level, b: Level) -> Bool { a.rawValue < b.rawValue }
    }
    public var level: Level
    /// Negative = overdue by that much.
    public var kmLeft: Int?
    public var daysLeft: Int?
    /// 0 = just done, 1 = due.
    public var progress: Double
}

public enum MaintenancePlanner {
    /// "Soon" within 10 % of the interval (at least 300 km / 14 days).
    public static func status(of item: MaintenanceItem, odometer: Int?, now: Date) -> MaintenanceStatus {
        var kmLeft: Int?
        var daysLeft: Int?
        var progress = 0.0
        var soon = false
        if let interval = item.intervalKm, let last = item.lastKm, let odo = odometer {
            let left = last + interval - odo
            kmLeft = left
            progress = max(progress, Double(odo - last) / Double(interval))
            soon = soon || left <= max(300, interval / 10)
        }
        if let months = item.intervalMonths, let last = item.lastDate,
           let due = Calendar(identifier: .gregorian).date(byAdding: .month, value: months, to: last) {
            let left = Int((due.timeIntervalSince(now) / 86_400).rounded(.down))
            daysLeft = left
            let total = due.timeIntervalSince(last)
            if total > 0 { progress = max(progress, now.timeIntervalSince(last) / total) }
            soon = soon || left <= max(14, months * 3)
        }
        let level: MaintenanceStatus.Level
        if kmLeft == nil && daysLeft == nil {
            level = .unknown
        } else if (kmLeft ?? 1) <= 0 || (daysLeft ?? 1) <= 0 {
            level = .due
        } else {
            level = soon ? .soon : .ok
        }
        return MaintenanceStatus(level: level, kmLeft: kmLeft, daysLeft: daysLeft, progress: min(max(progress, 0), 1.5))
    }
}
