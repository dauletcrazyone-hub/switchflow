import XCTest
@testable import SwitchFlowCompanion

final class PairingURITests: XCTestCase {
    private let fp = String(repeating: "ab", count: 32)

    func testParsesDesktopQR() throws {
        // Exactly what desktop/src-tauri/src/core.rs pairing_uri() produces.
        let raw = "switchflow://pair?v=1&hosts=192.168.1.24,10.0.0.5,127.0.0.1&port=47820&fp=\(fp)&s=Zm9vYmFyYmF6cXV4MTIz&n=%D0%9F%D0%9A%201"
        let t = try XCTUnwrap(PairingURI.parse(raw))
        XCTAssertEqual(t.hosts, ["192.168.1.24", "10.0.0.5", "127.0.0.1"])
        XCTAssertEqual(t.port, 47820)
        XCTAssertEqual(t.fingerprint, fp)
        XCTAssertEqual(t.desktopName, "ПК 1")
    }

    func testRejectsForeignCodes() {
        XCTAssertNil(PairingURI.parse("https://example.com"))
        XCTAssertNil(PairingURI.parse("switchflow://pair?v=1&hosts=evil.com&port=47820&fp=\(fp)&s=Zm9vYmFyYmF6cXV4MTIz"))
        XCTAssertNil(PairingURI.parse("switchflow://pair?v=1&hosts=1.2.3.4&port=47820&fp=short&s=Zm9vYmFyYmF6cXV4MTIz"))
        XCTAssertNil(PairingURI.parse("switchflow://pair?v=2&hosts=1.2.3.4&port=47820&fp=\(fp)&s=Zm9vYmFyYmF6cXV4MTIz"))
    }

    func testRRuleRoundTrip() throws {
        let rule = try XCTUnwrap(RRule.parse("FREQ=WEEKLY;INTERVAL=2;COUNT=10;BYDAY=MO,WE"))
        XCTAssertEqual(rule.frequency, .weekly)
        XCTAssertEqual(rule.interval, 2)
        XCTAssertEqual(rule.recurrenceEnd?.occurrenceCount, 10)
        XCTAssertEqual(RRule.string(from: rule), "FREQ=WEEKLY;INTERVAL=2;COUNT=10;BYDAY=MO,WE")
    }

    func testCommandPayloadKeepsIntegers() throws {
        let json = #"{"commands":[{"seq":3,"type":"upload","payload":{"sessionId":"s1","items":[{"key":"i0","sourceId":"A/L0/001"}]}}]}"#
        let r = try JSONDecoder().decode(CommandsResponse.self, from: Data(json.utf8))
        let up = try r.commands[0].decode(UploadCommand.self)
        XCTAssertEqual(up.items.first?.sourceId, "A/L0/001")
        let prep = #"{"commands":[{"seq":4,"type":"prepare","payload":{"sessionId":"s1","categories":["photos"],"totalBytes":123456789012}}]}"#
        let p = try JSONDecoder().decode(CommandsResponse.self, from: Data(prep.utf8)).commands[0].decode(PrepareCommand.self)
        XCTAssertEqual(p.totalBytes, 123_456_789_012)
    }
}
