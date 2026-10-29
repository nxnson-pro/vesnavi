import NavCore
import SwiftUI

/// Big next-turn card shown at the top while navigating — the same arrow goes to the scooter TFT.
struct ManeuverCard: View {
    let state: NavigationState
    let note: String?
    let onScooter: Bool
    /// Speedometer reading from the scooter, to flag speeding.
    var speedKmh: Int? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 16) {
                Image(systemName: state.maneuver.symbolName)
                    .font(.system(size: 46, weight: .bold))
                    .frame(width: 64, height: 64)
                VStack(alignment: .leading, spacing: 2) {
                    Text(state.hasArrived ? L("Đã đến", "Arrived") : DistanceFormatter.text(state.distanceToManeuver, language: appLanguage))
                        .font(.system(size: 36, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .contentTransition(.numericText())
                    Text(state.streetName ?? state.instruction)
                        .font(.title3.weight(.medium))
                        .lineLimit(2)
                        .opacity(0.9)
                }
                Spacer(minLength: 0)
                if let limit = state.speedLimit {
                    SpeedLimitSign(kmh: limit, estimated: state.speedLimitEstimated,
                                   speeding: (speedKmh ?? 0) > limit + 3)
                }
            }
            if state.isOffRoute {
                Label(L("Lệch tuyến — đang tìm đường mới", "Off route — finding a new route"), systemImage: "exclamationmark.triangle.fill")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(.yellow)
            }
            if let note {
                Text(note).font(.caption).opacity(0.8)
            }
            if onScooter {
                Label(L("Đang hiện trên màn hình xe", "Showing on the scooter TFT"), systemImage: "checkmark.circle.fill")
                    .font(.caption.weight(.semibold))
                    .opacity(0.75)
            }
        }
        .padding(18)
        .foregroundStyle(.white)
        .background(Theme.maneuverBackground, in: RoundedRectangle(cornerRadius: Theme.cardRadius, style: .continuous))
        .shadow(color: .black.opacity(0.25), radius: 14, y: 6)
        .animation(.snappy, value: state.distanceToManeuver)
    }
}

/// Round Vietnamese-style limit sign; red when the scooter is faster, labelled when estimated.
struct SpeedLimitSign: View {
    let kmh: Int
    let estimated: Bool
    let speeding: Bool

    var body: some View {
        VStack(spacing: 2) {
            Text("\(kmh)")
                .font(.system(size: 22, weight: .heavy, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(speeding ? .white : .black)
                .frame(width: 52, height: 52)
                .background(speeding ? Color.red : Color.white, in: Circle())
                .overlay(Circle().stroke(.red, lineWidth: 5))
            if estimated {
                Text(L("ước tính", "est.")).font(.caption2).opacity(0.7)
            }
        }
        .accessibilityLabel(L("Giới hạn tốc độ \(kmh) km/h\(estimated ? ", ước tính" : "")", "Speed limit \(kmh) km/h\(estimated ? ", estimated" : "")"))
    }
}
