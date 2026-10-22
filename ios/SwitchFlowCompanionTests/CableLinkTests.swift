import XCTest
@testable import SwitchFlowCompanion

final class CableLinkTests: XCTestCase {
    // Same rule as desktop cable.rs and Android CableLink.kt.
    func testDataChunksFitOneTransferAndAvoidPacketBoundaries() {
        for total in stride(from: 1, through: 40_000, by: 7) {
            var left = total
            var sent = 0
            while left > 0 {
                let n = CableLink.chunkLength(left)
                XCTAssert((1...left).contains(n))
                XCTAssertLessThanOrEqual(9 + n, 16_384)
                XCTAssertNotEqual((9 + n) % 64, 0, "total \(total)")
                left -= n
                sent += n
            }
            XCTAssertEqual(sent, total)
        }
    }

    func testPortsMatchTheDesktop() {
        XCTAssertEqual(CableLink.devicePort, 47822, "desktop usbmux.rs DEVICE_PORT")
        XCTAssertEqual(CableLink.localPort, 47821, "same local port as the Android app")
    }
}
