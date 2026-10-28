import CryptoKit
import Foundation
import Network

/// A TCP connection with async exact reads and ordered, back-pressured writes.
final class DirectSocket {
    let conn: NWConnection
    private let queue: DispatchQueue

    init(_ conn: NWConnection, queue: DispatchQueue) {
        self.conn = conn
        self.queue = queue
    }

    /// Starts the connection and waits until it is ready (or fails, or `timeout` passes).
    func start(timeout: TimeInterval) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            let once = Once()
            conn.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready: once.run { cont.resume() }
                case .failed, .cancelled: once.run { cont.resume(throwing: DirectError.closed) }
                case .waiting:
                    // No route (other network, Wi-Fi off): give up instead of waiting forever.
                    once.run { cont.resume(throwing: DirectError.closed) }
                    self?.conn.cancel()
                default: break
                }
            }
            conn.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) { [weak self] in
                once.run { cont.resume(throwing: DirectError.timeout) }
                if self?.conn.state != .ready { self?.conn.cancel() }
            }
        }
    }

    func read(_ n: Int) async throws -> Data {
        if n == 0 { return Data() }
        var out = Data()
        while out.count < n {
            let want = n - out.count
            let chunk: Data = try await withCheckedThrowingContinuation { cont in
                conn.receive(minimumIncompleteLength: 1, maximumLength: want) { data, _, done, error in
                    if let data, !data.isEmpty { cont.resume(returning: data) }
                    else if error != nil || done { cont.resume(throwing: DirectError.closed) }
                    else { cont.resume(returning: Data()) }
                }
            }
            out.append(chunk)
        }
        return out
    }

    func write(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            conn.send(content: data, completion: .contentProcessed { error in
                if error != nil { cont.resume(throwing: DirectError.closed) } else { cont.resume() }
            })
        }
    }

    func close() { conn.cancel() }
}

/// Runs a closure at most once (continuations must be resumed exactly once).
final class Once {
    private let lock = NSLock()
    private var done = false
    func run(_ f: () -> Void) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first { f() }
    }
}

enum DirectHandshake {
    static let magic = Data("SFD1".utf8)
    static let modeQR: UInt8 = 1
    static let modeCable: UInt8 = 2

    static func client(_ s: DirectSocket, key: Data) async throws -> SymmetricKey {
        let clientNonce = DirectCrypto.randomBytes(16)
        try await s.write(magic + Data([modeQR]) + clientNonce)
        let reply = try await s.read(20)
        guard reply.prefix(4) == magic else { throw DirectError.auth }
        return DirectCrypto.sessionKey(key: key, clientNonce: clientNonce, serverNonce: reply.suffix(16))
    }

    static func server(_ s: DirectSocket, key: Data) async throws -> SymmetricKey {
        let head = try await s.read(5)
        // An iPhone has no cable mode: only the QR code's key is accepted.
        guard head.prefix(4) == magic, head[head.startIndex + 4] == modeQR else { throw DirectError.auth }
        let clientNonce = try await s.read(16)
        let serverNonce = DirectCrypto.randomBytes(16)
        try await s.write(magic + serverNonce)
        return DirectCrypto.sessionKey(key: key, clientNonce: clientNonce, serverNonce: serverNonce)
    }
}

struct DirectRecord {
    let type: UInt8
    let body: Data

    func decode<T: Decodable>(_ t: T.Type) throws -> T {
        do { return try JSONDecoder.direct.decode(t, from: body) } catch { throw DirectError.badMessage }
    }
}

/// Encrypted, ordered records (docs/DIRECT.md) with keep-alive.
final class DirectChannel {
    static let chunk = 256 * 1024
    static let maxRecord = 1024 * 1024 + 64
    static let pingEvery: TimeInterval = 10
    static let silenceLimit: TimeInterval = 45

    private let socket: DirectSocket
    private let key: SymmetricKey
    private let sendDir: UInt8
    private let recvDir: UInt8
    private let lock = NSLock()
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0
    private var lastSent = Date()
    /// Silence counts only while a read waits; a slow save on this phone is not a dead link.
    private var waitingSince: Date?
    private var closed = false
    private var pinger: Task<Void, Never>?

    init(socket: DirectSocket, key: SymmetricKey, isClient: Bool) {
        self.socket = socket
        self.key = key
        sendDir = isClient ? UInt8(ascii: "C") : UInt8(ascii: "S")
        recvDir = isClient ? UInt8(ascii: "S") : UInt8(ascii: "C")
    }

    var isOpen: Bool { lock.lock(); defer { lock.unlock() }; return !closed }

    func send<T: Encodable>(_ type: UInt8, _ body: T) async throws {
        try await sendRaw(type, try JSONEncoder.direct.encode(body))
    }

    func sendData(_ data: Data) async throws { try await sendRaw(DirectType.data, data) }

    func sendRaw(_ type: UInt8, _ body: Data) async throws {
        // Counter and enqueue order must match: both happen under the lock.
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            lock.lock()
            if closed {
                lock.unlock()
                cont.resume(throwing: DirectError.closed)
                return
            }
            let counter = sendCounter
            sendCounter += 1
            let sealed: Data
            do {
                sealed = try DirectCrypto.seal(type: type, body: body, key: key, direction: sendDir, counter: counter)
            } catch {
                lock.unlock()
                cont.resume(throwing: DirectError.auth)
                return
            }
            var frame = Data()
            withUnsafeBytes(of: UInt32(sealed.count).bigEndian) { frame.append(contentsOf: $0) }
            frame.append(sealed)
            lastSent = Date()
            socket.conn.send(content: frame, completion: .contentProcessed { [weak self] error in
                if error != nil {
                    self?.close()
                    cont.resume(throwing: DirectError.closed)
                } else {
                    cont.resume()
                }
            })
            lock.unlock()
        }
    }

    /// The next record that is not a keep-alive.
    func receive() async throws -> DirectRecord {
        while true {
            let r = try await receiveAny()
            if r.type != DirectType.ping { return r }
        }
    }

    private func receiveAny() async throws -> DirectRecord {
        setWaiting(Date())
        do {
            let head = try await socket.read(4)
            let len = Int(head.withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }.bigEndian)
            guard len >= 17, len <= Self.maxRecord else { throw DirectError.auth }
            let sealed = try await socket.read(len)
            lock.lock()
            let counter = recvCounter
            recvCounter += 1
            lock.unlock()
            let (type, body) = try DirectCrypto.open(sealed, key: key, direction: recvDir, counter: counter)
            setWaiting(nil)
            return DirectRecord(type: type, body: body)
        } catch {
            close()
            throw error
        }
    }

    private func setWaiting(_ d: Date?) {
        lock.lock()
        waitingSince = d
        lock.unlock()
    }

    func startKeepAlive() {
        pinger = Task.detached { [weak self] in
            while true {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self, self.isOpen else { return }
                self.lock.lock()
                let since = self.waitingSince
                let idle = Date().timeIntervalSince(self.lastSent)
                self.lock.unlock()
                if let since, Date().timeIntervalSince(since) > Self.silenceLimit { self.close(); return }
                if idle >= Self.pingEvery { try? await self.sendRaw(DirectType.ping, Data("{}".utf8)) }
            }
        }
    }

    func close() {
        lock.lock()
        let was = closed
        closed = true
        lock.unlock()
        if was { return }
        pinger?.cancel()
        socket.close()
    }
}

/// A connected, authenticated channel; `isClient` is the end that dialled.
struct DirectLink {
    let channel: DirectChannel
    let isClient: Bool
}

/// Wi-Fi: the receiver listens, the sender connects.
final class DirectWifi {
    static let port: UInt16 = 47830
    private let queue = DispatchQueue(label: "app.switchflow.direct")
    private var listener: NWListener?

    /// Listens until `stop`; links that pass the handshake go to `onLink`. Returns the port.
    func listen(key: Data, onLink: @escaping (DirectLink) -> Void) throws -> UInt16 {
        stop()
        // The usual port first; when another app holds it, any free one (the QR code says which).
        for port in [NWEndpoint.Port(rawValue: Self.port)!, NWEndpoint.Port.any] {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            guard let l = try? NWListener(using: params, on: port) else { continue }
            let ready = DispatchSemaphore(value: 0)
            l.stateUpdateHandler = { state in
                switch state {
                case .ready, .failed, .cancelled: ready.signal()
                default: break
                }
            }
            l.newConnectionHandler = { [queue] conn in
                Task {
                    let s = DirectSocket(conn, queue: queue)
                    do {
                        try await s.start(timeout: 10)
                        // A stranger that connects and says nothing is dropped.
                        let dog = Watchdog(seconds: 10, queue: queue) { s.close() }
                        let k = try await DirectHandshake.server(s, key: key)
                        dog.cancel()
                        onLink(DirectLink(channel: DirectChannel(socket: s, key: k, isClient: false), isClient: false))
                    } catch {
                        s.close()
                    }
                }
            }
            l.start(queue: queue)
            _ = ready.wait(timeout: .now() + 3)
            if let p = l.port?.rawValue, p != 0, l.state == .ready {
                listener = l
                return p
            }
            l.cancel()
        }
        throw DirectError.closed
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    /// Dials every address of the QR code at once; the first that completes the handshake wins.
    func connect(_ t: DirectTarget, timeout: TimeInterval = 5) async -> DirectLink? {
        await withTaskGroup(of: DirectLink?.self) { group in
            for host in t.hosts {
                group.addTask { [queue] in
                    let conn = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: UInt16(t.port))!, using: .tcp)
                    let s = DirectSocket(conn, queue: queue)
                    do {
                        try await s.start(timeout: timeout)
                        let dog = Watchdog(seconds: 10, queue: queue) { s.close() }
                        let k = try await DirectHandshake.client(s, key: t.key)
                        dog.cancel()
                        return DirectLink(channel: DirectChannel(socket: s, key: k, isClient: true), isClient: true)
                    } catch {
                        s.close()
                        return nil
                    }
                }
            }
            var winner: DirectLink?
            for await link in group {
                guard let link else { continue }
                if winner == nil { winner = link } else { link.channel.close() }
            }
            return winner
        }
    }

    /// This iPhone's IPv4 addresses: Wi-Fi first, then its own hotspot. Cellular is left out.
    static func localAddresses() -> [String] {
        var out: [(Int, String)] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = p {
            defer { p = cur.pointee.ifa_next }
            guard let sa = cur.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
            let flags = Int32(cur.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            let name = String(cString: cur.pointee.ifa_name)
            let rank: Int
            if name.hasPrefix("en") { rank = 0 } else if name.hasPrefix("bridge") { rank = 1 } else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: host)
                if !ip.hasPrefix("169.254.") { out.append((rank, ip)) }
            }
        }
        var seen: [String] = []
        for (_, ip) in out.sorted(by: { $0.0 < $1.0 }) where !seen.contains(ip) { seen.append(ip) }
        return Array(seen.prefix(8))
    }
}

/// Closes something that hangs: runs `fire` after `seconds` unless cancelled first.
final class Watchdog {
    private let item: DispatchWorkItem

    init(seconds: TimeInterval, queue: DispatchQueue, fire: @escaping () -> Void) {
        item = DispatchWorkItem(block: fire)
        queue.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    func cancel() { item.cancel() }
}
