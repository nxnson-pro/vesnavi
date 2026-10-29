import Foundation
import Observation
import NavCore

/// The language picked in Settings: Vietnamese (default), English, or whatever iOS uses for the app
/// (iOS Settings → VesNavi → Language).
enum AppLanguage: String, CaseIterable, Identifiable {
    case vi, en, system

    var id: String { rawValue }

    var resolved: Language {
        switch self {
        case .vi: return .vi
        case .en: return .en
        case .system:
            let code = Bundle.main.preferredLocalizations.first ?? Locale.preferredLanguages.first ?? "vi"
            return code.hasPrefix("vi") ? .vi : .en
        }
    }

    /// Each option names itself in its own language, so it can be found whatever is shown now.
    var label: String {
        switch self {
        case .vi: return "Tiếng Việt"
        case .en: return "English"
        case .system: return L("Theo ngôn ngữ iPhone", "Same as iPhone")
        }
    }
}

/// Current app language. Views that call `L` re-render on a switch: reading `language` is tracked
/// by Observation like any other model property.
@Observable
final class Localizer: @unchecked Sendable {
    static let shared = Localizer()

    private(set) var choice: AppLanguage
    private(set) var language: Language

    private init() {
        let saved = UserDefaults.standard.string(forKey: SettingsKey.appLanguage).flatMap(AppLanguage.init) ?? .vi
        choice = saved
        language = saved.resolved
    }

    func set(_ newChoice: AppLanguage) {
        UserDefaults.standard.set(newChoice.rawValue, forKey: SettingsKey.appLanguage)
        choice = newChoice
        language = newChoice.resolved
    }

    /// For dates and numbers formatted in code.
    var locale: Locale { Locale(identifier: language == .vi ? "vi_VN" : "en_US") }
}

/// Vietnamese / English pair, picked by the current app language.
func L(_ vi: String, _ en: String) -> String {
    Localizer.shared.language.pick(vi, en)
}

/// Current app language (for NavCore text: instructions, TFT lines).
var appLanguage: Language { Localizer.shared.language }

/// Locale matching the app language, for `.formatted(… .locale(appLocale))`.
var appLocale: Locale { Localizer.shared.locale }
