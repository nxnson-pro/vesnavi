import CoreLocation
import Foundation
import MapKit
import Observation

/// A place the rider can route to in one tap.
struct SavedPlace: Codable, Equatable, Identifiable {
    var id = UUID()
    var name: String
    var subtitle: String?
    var latitude: Double
    var longitude: Double

    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }

    var mapItem: MKMapItem {
        let item = MKMapItem(placemark: MKPlacemark(coordinate: coordinate))
        item.name = name
        return item
    }

    init(name: String, subtitle: String? = nil, coordinate: CLLocationCoordinate2D) {
        self.name = name
        self.subtitle = subtitle
        latitude = coordinate.latitude
        longitude = coordinate.longitude
    }

    init(_ item: MKMapItem) {
        self.init(name: item.name ?? L("Điểm đến", "Destination"),
                  subtitle: item.placemark.title,
                  coordinate: item.placemark.coordinate)
    }

    /// Same spot (within ~30 m), to avoid duplicates in the lists.
    func isNear(_ other: SavedPlace) -> Bool {
        CLLocation(latitude: latitude, longitude: longitude)
            .distance(from: CLLocation(latitude: other.latitude, longitude: other.longitude)) < 30
    }
}

/// Where the scooter was left.
struct ParkingSpot: Codable, Equatable {
    var latitude: Double
    var longitude: Double
    var date: Date
    /// GPS accuracy in metres (nil for spots saved by older versions).
    var accuracy: Double?
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
}

/// Home, work, favourites, recent destinations and the parking spot — saved on the device.
@MainActor
@Observable
final class PlacesStore {
    private(set) var home: SavedPlace?
    private(set) var work: SavedPlace?
    private(set) var favorites: [SavedPlace] = []
    private(set) var recents: [SavedPlace] = []
    private(set) var parking: ParkingSpot?

    private struct Saved: Codable {
        var home: SavedPlace?
        var work: SavedPlace?
        var favorites: [SavedPlace]
        var recents: [SavedPlace]
        var parking: ParkingSpot?
    }
    private static let file = "places.json"
    static let maxRecents = 15

    init() {
        if let s = JSONFile.load(Saved.self, from: Self.file) {
            home = s.home
            work = s.work
            favorites = s.favorites
            recents = s.recents
            parking = s.parking
        }
    }

    func setHome(_ p: SavedPlace?) { home = p; save() }
    func setWork(_ p: SavedPlace?) { work = p; save() }

    func toggleFavorite(_ p: SavedPlace) {
        if let i = favorites.firstIndex(where: { $0.isNear(p) }) {
            favorites.remove(at: i)
        } else {
            favorites.append(p)
        }
        save()
    }

    func isFavorite(_ p: SavedPlace) -> Bool { favorites.contains { $0.isNear(p) } }

    func addRecent(_ p: SavedPlace) {
        recents.removeAll { $0.isNear(p) }
        recents.insert(p, at: 0)
        if recents.count > Self.maxRecents { recents.removeLast(recents.count - Self.maxRecents) }
        save()
    }

    func removeRecent(_ id: UUID) { recents.removeAll { $0.id == id }; save() }
    func removeFavorite(_ id: UUID) { favorites.removeAll { $0.id == id }; save() }

    func setParking(_ coordinate: CLLocationCoordinate2D, at date: Date = Date(), accuracy: Double? = nil) {
        parking = ParkingSpot(latitude: coordinate.latitude, longitude: coordinate.longitude, date: date,
                              accuracy: accuracy)
        save()
    }

    func clearParking() { parking = nil; save() }

    private func save() {
        JSONFile.save(Saved(home: home, work: work, favorites: favorites, recents: recents, parking: parking),
                      to: Self.file)
    }
}
