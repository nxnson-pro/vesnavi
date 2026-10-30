import Foundation

/// Bug reports to GitHub without any token in the app: the app opens a pre-filled "new issue" form
/// (`.github/ISSUE_TEMPLATE/bug_report.yml`) in the browser and the rider submits it from their own
/// GitHub account. Issues are public, so logs are redacted first.
public enum BugReport {
    public static let repository = "nxnson-pro/vesnavi"
    public static let template = "bug_report.yml"
    /// GitHub refuses "new issue" URLs much past 8 KB; stay below with some margin.
    public static let maxURLLength = 7_500
    static let maxDescriptionLength = 1_500

    /// Removes Bluetooth addresses and the given secrets (pairing key, auth answer, phone and nearby
    /// device names). Longer secrets go first so a short one can't leave half of a long one behind.
    public static func redact(_ text: String, secrets: [String]) -> String {
        var out = text
        for secret in Set(secrets).filter({ $0.count >= 3 }).sorted(by: { $0.count > $1.count }) {
            out = out.replacingOccurrences(of: secret, with: "<redacted>")
        }
        return out.replacingOccurrences(of: #"\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b"#,
                                        with: "XX:XX:XX:XX:XX:XX", options: .regularExpression)
    }

    /// The ways a byte secret is spelled in the log: "AA BB CC" and "AABBCC".
    public static func hexVariants(_ bytes: [UInt8]?) -> [String] {
        guard let bytes, !bytes.isEmpty else { return [] }
        return [Hex.encode(bytes), Hex.encode(bytes, separator: "")]
    }

    /// Pre-filled issue form. `log` is trimmed from its oldest lines until the URL fits.
    public static func issueURL(title: String, description: String, version: String, device: String,
                                log: String?) -> URL? {
        let base: [(String, String)] = [
            ("template", template),
            ("title", title),
            ("version", version),
            ("device", device),
            ("description", String(description.prefix(maxDescriptionLength))),
        ]
        func build(_ log: String?) -> String {
            let items = base + (log.map { [("log", $0)] } ?? [])
            return "https://github.com/\(repository)/issues/new?"
                + items.map { "\($0.0)=\(percentEncode($0.1))" }.joined(separator: "&")
        }
        guard let log else { return URL(string: build(nil)) }
        var lines = log.components(separatedBy: "\n")
        var trimmed = 0
        while true {
            let marker = trimmed > 0 ? ["[… \(trimmed) older lines trimmed]"] : []
            let url = build((marker + lines).joined(separator: "\n"))
            if url.count <= maxURLLength || lines.isEmpty { return URL(string: url) }
            let drop = min(lines.count, max(1, lines.count / 10))
            lines.removeFirst(drop)
            trimmed += drop
        }
    }

    /// Everything but RFC 3986 unreserved characters, so "+" and spaces survive GitHub's query parsing.
    static func percentEncode(_ s: String) -> String {
        var allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}
