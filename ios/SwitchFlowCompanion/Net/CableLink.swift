import Foundation
import Network

/// Transfer over the USB cable, without the QR code.
///
/// While the app is open it listens on the phone's 127.0.0.1:`devicePort` — loopback only, so
/// nothing on Wi-Fi can connect. The SwitchFlow PC reaches that port over the cable through
/// Apple's usbmuxd (Apple Mobile Device Service on Windows). Over that one connection both sides
/// exchange the same frames as with Android accessories (desktop cable.rs): the app's own HTTPS
/// connections to 127.0.0.1:`localPort` become streams to the PC's server, so certificate pinning
/// and the whole protocol work exactly as over Wi-Fi.
/// Frame: kind (1 byte) · stream (4, big-endian) · length (4) · payload.
final class CableLink {
    static let devicePort: UInt16 = 47822
    static let localPort: UInt16 = 47821

    private enum Kind: UInt8 { case hello = 1, open = 2, data = 3, close = 4, ready = 5 }
    private static let header = 9
    private static let maxTransfer = 16_384

    private let queue = DispatchQueue(label: "app.switchflow.cable")
    private var listener: NWListener?
    private var pc: NWConnection?
    private var local: NWListener?
    private var streams: [UInt32: NWConnection] = [:]
    private var nextStream: UInt32 = 1
    private var pending = Data()
    private var onHello: ((String) -> Void)?
    private var onClosed: (() -> Void)?

    /// Starts listening for the PC; `onHello` gets the pairing URI the PC sends over the cable.
    func start(onHello: @escaping (String) -> Void, onClosed: @escaping () -> Void) {
        queue.async {
            self.onHello = onHello
            self.onClosed = onClosed
            self.listen()
        }
    }

    /// Called when the app becomes active again: iOS may have torn the listener down in the background.
    func resume() {
        queue.async { if self.listener == nil { self.listen() } }
    }

    private func listen() {
        guard let l = try? NWListener(using: Self.loopback(Self.devicePort)) else {
            sfLog.error("cable: cannot listen")
            return
        }
        l.newConnectionHandler = { [weak self] conn in self?.accept(pc: conn) }
        l.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.queue.async { self?.listener = nil }
            default: break
            }
        }
        listener = l
        l.start(queue: queue)
    }

    private static func loopback(_ port: UInt16) -> NWParameters {
        let p = NWParameters.tcp
        p.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!)
        p.allowLocalEndpointReuse = true
        return p
    }

    // MARK: PC side

    private func accept(pc conn: NWConnection) {
        // A new connection replaces the old one (the PC app was restarted, or the cable replugged).
        if pc != nil { dropLink(notify: false) }
        pc = conn
        pending = Data()
        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                sfLog.info("cable: PC connected")
                self.startLocal()
                self.send(.ready, 0, Data(#"{"v":1}"#.utf8))
                self.receive(from: conn)
            case .failed, .cancelled:
                if self.pc === conn { self.dropLink(notify: true) }
            default: break
            }
        }
        conn.start(queue: queue)
    }

    private func receive(from conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, done, error in
            guard let self, self.pc === conn else { return }
            if let data, !data.isEmpty {
                self.pending.append(data)
                if !self.drainFrames() { self.dropLink(notify: true); return }
            }
            if done || error != nil { self.dropLink(notify: true); return }
            self.receive(from: conn)
        }
    }

    /// Handles every complete frame in `pending`; false when the stream is corrupt.
    private func drainFrames() -> Bool {
        while pending.count >= Self.header {
            let bytes = [UInt8](pending.prefix(Self.header))
            let stream = UInt32(bytes[1]) << 24 | UInt32(bytes[2]) << 16 | UInt32(bytes[3]) << 8 | UInt32(bytes[4])
            let len = Int(UInt32(bytes[5]) << 24 | UInt32(bytes[6]) << 16 | UInt32(bytes[7]) << 8 | UInt32(bytes[8]))
            guard let kind = Kind(rawValue: bytes[0]), len <= Self.maxTransfer else { return false }
            guard pending.count >= Self.header + len else { return true }
            let payload = pending.subdata(in: pending.startIndex + Self.header ..< pending.startIndex + Self.header + len)
            pending.removeFirst(Self.header + len)
            handle(kind, stream, payload)
        }
        return true
    }

    private func handle(_ kind: Kind, _ stream: UInt32, _ payload: Data) {
        switch kind {
        case .hello:
            let uri = (try? JSONSerialization.jsonObject(with: payload) as? [String: Any])?["uri"] as? String
            if let uri { onHello?(uri) }
        case .data:
            streams[stream]?.send(content: payload, completion: .contentProcessed { [weak self] error in
                if error != nil { self?.closeStream(stream, tellPC: true) }
            })
        case .close:
            closeStream(stream, tellPC: false)
        default:
            break
        }
    }

    // MARK: App side (the HTTPS client connects here)

    private func startLocal() {
        guard local == nil, let l = try? NWListener(using: Self.loopback(Self.localPort)) else { return }
        l.newConnectionHandler = { [weak self] conn in self?.open(local: conn) }
        local = l
        l.start(queue: queue)
    }

    private func open(local conn: NWConnection) {
        let id = nextStream
        nextStream += 1
        streams[id] = conn
        send(.open, id, Data())
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.closeStream(id, tellPC: true)
            default: break
            }
        }
        conn.start(queue: queue)
        pump(id, conn)
    }

    private func pump(_ id: UInt32, _ conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, done, error in
            guard let self, self.streams[id] === conn else { return }
            if let data, !data.isEmpty {
                var offset = 0
                while offset < data.count {
                    let n = Self.chunkLength(data.count - offset)
                    self.send(.data, id, data.subdata(in: data.startIndex + offset ..< data.startIndex + offset + n))
                    offset += n
                }
            }
            if done || error != nil { self.closeStream(id, tellPC: true); return }
            self.pump(id, conn)
        }
    }

    private func closeStream(_ id: UInt32, tellPC: Bool) {
        guard let conn = streams.removeValue(forKey: id) else { return }
        conn.cancel()
        if tellPC { send(.close, id, Data()) }
    }

    // MARK: Frames

    /// Same rule as desktop cable.rs: one frame per transfer, never a multiple of the USB packet size.
    static func chunkLength(_ remaining: Int) -> Int {
        var n = min(remaining, maxTransfer - header)
        if (header + n) % 64 == 0 { n -= 1 }
        return n
    }

    private func send(_ kind: Kind, _ stream: UInt32, _ payload: Data) {
        guard let pc else { return }
        var body = payload
        if kind != .data, (Self.header + body.count) % 64 == 0 { body.append(UInt8(ascii: " ")) }
        var frame = Data([kind.rawValue])
        withUnsafeBytes(of: stream.bigEndian) { frame.append(contentsOf: $0) }
        withUnsafeBytes(of: UInt32(body.count).bigEndian) { frame.append(contentsOf: $0) }
        frame.append(body)
        pc.send(content: frame, completion: .contentProcessed { [weak self] error in
            if error != nil { self?.dropLink(notify: true) }
        })
    }

    private func dropLink(notify: Bool) {
        guard pc != nil else { return }
        pc?.cancel()
        pc = nil
        streams.values.forEach { $0.cancel() }
        streams = [:]
        local?.cancel()
        local = nil
        pending = Data()
        sfLog.info("cable: PC disconnected")
        if notify { onClosed?() }
    }
}
