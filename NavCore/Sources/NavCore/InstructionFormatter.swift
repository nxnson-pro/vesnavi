import Foundation

/// Instruction text + distance display (Vietnamese or English), generated locally so every
/// routing provider (GraphHopper, OSRM, …) reads the same way.
public enum InstructionFormatter {
    public static func text(for maneuver: Maneuver, street: String?, language: Language = .vi) -> String {
        let street = street?.trimmingCharacters(in: .whitespaces).nilIfEmpty
        let onto = language.pick("vào", "onto")
        switch maneuver {
        case .depart:
            return street.map { language.pick("Bắt đầu trên \($0)", "Start on \($0)") } ?? language.pick("Bắt đầu", "Start")
        case .arrive:
            return language.pick("Đến nơi", "Arrived")
        case .roundabout(let exit):
            let base = exit.map { language.pick("Vào vòng xuyến, ra lối thứ \($0)", "At the roundabout, take exit \($0)") }
                ?? language.pick("Đi qua vòng xuyến", "Go through the roundabout")
            return street.map { "\(base) \(onto) \($0)" } ?? base
        case .straight:
            let verb = language.pick("Đi thẳng", "Continue straight")
            return street.map { "\(verb) \(onto) \($0)" } ?? verb
        default:
            let verb = (language == .vi ? verbs : verbsEN)[maneuver] ?? language.pick("Tiếp tục", "Continue")
            return street.map { "\(verb) \(onto) \($0)" } ?? verb
        }
    }

    private static let verbs: [Maneuver: String] = [
        .slightLeft: "Chếch sang trái",
        .left: "Rẽ trái",
        .sharpLeft: "Rẽ gắt sang trái",
        .slightRight: "Chếch sang phải",
        .right: "Rẽ phải",
        .sharpRight: "Rẽ gắt sang phải",
        .keepLeft: "Đi về bên trái",
        .keepRight: "Đi về bên phải",
        .uTurnLeft: "Quay đầu",
        .uTurnRight: "Quay đầu",
    ]

    private static let verbsEN: [Maneuver: String] = [
        .slightLeft: "Bear left",
        .left: "Turn left",
        .sharpLeft: "Sharp left",
        .slightRight: "Bear right",
        .right: "Turn right",
        .sharpRight: "Sharp right",
        .keepLeft: "Keep left",
        .keepRight: "Keep right",
        .uTurnLeft: "Make a U-turn",
        .uTurnRight: "Make a U-turn",
    ]
}

public enum DistanceFormatter {
    /// Rounds the way car navigators do, so the number on the dashboard doesn't flicker:
    /// < 100 m → 10 m steps, < 1 km → 50 m steps, otherwise 100 m steps.
    public static func rounded(_ meters: Double) -> Double {
        let m = max(0, meters)
        let step: Double = m < 100 ? 10 : (m < 1000 ? 50 : 100)
        return (m / step).rounded() * step
    }

    /// "40 m", "350 m", "1,2 km" (English: "1.2 km"), "12 km"
    public static func text(_ meters: Double, language: Language = .vi) -> String {
        let r = rounded(meters)
        if r < 1000 { return "\(Int(r)) m" }
        let km = r / 1000
        if km >= 10 { return "\(Int(km.rounded())) km" }
        let tenths = Int((km * 10).rounded())
        return "\(tenths / 10)\(language.pick(",", "."))\(tenths % 10) km"
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
