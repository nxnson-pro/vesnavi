import Foundation

/// Language of every text the app generates itself: instructions, distances, TFT lines.
public enum Language: String, Codable, CaseIterable, Sendable {
    case vi, en

    /// Picks the Vietnamese or English variant.
    public func pick(_ vi: String, _ en: String) -> String {
        self == .vi ? vi : en
    }
}
