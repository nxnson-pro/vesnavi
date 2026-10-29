import NavCore
import SwiftUI

/// Most urgent service items on the "Xe của tôi" sheet.
struct MaintenanceCard: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let store = model.maintenance
        VStack(alignment: .leading, spacing: 12) {
            Label(L("Bảo dưỡng", "Maintenance"), systemImage: "wrench.adjustable.fill").font(.headline)
            ForEach(Array(store.sorted.prefix(2))) { entry in
                MaintenanceRow(item: entry.item, status: entry.status)
            }
            NavigationLink {
                MaintenanceView()
            } label: {
                HStack {
                    Text(L("Tất cả hạng mục", "All items"))
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                }
                .font(.subheadline.weight(.medium))
                .contentShape(Rectangle())  // the whole row, not just the text and chevron
            }
            .buttonStyle(.plain)
        }
        .card()
    }
}

struct MaintenanceView: View {
    @Environment(AppModel.self) private var model
    @State private var editing: MaintenanceItem?

    var body: some View {
        let store = model.maintenance
        List {
            Section {
                ForEach(store.sorted) { entry in
                    Button { editing = entry.item } label: {
                        MaintenanceRow(item: entry.item, status: entry.status)
                    }
                    .buttonStyle(.plain)
                }
            } footer: {
                let odo = store.odometer.map { "\($0.formatted()) km" }
                Text(L("ODO hiện tại: \(odo ?? "chưa có (kết nối xe một lần)"). Khoảng thay mặc định theo thói quen ở VN cho GTS 300, chỉnh được từng mục. Mục quá hạn sẽ hiện popup trên màn hình xe mỗi ngày một lần.",
                       "Current ODO: \(odo ?? "none yet (connect the scooter once)"). Default intervals follow common practice in Vietnam for a GTS 300; each item can be changed. Overdue items pop up on the scooter display once a day."))
            }
        }
        .navigationTitle(L("Bảo dưỡng", "Maintenance"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editing) { item in
            MaintenanceEditSheet(item: item).presentationDetents([.medium, .large])
        }
    }
}

struct MaintenanceRow: View {
    let item: MaintenanceItem
    let status: MaintenanceStatus

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: item.icon)
                .font(.headline)
                .foregroundStyle(.white)
                .frame(width: 34, height: 34)
                .background(color, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(item.displayName(appLanguage)).font(.subheadline.weight(.semibold))
                    Spacer()
                    Text(summary).font(.caption.weight(.medium)).foregroundStyle(color)
                }
                if status.level != .unknown {
                    ProgressView(value: min(status.progress, 1)).tint(color)
                }
            }
        }
        .contentShape(Rectangle())
    }

    private var color: Color {
        switch status.level {
        case .due: return .red
        case .soon: return .orange
        case .ok: return Theme.accent
        case .unknown: return .gray
        }
    }

    private var summary: String {
        switch status.level {
        case .unknown:
            return L("Chưa nhập lần cuối", "Last service not set")
        case .due:
            if let km = status.kmLeft, km <= 0 { return L("Quá \((-km).formatted()) km", "\((-km).formatted()) km overdue") }
            if let d = status.daysLeft, d <= 0 { return L("Quá hạn \(-d) ngày", "\(-d) days overdue") }
            return L("Đến hạn", "Due")
        case .soon, .ok:
            var parts: [String] = []
            if let km = status.kmLeft { parts.append(L("còn \(km.formatted()) km", "\(km.formatted()) km left")) }
            if let d = status.daysLeft { parts.append(d >= 60 ? L("\(d / 30) tháng", "\(d / 30) months") : L("\(d) ngày", "\(d) days")) }
            return parts.joined(separator: " · ")
        }
    }
}

/// Mark as done / set when it was last done / change the interval.
struct MaintenanceEditSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let item: MaintenanceItem
    @State private var lastKm = ""
    @State private var lastDate = Date()
    @State private var intervalKm = 0
    @State private var intervalMonths = 0

    var body: some View {
        let odo = model.maintenance.odometer
        NavigationStack {
            Form {
                Section {
                    Button {
                        model.maintenance.markDone(item.id, atKm: odo)
                        dismiss()
                    } label: {
                        Label(L("Vừa làm hôm nay\(odo.map { " (ODO \($0.formatted()) km)" } ?? "")", "Done today\(odo.map { " (ODO \($0.formatted()) km)" } ?? "")"),
                              systemImage: "checkmark.circle.fill")
                    }
                }
                Section(L("Lần làm gần nhất", "Last done")) {
                    TextField(L("ODO lúc làm (km)", "ODO at the time (km)"), text: $lastKm).keyboardType(.numberPad)
                    DatePicker(L("Ngày", "Date"), selection: $lastDate, in: ...Date(), displayedComponents: .date)
                }
                Section(L("Chu kỳ", "Interval")) {
                    Stepper(intervalKm > 0 ? L("Mỗi \(intervalKm.formatted()) km", "Every \(intervalKm.formatted()) km") : L("Không theo km", "No km interval"),
                            value: $intervalKm, in: 0...50_000, step: 500)
                    Stepper(intervalMonths > 0 ? L("Mỗi \(intervalMonths) tháng", "Every \(intervalMonths) months") : L("Không theo thời gian", "No time interval"),
                            value: $intervalMonths, in: 0...60)
                }
            }
            .navigationTitle(item.displayName(appLanguage))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("Huỷ", "Cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Lưu", "Save")) {
                        model.maintenance.update(item.id) {
                            $0.lastKm = Int(lastKm.filter(\.isNumber))
                            $0.lastDate = lastDate
                            $0.intervalKm = intervalKm > 0 ? intervalKm : nil
                            $0.intervalMonths = intervalMonths > 0 ? intervalMonths : nil
                        }
                        dismiss()
                    }
                }
            }
            .onAppear {
                lastKm = item.lastKm.map { "\($0)" } ?? ""
                lastDate = item.lastDate ?? Date()
                intervalKm = item.intervalKm ?? 0
                intervalMonths = item.intervalMonths ?? 0
            }
        }
        .tint(Theme.accent)
    }
}
