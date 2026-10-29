import MapKit
import SwiftUI

/// One-tap chips under the search field: home, work, parked scooter.
struct QuickPlaces: View {
    @Environment(AppModel.self) private var model
    @State private var showParking = false

    var body: some View {
        let places = model.places
        if places.home != nil || places.work != nil || places.parking != nil {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    if let home = places.home {
                        chip(L("Nhà", "Home"), "house.fill") { go(home) }
                            .accessibilityIdentifier("homeChip")
                    }
                    if let work = places.work {
                        chip(L("Công ty", "Work"), "briefcase.fill") { go(work) }
                    }
                    if let spot = places.parking {
                        chip("\(L("Xe đỗ", "Parked")) · \(spot.date.formatted(.relative(presentation: .named).locale(appLocale)))", "parkingsign") {
                            showParking = true
                        }
                    }
                }
            }
            .confirmationDialog(L("Chỗ đỗ xe", "Parking spot"), isPresented: $showParking, titleVisibility: .visible) {
                if let spot = places.parking {
                    Button(L("Chỉ đường đi bộ (Apple Maps)", "Walking directions (Apple Maps)")) {
                        let item = MKMapItem(placemark: MKPlacemark(coordinate: spot.coordinate))
                        item.name = "Xe Vespa"
                        item.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeWalking])
                    }
                    Button(L("Đặt lại tại vị trí hiện tại", "Set to where I am now")) { model.setParkingHere() }
                    Button(L("Xoá chỗ đỗ", "Clear parking spot"), role: .destructive) { places.clearParking() }
                }
            } message: {
                if let spot = places.parking {
                    let accuracy = spot.accuracy.map { " (± \(Int($0.rounded())) m)" } ?? ""
                    Text(L("Lưu lúc \(savedAt(spot.date)) khi tắt xe\(accuracy). Lệch thì đứng cạnh xe và chọn \"Đặt lại tại vị trí hiện tại\".",
                           "Saved at \(savedAt(spot.date)) when the scooter was switched off\(accuracy). If it's off, stand next to the scooter and choose \"Set to where I am now\"."))
                }
            }
        }
    }

    private func savedAt(_ date: Date) -> String {
        date.formatted(.dateTime.day().month().hour().minute().locale(appLocale))
    }

    private func chip(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 12)
                .frame(height: 36)
                .background(Color(.tertiarySystemFill), in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func go(_ place: SavedPlace) {
        Task { await model.planRoute(to: place.mapItem) }
    }
}

/// Star menu on the route preview: favourite / set as home / set as work.
struct SavePlaceMenu: View {
    @Environment(AppModel.self) private var model
    let place: SavedPlace

    var body: some View {
        let places = model.places
        Menu {
            Button(places.isFavorite(place) ? L("Bỏ yêu thích", "Remove from favourites") : L("Thêm vào yêu thích", "Add to favourites"),
                   systemImage: places.isFavorite(place) ? "star.slash" : "star") {
                places.toggleFavorite(place)
            }
            Button(L("Đặt làm Nhà", "Set as Home"), systemImage: "house") { places.setHome(rename(place, L("Nhà", "Home"))) }
            Button(L("Đặt làm Công ty", "Set as Work"), systemImage: "briefcase") { places.setWork(rename(place, L("Công ty", "Work"))) }
        } label: {
            Image(systemName: places.isFavorite(place) ? "star.fill" : "star")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(places.isFavorite(place) ? .yellow : .secondary)
                .frame(width: 32, height: 32)
                .background(Color(.tertiarySystemFill), in: Circle())
        }
    }

    private func rename(_ p: SavedPlace, _ name: String) -> SavedPlace {
        var copy = p
        copy.subtitle = p.name
        copy.name = name
        return copy
    }
}

/// Saved places list shown in the search sheet before typing.
struct SavedPlacesList: View {
    @Environment(AppModel.self) private var model
    let onPick: (SavedPlace) -> Void

    var body: some View {
        let places = model.places
        if places.home != nil || places.work != nil {
            Section(L("Đi nhanh", "Quick")) {
                if let home = places.home { row(home, "house.fill") }
                if let work = places.work { row(work, "briefcase.fill") }
            }
        }
        if !places.favorites.isEmpty {
            Section(L("Yêu thích", "Favourites")) {
                ForEach(places.favorites) { p in
                    row(p, "star.fill")
                        .swipeActions { Button(L("Xoá", "Delete"), role: .destructive) { places.removeFavorite(p.id) } }
                }
            }
        }
        if !places.recents.isEmpty {
            Section(L("Gần đây", "Recent")) {
                ForEach(places.recents) { p in
                    row(p, "clock.arrow.circlepath")
                        .swipeActions {
                            Button(L("Xoá", "Delete"), role: .destructive) { places.removeRecent(p.id) }
                            Button(L("Yêu thích", "Favourite")) { places.toggleFavorite(p) }.tint(.yellow)
                        }
                }
            }
        }
    }

    private func row(_ p: SavedPlace, _ icon: String) -> some View {
        Button { onPick(p) } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.title3)
                    .foregroundStyle(Theme.accent)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.name).foregroundStyle(.primary)
                    if let sub = p.subtitle, !sub.isEmpty {
                        Text(sub).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
        }
    }
}
