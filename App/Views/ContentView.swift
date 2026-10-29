import MapKit
import NavCore
import SwiftUI

/// Single map screen: everything else floats on top of it as cards or opens as a sheet.
struct ContentView: View {
    @Environment(AppModel.self) private var model
    @State private var camera: MapCameraPosition = .userLocation(fallback: .automatic)
    @State private var sheet: Sheet?
    @AppStorage(SettingsKey.safetyNoticeAccepted) private var safetyNoticeAccepted = false

    enum Sheet: String, Identifiable {
        case search, scooter, settings
        var id: String { rawValue }
    }

    var body: some View {
        Map(position: $camera) {
            UserAnnotation()
            if !model.routeCoordinates.isEmpty {
                MapPolyline(coordinates: model.routeCoordinates)
                    .stroke(Theme.accent, style: StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round))
            }
            if let dest = model.destination {
                Marker(dest.name ?? L("Điểm đến", "Destination"), systemImage: "flag.checkered", coordinate: dest.placemark.coordinate)
                    .tint(Theme.accent)
            }
            if let spot = model.places.parking, model.phase != .navigating {
                if let accuracy = spot.accuracy, accuracy > 10 {
                    // How far off the saved spot may be.
                    MapCircle(center: spot.coordinate, radius: accuracy)
                        .foregroundStyle(.blue.opacity(0.15))
                }
                Annotation(L("Xe đỗ", "Parked"), coordinate: spot.coordinate) {
                    Text("P")
                        .font(.system(size: 15, weight: .heavy, design: .rounded))
                        .foregroundStyle(.white)
                        .frame(width: 30, height: 30)
                        .background(.blue, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(.white, lineWidth: 2))
                }
            }
            if let p = model.simulatedPosition {
                Annotation("", coordinate: p.clCoordinate) {
                    Image(systemName: "scooter")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(8)
                        .background(Theme.accent, in: Circle())
                        .overlay(Circle().stroke(.white, lineWidth: 2))
                }
            }
        }
        // Flat map: 3D terrain/buildings cost GPU (heat, battery) for no gain on a scooter.
        .mapStyle(.standard(elevation: .flat, emphasis: .muted, pointsOfInterest: .excludingAll))
        .mapControls { MapCompass() }
        .safeAreaInset(edge: .top) { TopBar(sheet: $sheet).padding(.horizontal) }
        .safeAreaInset(edge: .bottom) {
            VStack(alignment: .trailing, spacing: 12) {
                if model.phase != .navigating {
                    MapIconButton(systemImage: "location.fill") {
                        camera = .userLocation(fallback: .automatic)
                    }
                }
                BottomPanel(sheet: $sheet)
            }
            .padding(.horizontal)
            .padding(.bottom, 8)
        }
        .sheet(item: $sheet) { which in
            switch which {
            case .search: SearchView()
            case .scooter: ScooterView()
            case .settings: SettingsView()
            }
        }
        .sheet(isPresented: safetyNoticeBinding) {
            SafetyNoticeView { safetyNoticeAccepted = true }
        }
        .alert(L("Có lỗi", "Error"), isPresented: errorBinding) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
        .onChange(of: model.phase) { _, phase in
            switch phase {
            case .preview:
                camera = .automatic
            case .navigating where !model.isSimulating:
                camera = .userLocation(followsHeading: true, fallback: .automatic)
            case .idle:
                camera = .userLocation(fallback: .automatic)
            default:
                break
            }
        }
        .onChange(of: model.simulatedPosition) { _, p in
            guard let p else { return }
            camera = .camera(MapCamera(centerCoordinate: p.clCoordinate, distance: 700, pitch: 45))
        }
        .tint(Theme.accent)
        // Dates, numbers and pickers follow the app language, sheets included.
        .environment(\.locale, appLocale)
    }


    /// Once, on first launch (not in the demo recordings, where it would cover the tours).
    private var safetyNoticeBinding: Binding<Bool> {
        Binding(get: { !safetyNoticeAccepted && !UserDefaults.standard.bool(forKey: "demoMode") },
                set: { _ in })
    }

    private var errorBinding: Binding<Bool> {
        Binding(get: { model.errorMessage != nil },
                set: { if !$0 { model.errorMessage = nil } })
    }
}

/// Its own view so that navigation and scooter updates (every second) re-render only this card,
/// not the whole map.
private struct TopBar: View {
    @Environment(AppModel.self) private var model
    @Binding var sheet: ContentView.Sheet?

    var body: some View {
        if model.phase == .navigating, let state = model.navState {
            ManeuverCard(state: state, note: model.routeNote, onScooter: model.vespa.isReady,
                         speedKmh: model.vespa.telemetry?.dashboardSpeedKmh)
        } else {
            HStack {
                Button { sheet = .scooter } label: { ScooterStatusPill(ble: model.vespa) }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("scooterButton")
                Spacer()
                MapIconButton(systemImage: "gearshape") { sheet = .settings }
                    .accessibilityIdentifier("settingsButton")
            }
        }
    }
}

private struct BottomPanel: View {
    @Environment(AppModel.self) private var model
    @Binding var sheet: ContentView.Sheet?

    var body: some View {
        switch model.phase {
        case .idle: idle
        case .routing: routing
        case .preview: preview
        case .navigating: navigating
        }
    }

    private var idle: some View {
        VStack(spacing: 14) {
            Button { sheet = .search } label: {
                HStack(spacing: 12) {
                    Image(systemName: "magnifyingglass")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(Theme.accent)
                    Text(L("Đi đâu?", "Where to?"))
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.primary)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .frame(height: 54)
                .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(.plain)

            QuickPlaces()

            if let t = model.vespa.telemetry {
                Button { sheet = .scooter } label: {
                    TelemetryStrip(telemetry: t, rangeKm: model.trips.fuelEstimate?.rangeKm)
                }
                    .buttonStyle(.plain)
            } else if model.vespa.state == .idle {
                Button { model.vespa.quickConnect() } label: {
                    Label(L("Kết nối xe", "Connect scooter"), systemImage: "antenna.radiowaves.left.and.right")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            }
        }
        .card()
    }

    private var routing: some View {
        HStack(spacing: 12) {
            ProgressView()
            Text(L("Đang tìm đường…", "Finding a route…")).font(.headline)
            Spacer()
            Button(L("Huỷ", "Cancel")) { model.stopNavigation() }
        }
        .card()
    }

    private var preview: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(model.destination?.name ?? L("Điểm đến", "Destination"))
                        .font(.title2.weight(.bold))
                        .lineLimit(2)
                    if let route = model.route {
                        Text("\(DurationFormatter.text(route.duration)) · \(DistanceFormatter.text(route.distance, language: appLanguage)) · \(L("đến lúc", "arrive")) \(arrival(after: route.duration))")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if let dest = model.destination {
                    SavePlaceMenu(place: SavedPlace(dest))
                }
                Button { model.stopNavigation() } label: {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .background(Color(.tertiarySystemFill), in: Circle())
                }
                .buttonStyle(.plain)
            }
            if let note = model.routeNote {
                Label(note, systemImage: "info.circle").font(.caption).foregroundStyle(.orange)
            }
            if let osmCopyrightURL {
                // ODbL: the route line is OpenStreetMap data (OSRM / GraphHopper).
                Link(L("Tuyến đường: © OpenStreetMap contributors", "Route: © OpenStreetMap contributors"),
                     destination: osmCopyrightURL)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Button { model.startNavigation(simulate: false) } label: {
                Text(L("Bắt đầu", "Start")).font(.headline).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            Button { model.startNavigation(simulate: true) } label: {
                Label(L("Chạy thử (mô phỏng)", "Test run (simulation)"), systemImage: "play.circle").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderless)
            .accessibilityIdentifier("simulateButton")
        }
        .card()
    }

    private var navigating: some View {
        HStack(spacing: 14) {
            if let s = model.navState {
                VStack(alignment: .leading, spacing: 2) {
                    Text(arrival(after: s.remainingDuration))
                        .font(.title2.weight(.bold))
                        .monospacedDigit()
                    Text("\(DurationFormatter.text(s.remainingDuration)) · \(DistanceFormatter.text(s.remainingDistance, language: appLanguage))")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if model.isSimulating {
                        Text(L("Đang mô phỏng", "Simulating")).font(.caption.weight(.semibold)).foregroundStyle(.orange)
                    }
                }
            }
            Spacer()
            if let t = model.vespa.telemetry, let rpm = t.rpm, t.engineRunning {
                VStack(spacing: 0) {
                    Text("\(rpm)").font(.headline).monospacedDigit()
                    Text(L("vòng/ph", "rpm")).font(.caption2).foregroundStyle(.secondary)
                }
            }
            Button(role: .destructive) { model.stopNavigation() } label: {
                Image(systemName: "xmark")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 52, height: 52)
                    .background(.red, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("Dừng dẫn đường", "Stop navigation"))
        }
        .card()
    }

    private func arrival(after seconds: Double) -> String {
        Date().addingTimeInterval(seconds).formatted(.dateTime.hour().minute().locale(appLocale))
    }
}

/// Three key engine numbers under the search field.
struct TelemetryStrip: View {
    let telemetry: ScooterTelemetry
    var rangeKm: Double?

    var body: some View {
        HStack(spacing: 0) {
            item("thermometer.medium", telemetry.coolantC.map { "\($0)°" } ?? "–", L("Nước mát", "Coolant"))
            Divider().frame(height: 32)
            item("minus.plus.batteryblock", telemetry.batteryVolts.map { String(format: "%.1f V", $0) } ?? "–", L("Ắc quy", "Battery"))
            Divider().frame(height: 32)
            if let rangeKm {
                item("fuelpump", "\(Int(rangeKm.rounded())) km", L("Còn đi được", "Range"))
            } else {
                item("road.lanes", telemetry.odometerKm.map { "\($0.formatted()) km" } ?? "–", "ODO")
            }
        }
    }

    private func item(_ icon: String, _ value: String, _ title: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.headline).monospacedDigit()
            Label(title, systemImage: icon).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}
