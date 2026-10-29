import SwiftUI

/// Shared look: one accent colour, rounded cards on material, big readable numbers.
enum Theme {
    /// Vespa-ish teal.
    static let accent = Color(red: 0.11, green: 0.62, blue: 0.56)
    static let maneuverBackground = Color(red: 0.06, green: 0.20, blue: 0.19)
    static let cardRadius: CGFloat = 22
}

extension View {
    /// Floating card used across the map screens.
    func card(padding: CGFloat = 16) -> some View {
        self
            .padding(padding)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Theme.cardRadius, style: .continuous))
            .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
    }
}

/// Circular icon button that sits on the map.
struct MapIconButton: View {
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 44, height: 44)
                .background(.regularMaterial, in: Circle())
                .shadow(color: .black.opacity(0.12), radius: 8, y: 3)
        }
        .buttonStyle(.plain)
    }
}

/// Coloured dot + short text describing the scooter link.
struct ScooterStatusPill: View {
    let ble: VespaBLE

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(color).frame(width: 8, height: 8)
            Image(systemName: "scooter")
            Text(label).lineLimit(1)
        }
        .font(.subheadline.weight(.semibold))
        .padding(.horizontal, 14)
        .frame(height: 44)
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.12), radius: 8, y: 3)
    }

    private var color: Color {
        switch ble.state {
        case .ready: return .green
        case .off, .unauthorized, .unknownService: return .red
        case .idle: return .gray
        default: return .orange
        }
    }

    private var label: String {
        switch ble.state {
        case .ready:
            if let t = ble.telemetry, let c = t.coolantC, t.engineRunning { return L("Đã kết nối · \(c)°C", "Connected · \(c)°C") }
            return L("Đã kết nối", "Connected")
        case .idle: return L("Chưa kết nối xe", "Scooter not connected")
        case .scanning, .connecting: return L("Đang tìm xe…", "Looking for the scooter…")
        case .discovering, .handshaking, .waitingForScooter: return L("Đang kết nối…", "Connecting…")
        case .off: return L("Bluetooth tắt", "Bluetooth off")
        case .unauthorized: return L("Cần quyền Bluetooth", "Bluetooth permission needed")
        case .unknownService: return L("Xe không hỗ trợ", "Scooter not supported")
        }
    }
}
