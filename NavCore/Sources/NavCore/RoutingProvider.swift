import Foundation

/// Injected network call, so NavCore stays free of URLSession and parsing can be tested offline.
public typealias HTTPFetcher = @Sendable (URL) async throws -> Data

public protocol RoutingProvider: Sendable {
    var name: String { get }
    func route(from: Coordinate, to: Coordinate) async throws -> Route
}

public enum RoutingError: Error, Equatable, LocalizedError {
    case noRoute
    case invalidResponse
    case server(String)

    public var errorDescription: String? { message(.vi) }

    public func message(_ language: Language) -> String {
        switch self {
        case .noRoute: return language.pick("Không tìm được đường đi.", "No route found.")
        case .invalidResponse: return language.pick("Dữ liệu dẫn đường trả về không hợp lệ.", "The routing service returned invalid data.")
        case .server(let msg): return language.pick("Lỗi máy chủ dẫn đường: \(msg)", "Routing server error: \(msg)")
        }
    }
}
