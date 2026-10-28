import CryptoKit
import XCTest
@testable import SwitchFlowCompanion

final class DirectTests: XCTestCase {
    private func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    private func unhex(_ s: String) -> Data {
        var d = Data()
        var i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            d.append(UInt8(s[i..<j], radix: 16)!)
            i = j
        }
        return d
    }

    func testHkdfMatchesRfc5869() {
        let k = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: Data(repeating: 0x0b, count: 22)),
                                       salt: unhex("000102030405060708090a0b0c"), info: unhex("f0f1f2f3f4f5f6f7f8f9"), outputByteCount: 42)
        XCTAssertEqual(k.withUnsafeBytes { hex(Data($0)) }, "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    }

    /// Same numbers as the Android test (DirectTest.kt): both apps must agree byte for byte.
    func testCrossPlatformVector() throws {
        let key = Data((0..<32).map { UInt8($0) })
        let session = DirectCrypto.sessionKey(key: key, clientNonce: Data(repeating: 0x10, count: 16), serverNonce: Data(repeating: 0x20, count: 16))
        XCTAssertEqual(session.withUnsafeBytes { hex(Data($0)) }, "15acf1cf04e20b6f0763851f3003cb3b4f2c81f1b255d36e833a62526df99966")
        let sealed = try DirectCrypto.seal(type: DirectType.hello, body: Data("{}".utf8), key: session, direction: UInt8(ascii: "C"), counter: 0)
        XCTAssertEqual(hex(sealed), "2ffeeb7e78c8305d5d82517ee1cab4e8215145")
        let (type, body) = try DirectCrypto.open(sealed, key: session, direction: UInt8(ascii: "C"), counter: 0)
        XCTAssertEqual(type, DirectType.hello)
        XCTAssertEqual(body, Data("{}".utf8))
        XCTAssertThrowsError(try DirectCrypto.open(sealed, key: session, direction: UInt8(ascii: "C"), counter: 1))
    }

    func testQrRoundTripAndAndroidCode() {
        let t = DirectTarget(hosts: ["192.168.1.5", "172.20.10.1"], port: 47830, key: Data((0..<32).map { UInt8($0 * 7 % 256) }), name: "Daulet's iPhone & Co", platform: "ios")
        let raw = DirectQR.build(t)
        XCTAssertTrue(DirectQR.isDirect(raw))
        XCTAssertEqual(DirectQR.parse(raw), t)
        // What the Android app builds (URLEncoder: spaces as %20 after replacing "+").
        let android = "switchflow://direct?v=1&h=192.168.0.7&p=47830&k=AAcOFRwjKjE4P0ZNVFtiaXB3foWMk5qhqK-2vcTL0tk&n=Galaxy%20A10&o=android"
        let a = DirectQR.parse(android)
        XCTAssertEqual(a?.name, "Galaxy A10")
        XCTAssertEqual(a?.key.count, 32)
        XCTAssertNil(DirectQR.parse("switchflow://pair?v=1&hosts=1.2.3.4&port=1&fp=00&s=abc"))
        XCTAssertNil(DirectQR.parse(raw.replacingOccurrences(of: "k=", with: "k=x")))
    }

    func testDirectionsResolve() {
        XCTAssertEqual(DirectIntent.resolve(mine: DirectIntent.send, theirs: DirectIntent.receive), DirectIntent.send)
        XCTAssertEqual(DirectIntent.resolve(mine: DirectIntent.receive, theirs: DirectIntent.any), DirectIntent.receive)
        XCTAssertNil(DirectIntent.resolve(mine: DirectIntent.send, theirs: DirectIntent.send))
    }

    // MARK: Whole transfer over loopback TCP

    final class MemorySink: DirectSink {
        var saved: [String: Data] = [:]
        func plan(_ offer: Offer, done: Set<String>) async -> Accept {
            Accept(ok: true, skip: offer.items.map(\.k).filter { done.contains($0) }, freeBytes: 1 << 40, error: nil)
        }
        func begin(_ item: OfferItem) throws -> ItemWriter { Writer(item, self) }

        final class Writer: ItemWriter {
            let item: OfferItem
            weak var sink: MemorySink?
            var buf = Data()
            init(_ item: OfferItem, _ sink: MemorySink) { self.item = item; self.sink = sink }
            func write(_ data: Data) throws { buf.append(data) }
            func commit(sha256: String) async throws -> ItemAck { sink?.saved[item.n] = buf; return ItemAck(k: item.k, result: "saved") }
            func abort() {}
        }
    }

    final class Quiet: TransferListener {
        func planned(_ all: [OfferItem], skip: Set<String>) {}
        func bytes(_ n: Int64, _ item: OfferItem) {}
        func itemDone(_ item: OfferItem, _ ack: ItemAck) {}
    }

    struct FileSource: DirectSource {
        let files: [String: URL]
        func file(for item: OfferItem) async throws -> (URL, Bool) {
            guard let u = files[item.n] else { throw SourceFailure(code: "not_found") }
            return (u, false)
        }
    }

    func testWholeTransferOverLoopback() async throws {
        let key = DirectCrypto.randomBytes(32)
        let wifi = DirectWifi()
        let got = expectation(description: "link")
        var serverLink: DirectLink?
        let port = try wifi.listen(key: key) { link in serverLink = link; got.fulfill() }
        defer { wifi.stop() }
        let client = await wifi.connect(DirectTarget(hosts: ["127.0.0.1"], port: Int(port), key: key, name: "t", platform: "ios"))
        await fulfillment(of: [got], timeout: 5)
        let c = try XCTUnwrap(client)
        let s = try XCTUnwrap(serverLink)
        let device = PeerDevice(id: "x", name: "x", model: "x", platform: "ios", os: "17", app: "0.1")
        async let rxPeer = DirectPeer.greet(s, mine: Hello(device: device, intent: DirectIntent.receive))
        let txPeer = try await DirectPeer.greet(c, mine: Hello(device: device, intent: DirectIntent.send))
        let rx = try await rxPeer

        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var files: [String: URL] = [:]
        var contents: [String: Data] = [:]
        for (name, size) in [("a.jpg", 700_000), ("b.mov", 3 * DirectChannel.chunk + 17), ("empty.txt", 0)] {
            let d = Data((0..<size).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ 7) })
            let u = dir.appendingPathComponent(name)
            try d.write(to: u)
            files[name] = u
            contents[name] = d
        }
        let items = ["a.jpg", "b.mov", "empty.txt", "gone.jpg"].enumerated().map { i, n in
            OfferItem(k: String(i + 1), c: Category.photos, n: n, s: Int64(contents[n]?.count ?? 10))
        }
        let sink = MemorySink()
        let quiet = Quiet()
        let finished = expectation(description: "finish")
        let receiver = DirectReceiver(peer: rx, sink: sink, listener: quiet, stateFor: { ReceiveState(sessionId: $0) },
                                      onFinished: { _ in finished.fulfill() }, onCancelled: { _ in })
        receiver.attach()
        rx.startReading()
        txPeer.startReading()
        let f = try await DirectSender(peer: txPeer, offer: Offer(sessionId: "s", items: items), source: FileSource(files: files), listener: quiet).run()
        await fulfillment(of: [finished], timeout: 5)
        XCTAssertEqual(f, Finish(saved: 3, skipped: 0, failed: 1))
        for (n, d) in contents { XCTAssertEqual(sink.saved[n], d, n) }
        txPeer.channel.close()
    }
}
