import NavCore
import SwiftUI

/// Fuel left, range and when to refuel, estimated from the ECU's fuel rate since the last fill-up.
struct FuelCard: View {
    @Environment(AppModel.self) private var model
    @State private var showLevel = false

    var body: some View {
        let store = model.trips
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(L("Nhiên liệu", "Fuel"), systemImage: "fuelpump.fill").font(.headline)
                Spacer()
                Menu {
                    Button(L("Vừa đổ đầy bình", "Just filled up"), systemImage: "fuelpump") { store.filledUp() }
                    Button(L("Chỉnh mức xăng hiện tại…", "Set current fuel level…"), systemImage: "slider.horizontal.3") { showLevel = true }
                } label: {
                    Image(systemName: "ellipsis.circle").font(.title3)
                }
            }
            if let at = store.refuelDetectedAt, Date().timeIntervalSince(at) < 3600 {
                Label(L("Đã nhận biết bạn vừa đổ xăng — mức xăng đã cập nhật", "Fill-up detected — fuel level updated"), systemImage: "checkmark.circle.fill")
                    .font(.subheadline)
                    .foregroundStyle(.green)
            }
            if let e = store.fuelEstimate {
                ProgressView(value: e.fraction)
                    .tint(e.litersLeft <= FuelEstimator.reserveLiters ? .red : Theme.accent)
                HStack(alignment: .firstTextBaseline) {
                    Text("≈ \(e.rangeKm, format: .number.precision(.fractionLength(0))) km")
                        .font(.system(size: 30, weight: .bold, design: .rounded))
                    Text(L("còn đi được", "range")).font(.subheadline).foregroundStyle(.secondary)
                    Spacer()
                    Text("\(e.litersLeft, format: .number.precision(.fractionLength(1))) L")
                        .font(.headline).monospacedDigit()
                }
                Text(refuelText(e))
                    .font(.subheadline)
                    .foregroundStyle(e.litersLeft <= FuelEstimator.reserveLiters ? .red : .secondary)
                Text("\(L("Tiêu thụ", "Consumption")) \(e.litersPer100Km, format: .number.precision(.fractionLength(1))) L/100 km · \(100 / e.litersPer100Km, format: .number.precision(.fractionLength(0))) km/L")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let bars = store.fuel.lastFuelBars {
                    Text(L("Đồng hồ xăng trên xe: \(bars)/\(store.fuel.barsMax) vạch\(store.fuel.filledAt == nil ? " (đang ước theo đồng hồ)" : "")", "Scooter fuel gauge: \(bars)/\(store.fuel.barsMax) bars\(store.fuel.filledAt == nil ? " (estimating from the gauge)" : "")"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } else {
                Text(L("Kết nối xe để đọc đồng hồ xăng, hoặc khi đổ đầy bấm ⋯ → \"Vừa đổ đầy bình\". App tự nhận biết khi bạn đổ xăng (đồng hồ tăng ≥ 2 vạch) và tính xăng còn lại theo lượng xăng xe báo đã tiêu thụ.", "Connect the scooter to read its fuel gauge, or after filling up tap ⋯ → \"Just filled up\". The app detects fill-ups (gauge up ≥ 2 bars) and tracks fuel left from the consumption the scooter reports."))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button(L("Vừa đổ đầy bình", "Just filled up")) { store.filledUp() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .card()
        .sheet(isPresented: $showLevel) { FuelLevelSheet().presentationDetents([.medium]) }
    }

    private func refuelText(_ e: FuelEstimate) -> String {
        if e.litersLeft <= FuelEstimator.reserveLiters { return L("Sắp hết xăng — nên đổ ngay", "Almost empty — refuel now") }
        guard let date = e.refuelBy else {
            return L("Ghi thêm vài hành trình để dự tính ngày đổ xăng", "Record a few more trips to predict the refuel date")
        }
        let days = Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: Date()),
                                                   to: Calendar.current.startOfDay(for: date)).day ?? 0
        let when: String
        switch days {
        case ..<1: when = L("hôm nay", "today")
        case 1: when = L("ngày mai", "tomorrow")
        default:
            let day = date.formatted(.dateTime.weekday(.wide).day().month().locale(appLocale))
            when = L("\(day) (\(days) ngày nữa)", "\(day) (in \(days) days)")
        }
        return L("Nên đổ xăng: \(when)", "Refuel: \(when)")
    }
}

/// The rider reads the fuel gauge and sets the level by hand (e.g. after a partial fill).
private struct FuelLevelSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var fraction = 0.5
    @State private var tank = 8.5
    @State private var barsMax = 7

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading) {
                        Text("\(Int((fraction * 100).rounded()))% · \(fraction * tank, format: .number.precision(.fractionLength(1))) L")
                            .font(.title2.weight(.bold))
                        Slider(value: $fraction, in: 0...1, step: 0.125)
                    }
                } footer: {
                    Text(L("Ước theo đồng hồ xăng trên xe (mỗi nấc = 1/8 bình).", "Read it off the scooter's fuel gauge (each step = 1/8 tank)."))
                }
                Section {
                    Stepper("\(L("Dung tích bình", "Tank size")): \(tank, format: .number.precision(.fractionLength(1))) L",
                            value: $tank, in: 5...15, step: 0.5)
                    Stepper(L("Bình đầy = \(barsMax) vạch trên đồng hồ xe", "Full tank = \(barsMax) bars on the gauge"), value: $barsMax, in: 4...10)
                } footer: {
                    Text(L("Đếm số vạch khi bình đầy để app đọc đồng hồ xăng của xe chính xác.", "Count the bars with a full tank so the app reads the scooter's gauge correctly."))
                }
            }
            .navigationTitle(L("Mức xăng hiện tại", "Current fuel level"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("Huỷ", "Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Lưu", "Save")) {
                        model.trips.setTankLiters(tank)
                        model.trips.setBarsMax(barsMax)
                        model.trips.setFuelLevel(fraction)
                        dismiss()
                    }
                }
            }
            .onAppear {
                tank = model.trips.fuel.tankLiters
                barsMax = model.trips.fuel.barsMax
                fraction = model.trips.fuelEstimate?.fraction ?? 0.5
            }
        }
        .tint(Theme.accent)
    }
}

/// Live trip + link to the history.
struct TripsCard: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let store = model.trips
        VStack(alignment: .leading, spacing: 12) {
            Label(L("Hành trình", "Trips"), systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                .font(.headline)
            if let live = store.current {
                HStack {
                    Circle().fill(.red).frame(width: 8, height: 8)
                    Text("\(L("Đang ghi", "Recording")) · \(live.distanceKm, format: .number.precision(.fractionLength(1))) km · \(Duration.seconds(live.duration).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated).locale(appLocale)))")
                        .font(.subheadline)
                }
            }
            let month = monthTotals(store.trips)
            HStack(spacing: 0) {
                stat("\(month.km.formatted(.number.precision(.fractionLength(0)))) km", L("Tháng này", "This month"))
                Divider().frame(height: 30)
                stat("\(month.liters.formatted(.number.precision(.fractionLength(1)))) L", L("Xăng", "Fuel"))
                Divider().frame(height: 30)
                stat("\(month.count)", L("Chuyến", "Trips"))
            }
            NavigationLink {
                TripsView()
            } label: {
                HStack {
                    Text(L("Xem tất cả hành trình", "See all trips"))
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                }
                .font(.subheadline.weight(.medium))
                .contentShape(Rectangle())  // the whole row, not just the text and chevron
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("allTripsLink")
        }
        .card()
    }

    private func stat(_ value: String, _ title: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.headline).monospacedDigit()
            Text(title).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func monthTotals(_ trips: [TripSummary]) -> (km: Double, liters: Double, count: Int) {
        let cal = Calendar.current
        let inMonth = trips.filter { cal.isDate($0.start, equalTo: Date(), toGranularity: .month) }
        return (inMonth.reduce(0) { $0 + $1.distanceKm }, inMonth.reduce(0) { $0 + $1.fuelLiters }, inMonth.count)
    }
}

struct TripsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let trips = Array(model.trips.trips.reversed())
        List {
            if trips.isEmpty {
                Text(L("Chưa có hành trình nào. App tự ghi mỗi khi xe nổ máy và đã kết nối.", "No trips yet. The app records one whenever the engine runs while connected."))
                    .foregroundStyle(.secondary)
            }
            ForEach(trips) { trip in
                NavigationLink {
                    TripDetailView(trip: trip)
                } label: {
                    TripRow(trip: trip)
                }
            }
            .onDelete { offsets in
                model.trips.delete(Set(offsets.map { trips[$0].id }))
            }
        }
        .navigationTitle(L("Hành trình", "Trips"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct TripRow: View {
    let trip: TripSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(trip.start.formatted(.dateTime.weekday(.abbreviated).day().month().hour().minute().locale(appLocale)))
                .font(.subheadline.weight(.semibold))
            HStack(spacing: 12) {
                Label("\(trip.distanceKm.formatted(.number.precision(.fractionLength(1)))) km", systemImage: "road.lanes")
                Label(Duration.seconds(trip.duration).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated).locale(appLocale)),
                      systemImage: "clock")
                if let l = trip.litersPer100Km {
                    Label("\(l.formatted(.number.precision(.fractionLength(1)))) L/100", systemImage: "fuelpump")
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }
}

struct TripDetailView: View {
    let trip: TripSummary

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                MetricTile(icon: "road.lanes", title: L("Quãng đường", "Distance"), value: fmt(trip.distanceKm, 1), unit: "km")
                MetricTile(icon: "clock", title: L("Thời gian", "Time"),
                           value: Duration.seconds(trip.duration).formatted(.units(allowed: [.hours, .minutes], width: .narrow).locale(appLocale)),
                           unit: "")
                MetricTile(icon: "speedometer", title: L("Tốc độ TB", "Avg speed"), value: fmt(trip.avgSpeedKmh, 0), unit: "km/h")
                MetricTile(icon: "gauge.with.dots.needle.67percent", title: L("Tốc độ tối đa", "Max speed"), value: fmt(trip.maxSpeedKmh, 0), unit: "km/h")
                MetricTile(icon: "fuelpump", title: L("Xăng đã dùng", "Fuel used"), value: fmt(trip.fuelLiters, 2), unit: "L")
                MetricTile(icon: "leaf", title: L("Tiêu thụ", "Consumption"), value: trip.litersPer100Km.map { fmt($0, 1) }, unit: "L/100km")
                MetricTile(icon: "thermometer.medium", title: L("Nước mát TB", "Avg coolant"), value: trip.avgCoolantC.map { fmt($0, 0) }, unit: "°C")
                MetricTile(icon: "thermometer.high", title: L("Nước mát cao nhất", "Max coolant"), value: trip.maxCoolantC.map { "\($0)" }, unit: "°C")
                MetricTile(icon: "sun.max", title: L("Nhiệt độ ngoài TB", "Avg outside temp"), value: trip.avgAmbientC.map { fmt($0, 0) }, unit: "°C")
                MetricTile(icon: "arrow.up.to.line", title: L("Vòng tua cao nhất", "Peak RPM"), value: "\(trip.maxRPM)", unit: L("vòng/ph", "rpm"))
            }
            .padding()
            if let s = trip.startOdometerKm, let e = trip.endOdometerKm {
                Text("ODO \(s) → \(e) km").font(.caption).foregroundStyle(.secondary)
            }
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(trip.start.formatted(.dateTime.day().month().hour().minute().locale(appLocale)))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func fmt(_ v: Double, _ digits: Int) -> String {
        v.formatted(.number.precision(.fractionLength(digits)))
    }
}
