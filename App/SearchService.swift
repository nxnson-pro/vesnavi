import MapKit
import Observation

/// Free address / place search via Apple MapKit (no API key).
@MainActor
@Observable
final class SearchService: NSObject, MKLocalSearchCompleterDelegate {
    private(set) var results: [MKLocalSearchCompletion] = []
    @ObservationIgnored private let completer = MKLocalSearchCompleter()

    override init() {
        super.init()
        completer.delegate = self
        completer.resultTypes = [.address, .pointOfInterest]
    }

    func update(query: String, near center: CLLocationCoordinate2D?) {
        if let center {
            completer.region = MKCoordinateRegion(center: center, latitudinalMeters: 50_000, longitudinalMeters: 50_000)
        }
        if query.trimmingCharacters(in: .whitespaces).isEmpty {
            results = []
        } else {
            completer.queryFragment = query
        }
    }

    func resolve(_ completion: MKLocalSearchCompletion) async throws -> MKMapItem {
        let response = try await MKLocalSearch(request: MKLocalSearch.Request(completion: completion)).start()
        guard let item = response.mapItems.first else { throw URLError(.resourceUnavailable) }
        return item
    }

    nonisolated func completerDidUpdateResults(_ completer: MKLocalSearchCompleter) {
        let results = completer.results
        MainActor.assumeIsolated { self.results = results }
    }

    nonisolated func completer(_ completer: MKLocalSearchCompleter, didFailWithError error: Error) {
        MainActor.assumeIsolated { self.results = [] }
    }
}
