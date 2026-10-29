import NavCore
import SwiftUI

/// "Xe" sheet: connection + live engine data from the scooter.
struct ScooterView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    private var ble: VespaBLE { model.vespa }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    connectionCard
                    if let t = ble.telemetry {
                        gauges(t)
                        tiles(t)
                        if let at = ble.telemetryAt {
                            TimelineView(.periodic(from: .now, by: 1)) { context in
                                // Data arrives twice a second: "in 0 seconds" otherwise.
                                let fresh = context.date.timeIntervalSince(at) < 3
                                Text(fresh ? L("Cập nhật vừa xong", "Updated just now")
                                     : "\(L("Cập nhật", "Updated")) \(at.formatted(.relative(presentation: .numeric).locale(appLocale)))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    } else if ble.isReady {
                        Text(L("Đang chờ dữ liệu từ xe…", "Waiting for data from the scooter…"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .padding(.top, 24)
                    } else {
                        howTo
                    }
                    FuelCard()
                    MaintenanceCard()
                    TripsCard()
                    NavigationLink {
                        BluetoothLabView()
                    } label: {
                        Label(L("Bluetooth Lab (nâng cao)", "Bluetooth Lab (advanced)"), systemImage: "wrench.and.screwdriver")
                            .font(.subheadline)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .card(padding: 14)
                    }
                    .buttonStyle(.plain)
                }
                .padding()
            }
            .accessibilityIdentifier("scooterScroll")
            .background(Color(.systemGroupedBackground))
            .navigationTitle(L("Xe của tôi", "My scooter"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Xong", "Done")) { dismiss() }
                        .accessibilityIdentifier("scooterDone")
                }
            }
        }
        .tint(Theme.accent)
        .onAppear { ble.ensureCentral() }
    }

    // MARK: - Sections

    private var connectionCard: some View {
        HStack(spacing: 14) {
            Image(systemName: "scooter")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .background(ble.isReady ? Theme.accent : Color.gray, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text("Vespa GTS").font(.headline)
                Text(ble.state.title).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
            if ble.isConnected {
                Button(L("Ngắt", "Disconnect")) { ble.disconnect() }
                    .buttonStyle(.bordered)
                    .tint(.red)
            } else if [.scanning, .connecting].contains(ble.state) {
                ProgressView()
            } else {
                Button(L("Kết nối", "Connect")) { ble.quickConnect() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .card()
    }

    private func gauges(_ t: ScooterTelemetry) -> some View {
        HStack(spacing: 16) {
            RingGauge(title: L("Vòng tua", "RPM"), value: t.rpm.map(Double.init), range: 0...10_000,
                      unit: L("vòng/phút", "rpm"), tint: rpmTint(t.rpm)) { String(Int($0)) }
            RingGauge(title: L("Nước làm mát", "Coolant"), value: t.coolantC.map(Double.init), range: 0...130,
                      unit: "°C", tint: coolantTint(t.coolantC)) { String(Int($0)) }
        }
    }

    private func tiles(_ t: ScooterTelemetry) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
            MetricTile(icon: "minus.plus.batteryblock", title: L("Ắc quy", "Battery"),
                       value: t.batteryVolts.map { String(format: "%.1f", $0) }, unit: "V", tint: batteryTint(t))
            MetricTile(icon: "speedometer", title: L("Tốc độ", "Speed"),
                       value: (t.dashboardSpeedKmh ?? t.rearWheelKmh.map { Int($0.rounded()) }).map { "\($0)" },
                       unit: "km/h")
            MetricTile(icon: "sun.max", title: L("Ngoài trời", "Outside"),
                       value: t.ambientC.map { "\($0)" }, unit: "°C")
            MetricTile(icon: "wind", title: L("Gió nạp", "Intake air"),
                       value: t.intakeAirC.map { "\($0)" }, unit: "°C")
            MetricTile(icon: "gauge.with.needle", title: L("Tay ga", "Throttle"),
                       value: t.throttle.map { "\($0)" }, unit: "%")
            MetricTile(icon: "fuelpump", title: L("Tiêu hao", "Fuel rate"),
                       value: t.fuelLitersPerHour.map { String(format: "%.1f", $0) }, unit: "L/h")
            MetricTile(icon: "road.lanes", title: "ODO",
                       value: t.odometerKm.map { $0.formatted() }, unit: "km")
            MetricTile(icon: "arrow.up.to.line", title: L("Vòng tua cao nhất", "Peak RPM"),
                       value: ble.peakRPM > 0 ? String(ble.peakRPM) : nil, unit: L("vòng/phút", "rpm"))
            if let mil = t.milLamp, mil > 0, t.engineRunning {
                MetricTile(icon: "engine.combustion.fill", title: L("Đèn báo lỗi động cơ", "Engine warning light"),
                           value: L("BẬT", "ON"), unit: "", tint: .red)
            }
        }
    }

    private var howTo: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("Kết nối lần đầu", "First connection")).font(.headline)
            step(1, L("Bật chìa khoá để màn hình xe sáng.", "Turn the key so the scooter display is on."))
            step(2, L("Trên màn hình xe: Settings → Configuration → Pairing configuration.", "On the scooter display: Settings → Configuration → Pairing configuration."))
            step(3, L("Trên iPhone: Cài đặt → Bluetooth → chọn \"BT ROUTER\". Nếu được hỏi mã, nhập 0000.", "On the iPhone: Settings → Bluetooth → choose \"BT ROUTER\". If asked for a code, enter 0000."))
            step(4, L("Đóng hẳn app Vespa chính hãng (nếu có), rồi bấm Kết nối ở trên.", "Fully close the official Vespa app (if installed), then tap Connect above."))
            Text(L("Chỉ cần ghép đôi một lần. Những lần sau app tự kết nối khi bạn bật xe.", "You only pair once. After that the app connects by itself when you start the scooter."))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(n)")
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(Theme.accent, in: Circle())
            Text(text).font(.subheadline)
        }
    }

    // MARK: - Colours

    private func rpmTint(_ rpm: Int?) -> Color {
        (rpm ?? 0) > 8_000 ? .red : Theme.accent
    }

    private func coolantTint(_ c: Int?) -> Color {
        guard let c else { return .gray }
        if c >= 110 { return .red }
        if c >= 100 { return .orange }
        if c < 60 { return .blue }
        return Theme.accent
    }

    private func batteryTint(_ t: ScooterTelemetry) -> Color {
        guard let v = t.batteryVolts else { return .primary }
        if v < 11.5 { return .red }
        if t.engineRunning && v < 12.8 { return .orange }  // not charging
        return .primary
    }
}

/// 3/4 ring gauge with the value in the middle.
struct RingGauge: View {
    let title: String
    let value: Double?
    let range: ClosedRange<Double>
    let unit: String
    let tint: Color
    let format: (Double) -> String

    private var fraction: Double {
        guard let value else { return 0 }
        return min(1, max(0, (value - range.lowerBound) / (range.upperBound - range.lowerBound)))
    }

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                // Rotated so the gap sits at the bottom; the label below stays upright.
                ZStack {
                    Circle()
                        .trim(from: 0.125, to: 0.875)
                        .stroke(Color(.tertiarySystemFill), style: StrokeStyle(lineWidth: 12, lineCap: .round))
                    Circle()
                        .trim(from: 0.125, to: 0.125 + 0.75 * fraction)
                        .stroke(tint, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                }
                .rotationEffect(.degrees(90))
                VStack(spacing: 0) {
                    Text(value.map(format) ?? "–")
                        .font(.system(size: 30, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .contentTransition(.numericText())
                    Text(unit).font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(height: 130)
            Text(title).font(.subheadline.weight(.medium)).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .card()
        .animation(.easeOut(duration: 0.35), value: fraction)
    }
}

struct MetricTile: View {
    let icon: String
    let title: String
    let value: String?
    let unit: String
    var tint: Color = .primary

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: icon)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .lineLimit(1)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value ?? "–")
                    .font(.system(size: 26, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(tint)
                    .contentTransition(.numericText())
                Text(unit).font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card(padding: 14)
    }
}
