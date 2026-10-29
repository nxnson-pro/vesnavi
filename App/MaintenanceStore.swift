import Foundation
import Observation
import NavCore

/// Service items and the last known odometer (from the scooter), saved on the device.
@MainActor
@Observable
final class MaintenanceStore {
    private(set) var items: [MaintenanceItem]
    /// Latest odometer seen from the scooter (kept when disconnected).
    private(set) var odometer: Int?

    private struct Saved: Codable {
        var items: [MaintenanceItem]
        var odometer: Int?
    }
    private static let file = "maintenance.json"

    init() {
        let saved = JSONFile.load(Saved.self, from: Self.file)
        var items = saved?.items ?? MaintenanceItem.defaults
        // Items added in later versions.
        for d in MaintenanceItem.defaults where !items.contains(where: { $0.id == d.id }) { items.append(d) }
        self.items = items
        odometer = saved?.odometer
    }

    func status(_ item: MaintenanceItem) -> MaintenanceStatus {
        MaintenancePlanner.status(of: item, odometer: odometer, now: Date())
    }

    struct Entry: Identifiable {
        let item: MaintenanceItem
        let status: MaintenanceStatus
        var id: String { item.id }
    }

    /// Most urgent first (due, soon, ok, unknown).
    var sorted: [Entry] {
        items.map { Entry(item: $0, status: status($0)) }.sorted { a, b in
            if a.status.level != b.status.level { return a.status.level > b.status.level }
            return a.status.progress > b.status.progress
        }
    }

    var dueItems: [MaintenanceItem] { sorted.filter { $0.status.level == .due }.map(\.item) }

    func updateOdometer(_ km: Int) {
        // The odometer only changes once per kilometre, so saving on change is cheap.
        guard km != odometer else { return }
        odometer = km
        save()
    }

    func markDone(_ id: String, atKm km: Int?, on date: Date = Date()) {
        update(id) {
            $0.lastKm = km ?? odometer
            $0.lastDate = date
        }
    }

    func update(_ id: String, _ change: (inout MaintenanceItem) -> Void) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        change(&items[i])
        save()
    }

    private func save() {
        JSONFile.save(Saved(items: items, odometer: odometer), to: Self.file)
    }
}
