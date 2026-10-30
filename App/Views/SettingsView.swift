import NavCore
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @AppStorage(SettingsKey.graphHopperKey) private var graphHopperKey = ""
    @AppStorage(SettingsKey.graphHopperProfile) private var graphHopperProfile = "car"
    @AppStorage(SettingsKey.simulationSpeedKmh) private var simulationSpeed = 30.0
    @AppStorage(SettingsKey.keepScreenOn) private var keepScreenOn = false
    @AppStorage(SettingsKey.rememberParking) private var rememberParking = true

    var body: some View {
        @Bindable var ble = model.vespa
        @Bindable var speedLimits = model.speedLimits
        NavigationStack {
            Form {
                Section {
                    Picker(selection: languageBinding) {
                        ForEach(AppLanguage.allCases) { Text($0.label).tag($0) }
                    } label: {
                        Label("Ngôn ngữ / Language", systemImage: "globe")
                    }
                    .accessibilityIdentifier("languagePicker")
                }

                Section {
                    Toggle(isOn: $ble.autoReconnect) {
                        Label(L("Tự kết nối khi bật xe", "Auto-connect when the scooter starts"), systemImage: "antenna.radiowaves.left.and.right")
                    }
                    LabeledContent {
                        Text(ble.state.title)
                    } label: {
                        Label(L("Trạng thái", "Status"), systemImage: "scooter")
                    }
                } header: {
                    Text(L("Xe", "Scooter"))
                } footer: {
                    Text(L("Chạm vào biểu tượng xe ở màn hình chính để xem thông số và kết nối.", "Tap the scooter badge on the main screen for engine data and connecting."))
                }

                Section {
                    Picker(selection: $ble.idleDisplay) {
                        ForEach(VespaBLE.IdleDisplay.allCases) { Text($0.title).tag($0) }
                    } label: {
                        Label(L("Khi không dẫn đường", "When not navigating"), systemImage: "text.alignleft")
                    }
                    Toggle(isOn: $ble.warningsEnabled) {
                        Label(L("Cảnh báo popup", "Pop-up warnings"), systemImage: "exclamationmark.bubble")
                    }
                } header: {
                    Text(L("Màn hình xe (thử nghiệm)", "Scooter display (experimental)"))
                } footer: {
                    Text(L("Dòng chữ hiện nhiệt độ nước làm mát và điện áp ắc quy, ví dụ \"NUOC 85C  AQ 13.8V\". Màn hình xe chuyển sang giao diện dẫn đường để hiện dòng chữ. Popup báo khi nước làm mát ≥ 105°C, ắc quy không sạc hoặc yếu (tối đa 1 lần / 5 phút).", "The text line shows coolant temperature and battery voltage, e.g. \"COOL 85C  BAT 13.8V\". The scooter display switches to its navigation layout to show the line. Pop-ups warn when coolant ≥ 105°C or the battery is not charging or low (at most once every 5 minutes)."))
                }

                Section {
                    Picker(selection: $graphHopperProfile) {
                        Text(L("Ô tô (gói miễn phí)", "Car (free plan)")).tag("car")
                        Text(L("Xe tay ga (gói trả phí)", "Scooter (paid plan)")).tag("scooter")
                    } label: {
                        Label(L("Loại đường", "Route profile"), systemImage: "road.lanes")
                    }
                    TextField(L("GraphHopper API key (tuỳ chọn)", "GraphHopper API key (optional)"), text: $graphHopperKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text(L("Tìm đường", "Routing"))
                } footer: {
                    Text(L("Mặc định dùng OSRM (miễn phí, không cần key). Có key GraphHopper (đăng ký miễn phí ≈500 lượt/ngày) thì dùng GraphHopper, lỗi hoặc hết lượt sẽ tự quay về OSRM. Gói miễn phí chỉ có kiểu Ô tô (đi đúng đường một chiều, phù hợp xe máy).", "OSRM is used by default (free, no key). With a GraphHopper key (free sign-up, ≈500 requests/day) GraphHopper is used, falling back to OSRM on errors or when the quota runs out. The free plan only has the Car profile (respects one-way streets, fine for a scooter)."))
                }

                Section {
                    Toggle(isOn: $keepScreenOn) {
                        Label(L("Giữ màn hình điện thoại sáng", "Keep the phone screen on"), systemImage: "sun.max")
                    }
                    Picker(selection: $speedLimits.source) {
                        Text(L("Tắt", "Off")).tag(SpeedLimitSource.off)
                        Text("OpenStreetMap").tag(SpeedLimitSource.osm)
                        Text(L("OpenStreetMap + luật VN", "OpenStreetMap + VN rules")).tag(SpeedLimitSource.osmWithVietnamDefaults)
                        Text(L("GraphHopper (cần key)", "GraphHopper (key needed)")).tag(SpeedLimitSource.graphHopper)
                    } label: {
                        Label(L("Giới hạn tốc độ", "Speed limit"), systemImage: "exclamationmark.octagon")
                    }
                } header: {
                    Text(L("Dẫn đường", "Navigation"))
                } footer: {
                    Text(L("Màn hình điện thoại có thể tắt khi dẫn đường để tiết kiệm pin. Giới hạn tốc độ hiện trên màn hình xe và trong app; nguồn miễn phí: OpenStreetMap (không cần key). \"+ luật VN\": đường chưa có dữ liệu thì ước theo Thông tư 38/2024 (trong khu dân cư: 60 km/h đường đôi/một chiều ≥ 2 làn, 50 km/h còn lại), ghi \"ước tính\".", "The phone screen may turn off while navigating to save battery. The speed limit shows on the scooter and in the app; free source: OpenStreetMap (no key). \"+ VN rules\": roads without data use Vietnam's Circular 38/2024 (built-up areas: 60 km/h on divided or one-way roads with ≥ 2 lanes, 50 km/h otherwise), marked \"estimate\"."))
                }

                Section {
                    Toggle(isOn: $rememberParking) {
                        Label(L("Nhớ chỗ đỗ xe", "Remember parking spot"), systemImage: "parkingsign")
                    }
                    .onChange(of: rememberParking) { _, on in
                        if on { model.location.requestAlways() }
                    }
                    if rememberParking && !model.location.hasAlways {
                        Button(L("Cho phép vị trí \"Luôn luôn\"", "Allow \"Always\" location")) { model.location.requestAlways() }
                    }
                } footer: {
                    Text(L("Lưu vị trí lúc tắt xe. Để lấy được vị trí khi điện thoại trong túi, cần quyền vị trí \"Luôn luôn\"; nếu không, chỉ lưu được khi app đang mở (hoặc đang dẫn đường).", "Saves the location when the scooter is switched off. With the phone in a pocket this needs \"Always\" location access; otherwise it only works while the app is open (or navigating)."))
                }

                Section {
                    Stepper(value: $simulationSpeed, in: 10...80, step: 5) {
                        Label(L("Tốc độ: \(Int(simulationSpeed)) km/h", "Speed: \(Int(simulationSpeed)) km/h"), systemImage: "play.circle")
                    }
                } header: {
                    Text(L("Chạy thử", "Test run"))
                }

                AboutSection(version: AppInfo.version)
            }
            .navigationTitle(L("Cài đặt", "Settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Xong", "Done")) { dismiss() }
                        .accessibilityIdentifier("settingsDone")
                }
            }
        }
        .tint(Theme.accent)
    }

    private var languageBinding: Binding<AppLanguage> {
        Binding(get: { Localizer.shared.choice }, set: { Localizer.shared.set($0) })
    }
}
