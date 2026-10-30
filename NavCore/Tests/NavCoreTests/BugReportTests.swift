import XCTest
@testable import NavCore

final class BugReportTests: XCTestCase {
    func testRedactsAddressesAndSecrets() {
        let log = """
        Device connected to the server: 4C:11:AE:00:12:FF
        TX 00 01 AA BB 01 02 03 04 05 06
        Pairing: key=AABB010203040506
        Found: Lan's phone RSSI -60
        Found: BT-ROUTER RSSI -50
        """
        let key: [UInt8] = [0xAA, 0xBB, 1, 2, 3, 4, 5, 6]
        let out = BugReport.redact(log, secrets: BugReport.hexVariants(key) + ["Lan's phone", "ab"])
        XCTAssertFalse(out.contains("4C:11:AE"))
        XCTAssertTrue(out.contains("XX:XX:XX:XX:XX:XX"))
        XCTAssertFalse(out.contains("AA BB 01 02"))
        XCTAssertFalse(out.contains("AABB0102"))
        XCTAssertFalse(out.contains("Lan's phone"))
        XCTAssertTrue(out.contains("BT-ROUTER"), "the scooter's own name stays")
        XCTAssertTrue(out.contains("TX 00 01 <redacted>"))
        XCTAssertTrue(BugReport.hexVariants(nil).isEmpty)
    }

    func testShortReportKeepsTheWholeLog() throws {
        let url = try XCTUnwrap(BugReport.issueURL(title: "[iOS] Không kết nối", description: "C++ & 50%",
                                                   version: "1.0.8 (40)", device: "iPhone", log: "line 1\nline 2"))
        let s = url.absoluteString
        XCTAssertTrue(s.hasPrefix("https://github.com/nxnson-pro/vesnavi/issues/new?template=bug_report.yml&title="))
        XCTAssertTrue(s.contains("description=C%2B%2B%20%26%2050%25"), "+, & and % must not be mangled")
        XCTAssertTrue(s.contains("log=line%201%0Aline%202"))
        let items = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(items.first { $0.name == "title" }?.value, "[iOS] Không kết nối")
    }

    func testLongLogIsTrimmedFromTheOldestLines() throws {
        let log = (1...3000).map { "line \($0) C0 80 76 34 01 01 00 00" }.joined(separator: "\n")
        let url = try XCTUnwrap(BugReport.issueURL(title: "t", description: "d", version: "v", device: "x", log: log))
        let s = url.absoluteString
        XCTAssertLessThanOrEqual(s.count, BugReport.maxURLLength)
        let value = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first { $0.name == "log" }?.value)
        XCTAssertTrue(value.hasPrefix("[… "), "says how much was cut")
        XCTAssertTrue(value.hasSuffix("line 3000 C0 80 76 34 01 01 00 00"), "keeps the newest lines")
        XCTAssertFalse(value.contains("line 1 C0"))
    }
}
