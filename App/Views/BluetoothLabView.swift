import NavCore
import SwiftUI
import UniformTypeIdentifiers

/// On-scooter test bench: connect, watch the handshake, push test arrows, export the log.
struct BluetoothLabView: View {
    @Environment(AppModel.self) private var model
    @State private var icon = 11
    @State private var distance = 200
    @State private var roadName = "Lê Lợi"
    @State private var rawHex = ""
    @State private var rawWithHeader = false
    @State private var popupLine1 = "Xin chao"
    @State private var popupLine2 = "VesNavi"
    @State private var showReport = false

    private var ble: VespaBLE { model.vespa }

    var body: some View {
        @Bindable var ble = model.vespa
        Form {
            Section(L("Trạng thái", "Status")) {
                LabeledContent(L("Kết nối", "Connection"), value: ble.state.title)
                if let name = ble.connectedName { LabeledContent("Xe", value: name) }
                if let proto = ble.linkProtocol { LabeledContent(L("Giao thức", "Protocol"), value: proto.rawValue) }
                if let fw = ble.firmwareInfo { LabeledContent("Firmware", value: fw) }
                LabeledContent(L("Ghép đôi", "Pairing"), value: ble.pairing?.key == nil ? L("Chưa", "None") : L("Đã có khoá", "Key stored"))
                Toggle(L("Tự kết nối lại khi bật xe", "Auto-reconnect when the scooter starts"), isOn: $ble.autoReconnect)
                if ble.isConnected {
                    Button(L("Ngắt kết nối", "Disconnect"), role: .destructive) { ble.disconnect() }
                }
                Button(L("Quên ghép đôi (ghép lại từ đầu)", "Forget pairing (pair again from scratch)"), role: .destructive) { ble.forgetPairing() }
            }

            Section {
                if ble.state == .scanning {
                    Button(L("Dừng quét", "Stop scan")) { ble.stopScan() }
                } else {
                    Button(L("🔍 Quét thiết bị Bluetooth", "🔍 Scan Bluetooth devices")) { ble.startScan() }
                }
                ForEach(ble.discovered) { d in
                    Button { ble.connect(d.id) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text((d.isLikelyVespa ? "🛵 " : "") + d.name).foregroundStyle(.primary)
                            Text("RSSI \(d.rssi)" + (d.services.isEmpty ? "" : " · " + d.services.joined(separator: ", ")))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            } header: {
                Text(L("Tìm xe", "Find the scooter"))
            } footer: {
                Text(L("Xe PMP2 (GTS Supertech): giống app chính hãng, ghép đôi một lần theo hướng dẫn Vespa: trên màn hình xe chọn Settings → Configuration → Pairing configuration, rồi Cài đặt iPhone → Bluetooth → \"BT ROUTER\" (mã 0000 nếu được hỏi), sau đó mới kết nối ở đây. Bật chìa khoá để màn hình TFT sáng, đứng gần xe. Thiết bị nghi là Vespa (có dịch vụ 1815 hoặc tên VESPA/BT-ROUTER) có biểu tượng 🛵. Nếu không chắc, chạm từng cái và xem log.", "PMP2 scooters (GTS Supertech): like the official app, pair once following Vespa's guide: on the scooter display choose Settings → Configuration → Pairing configuration, then iPhone Settings → Bluetooth → \"BT ROUTER\" (code 0000 if asked), and only then connect here. Turn the key so the TFT is on and stay close to the scooter. Likely Vespa devices (service 1815 or named VESPA/BT-ROUTER) are marked 🛵. If unsure, tap each one and check the log."))
            }

            Section {
                LabeledContent(L("Trạng thái", "Status"), value: ble.state.title)
            } header: {
                Text("PMP2 / Cobo (GTS Supertech)")
            } footer: {
                Text(L("Giống app chính hãng: iPhone kết nối xe để lấy khoá, tự ngắt, rồi xe kết nối ngược vào iPhone (\"Chờ xe kết nối vào iPhone…\"). Khi ✅ Sẵn sàng, màn hình xe hiện \"NO ADDRESS\" cho tới khi bạn bắt đầu dẫn đường. Để app mở cạnh xe rồi xuất log để kiểm tra.", "Like the official app: the iPhone connects to the scooter to fetch a key, disconnects, then the scooter connects back to the iPhone (\"Waiting for the scooter…\"). Once ✅ Ready, the scooter display shows \"NO ADDRESS\" until you start navigating. Keep the app open near the scooter, then export the log to check."))
            }

            Section {
                HStack {
                    preset("⬅️ 200m", icon: PMP3.Icon.left, distance: 200)
                    preset("➡️ 50m", icon: PMP3.Icon.right, distance: 50)
                    preset("⬆️ 1,2km", icon: PMP3.Icon.straight, distance: 1200)
                }
                HStack {
                    preset(L("↩️ Quay đầu", "↩️ U-turn"), icon: PMP3.Icon.uTurnCounterClockwise, distance: 120)
                    preset(L("🔄 Vòng xuyến", "🔄 Roundabout"), icon: PMP3.Icon.roundaboutCCWStraight, distance: 300)
                    preset(L("🏁 Đến nơi", "🏁 Arrive"), icon: PMP3.Icon.arrive, distance: 10)
                }
                Stepper(L("Mã icon: \(icon)", "Icon code: \(icon)"), value: $icon, in: 0...255)
                Stepper(L("Khoảng cách: \(distance) m", "Distance: \(distance) m"), value: $distance, in: 0...20_000, step: 50)
                TextField(L("Tên đường", "Street name"), text: $roadName)
                Picker(L("Kiểu chữ (tag 4)", "Text style (tag 4)"), selection: $ble.dashOptions.textMode) {
                    Text(L("Thường", "Plain")).tag(PMP3.TextMode.plain)
                    Text("FF 00").tag(PMP3.TextMode.modeFF)
                    Text("01 00").tag(PMP3.TextMode.mode01)
                    Text("02 00").tag(PMP3.TextMode.mode02)
                }
                Toggle(L("Chữ IN HOA", "UPPERCASE"), isOn: $ble.dashOptions.uppercase)
                Button(L("Gửi icon \(icon)", "Send icon \(icon)")) { sendTest(icon: UInt8(icon), distance: distance) }
            } header: {
                Text(L("Gửi thử lên màn hình", "Test on the display"))
            } footer: {
                Text(L("Cần trạng thái ✅ Sẵn sàng. Hoặc vào màn hình chính → chọn điểm đến → Mô phỏng: hướng rẽ sẽ được gửi lên xe liên tục.", "Needs ✅ Ready. Or on the main screen pick a destination → Simulation: turns are sent to the scooter continuously."))
            }
            .disabled(!ble.isConnected)

            Section {
                TextField(L("Dòng 1", "Line 1"), text: $popupLine1)
                TextField(L("Dòng 2", "Line 2"), text: $popupLine2)
                Button(L("Gửi popup", "Send pop-up")) { ble.showPopup(popupLine1, popupLine2) }
            } header: {
                Text(L("Popup (thử nghiệm)", "Pop-up (experimental)"))
            } footer: {
                Text(L("Gói C2 của app gốc, hiện ~4 giây. Cần trạng thái ✅ Sẵn sàng.", "The official app's C2 packet, shown for ~4 s. Needs ✅ Ready."))
            }
            .disabled(!ble.isReady)

            Section(L("Gửi byte thô (debug)", "Send raw bytes (debug)")) {
                TextField("VD: 48 42", text: $rawHex)
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                Toggle(L("Thêm header PMP3 55 01 05", "Add PMP3 header 55 01 05"), isOn: $rawWithHeader)
                Button(L("Gửi", "Send")) {
                    guard let bytes = Hex.decode(rawHex), !bytes.isEmpty else { return }
                    ble.sendRaw((rawWithHeader ? PMP3.dashHeader : []) + bytes)
                }
                .disabled(Hex.decode(rawHex)?.isEmpty ?? true)
            }
            .disabled(!ble.isConnected)

            if !ble.gattTable.isEmpty {
                Section(L("Dịch vụ GATT", "GATT services")) {
                    ForEach(ble.gattTable, id: \.self) { Text($0).font(.caption.monospaced()) }
                }
            }

            Section {
                // Built only when shared — building it on every redraw is slow with a long log.
                Button { showReport = true } label: {
                    Label(L("Báo lỗi lên GitHub (kèm log)", "Report a bug on GitHub (with log)"), systemImage: "ladybug")
                }
                .sheet(isPresented: $showReport) { BugReportView(includeLog: true) }
                ShareLink(item: LogExport(ble: ble), preview: SharePreview("vespa-bluetooth-log.txt")) {
                    Label(L("Xuất log (gửi cho dev)", "Export log (send to dev)"), systemImage: "square.and.arrow.up")
                }
                Button(L("Xoá log", "Clear log")) { ble.clearLog() }
                ForEach(ble.log.suffix(200).reversed()) { line in
                    Text("\(line.time.formatted(date: .omitted, time: .standard))  \(line.text)")
                        .font(.caption2.monospaced())
                        .textSelection(.enabled)
                }
            } header: {
                Text(L("Log (\(ble.log.count) dòng, mới nhất ở trên)", "Log (\(ble.log.count) lines, newest first)"))
            }
        }
        .navigationTitle("Bluetooth Lab")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { ble.ensureCentral() }
    }

    private func preset(_ title: String, icon: UInt8, distance: Int) -> some View {
        Button(title) { sendTest(icon: icon, distance: distance) }
            .buttonStyle(.bordered)
            .font(.caption)
            .frame(maxWidth: .infinity)
    }

    private func sendTest(icon: UInt8, distance: Int) {
        self.icon = Int(icon)
        ble.sendTestDash(icon: icon, distance: distance, road: roadName)
    }
}

/// Log text produced lazily when the share sheet asks for it.
private struct LogExport: Transferable {
    let ble: VespaBLE

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .plainText) { export in
            await Data(export.ble.exportLog().utf8)
        }
        .suggestedFileName("vespa-bluetooth-log.txt")
    }
}
