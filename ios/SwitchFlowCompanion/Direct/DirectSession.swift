import CryptoKit
import Foundation

/// The other phone after both said HELLO. One reader task feeds records to the current handler.
final class DirectPeer {
    let channel: DirectChannel
    let hello: Hello
    private let lock = NSLock()
    private var _handler: (DirectRecord) async -> Void = { _ in }
    private var _onLost: (Error) -> Void = { _ in }

    init(channel: DirectChannel, hello: Hello) {
        self.channel = channel
        self.hello = hello
    }

    var handler: (DirectRecord) async -> Void {
        get { lock.lock(); defer { lock.unlock() }; return _handler }
        set { lock.lock(); _handler = newValue; lock.unlock() }
    }

    var onLost: (Error) -> Void {
        get { lock.lock(); defer { lock.unlock() }; return _onLost }
        set { lock.lock(); _onLost = newValue; lock.unlock() }
    }

    func startReading() {
        channel.startKeepAlive()
        Task.detached { [self] in
            do {
                while true {
                    // Read first, then look the handler up: it may change while the read waits.
                    let r = try await channel.receive()
                    await handler(r)
                }
            } catch {
                channel.close()
                onLost(error)
            }
        }
    }

    /// HELLO exchange; the side that dialled speaks first.
    static func greet(_ link: DirectLink, mine: Hello) async throws -> DirectPeer {
        if link.isClient { try await link.channel.send(DirectType.hello, mine) }
        let r = try await link.channel.receive()
        guard r.type == DirectType.hello else { throw DirectError.badMessage }
        let theirs = try r.decode(Hello.self)
        if !link.isClient { try await link.channel.send(DirectType.hello, mine) }
        return DirectPeer(channel: link.channel, hello: theirs)
    }
}

/// A one-slot-at-a-time mailbox the sender waits on.
actor Mailbox {
    private var items: [DirectRecord] = []
    private var waiter: CheckedContinuation<DirectRecord, Error>?
    private var failure: Error?

    func put(_ r: DirectRecord) {
        if let w = waiter { waiter = nil; w.resume(returning: r) } else { items.append(r) }
    }

    func fail(_ e: Error) {
        failure = e
        if let w = waiter { waiter = nil; w.resume(throwing: e) }
    }

    func next() async throws -> DirectRecord {
        if !items.isEmpty { return items.removeFirst() }
        if let failure { throw failure }
        return try await withCheckedThrowingContinuation { waiter = $0 }
    }
}

protocol DirectSource {
    /// A readable file for the item, and whether it is a temporary copy to delete afterwards.
    func file(for item: OfferItem) async throws -> (URL, Bool)
}

protocol ItemWriter: AnyObject {
    func write(_ data: Data) throws
    func commit(sha256: String) async throws -> ItemAck
    func abort()
}

protocol DirectSink: AnyObject {
    func plan(_ offer: Offer, done: Set<String>) async -> Accept
    func begin(_ item: OfferItem) throws -> ItemWriter
}

protocol TransferListener: AnyObject {
    func planned(_ all: [OfferItem], skip: Set<String>)
    func bytes(_ n: Int64, _ item: OfferItem)
    func itemDone(_ item: OfferItem, _ ack: ItemAck)
}

/// Sending phone. `run` returns the receiver's FINISH, or throws when the link drops.
final class DirectSender {
    private let peer: DirectPeer
    private let offer: Offer
    private let source: DirectSource
    private weak var listener: TransferListener?
    private let mailbox = Mailbox()
    private let flag = NSLock()
    private var _cancelled = false

    var cancelled: Bool {
        get { flag.lock(); defer { flag.unlock() }; return _cancelled }
        set { flag.lock(); _cancelled = newValue; flag.unlock() }
    }

    init(peer: DirectPeer, offer: Offer, source: DirectSource, listener: TransferListener) {
        self.peer = peer
        self.offer = offer
        self.source = source
        self.listener = listener
    }

    func run() async throws -> Finish {
        let byKey = Dictionary(offer.items.map { ($0.k, $0) }, uniquingKeysWith: { a, _ in a })
        let box = mailbox
        peer.onLost = { e in Task { await box.fail(e) } }
        peer.handler = { [weak self] r in
            switch r.type {
            case DirectType.ack:
                if let ack = try? r.decode(ItemAck.self), let item = byKey[ack.k] { self?.listener?.itemDone(item, ack) }
            case DirectType.accept, DirectType.finish, DirectType.cancel:
                await box.put(r)
            default: break
            }
        }
        try await peer.channel.send(DirectType.offer, offer)
        let accept = try await wait(for: DirectType.accept).decode(Accept.self)
        guard accept.ok else { throw DirectError.refused(accept.error ?? "refused") }
        let skip = Set(accept.skip ?? [])
        listener?.planned(offer.items, skip: skip)
        for item in offer.items where !skip.contains(item.k) {
            try await checkCancelled()
            try await sendItem(item)
        }
        try await peer.channel.sendRaw(DirectType.done, Data("{}".utf8))
        return try await wait(for: DirectType.finish).decode(Finish.self)
    }

    private func checkCancelled() async throws {
        guard cancelled else { return }
        try? await peer.channel.send(DirectType.cancel, CancelMsg(reason: "user"))
        peer.channel.close()
        throw DirectError.cancelled("user")
    }

    private func sendItem(_ item: OfferItem) async throws {
        let ch = peer.channel
        try await ch.send(DirectType.item, ItemStart(k: item.k))
        var hasher = SHA256()
        var size: Int64 = 0
        var error: String?
        do {
            let (url, temporary) = try await source.file(for: item)
            defer { if temporary { try? FileManager.default.removeItem(at: url) } }
            let h = try FileHandle(forReadingFrom: url)
            defer { try? h.close() }
            while true {
                try await checkCancelled()
                let next: Data?
                do {
                    next = try h.read(upToCount: DirectChannel.chunk)
                } catch {
                    throw SourceFailure(code: "source_read_failed")
                }
                guard let chunk = next, !chunk.isEmpty else { break }
                try await ch.sendData(chunk)
                hasher.update(data: chunk)
                size += Int64(chunk.count)
                listener?.bytes(Int64(chunk.count), item)
            }
        } catch let e as SourceFailure {
            error = e.code
        } catch let e as TransferError {
            error = e.code
        } catch let e as DirectError {
            throw e
        } catch {
            // Could not open the original (deleted, iCloud download failed).
            error = "source_read_failed"
        }
        try await ch.send(DirectType.end, ItemEnd(k: item.k, size: size, sha256: Hashing.hex(hasher.finalize()), error: error))
    }

    private func wait(for type: UInt8) async throws -> DirectRecord {
        while true {
            let r = try await mailbox.next()
            if r.type == type { return r }
            if r.type == DirectType.cancel {
                throw DirectError.cancelled((try? r.decode(CancelMsg.self).reason) ?? "peer")
            }
        }
    }
}

struct SourceFailure: Error { let code: String }

/// What the receiving iPhone knows about a session; survives reconnects. Key → saved|duplicate|skipped|failed.
final class ReceiveState {
    let sessionId: String
    private let lock = NSLock()
    private var results: [String: String] = [:]

    init(sessionId: String) { self.sessionId = sessionId }

    func set(_ k: String, _ result: String, onlyIfMissing: Bool = false) {
        lock.lock()
        if !(onlyIfMissing && results[k] != nil) { results[k] = result }
        lock.unlock()
    }

    var done: Set<String> {
        lock.lock(); defer { lock.unlock() }
        return Set(results.filter { $0.value != "failed" }.keys)
    }

    func finish() -> Finish {
        lock.lock(); defer { lock.unlock() }
        return Finish(
            saved: results.values.filter { $0 == "saved" }.count,
            skipped: results.values.filter { $0 == "skipped" || $0 == "duplicate" }.count,
            failed: results.values.filter { $0 == "failed" }.count
        )
    }
}

/// Receiving phone: a record handler run on the peer's reader task, so slow saves hold the sender back.
final class DirectReceiver {
    private let peer: DirectPeer
    private let sink: DirectSink
    private weak var listener: TransferListener?
    private let stateFor: (String) -> ReceiveState
    private let onFinished: (Finish) -> Void
    private let onCancelled: (String) -> Void
    private var state: ReceiveState?
    private var items: [String: OfferItem] = [:]
    private var current: OfferItem?
    private var writer: ItemWriter?
    private var hasher = SHA256()
    private var received: Int64 = 0

    init(peer: DirectPeer, sink: DirectSink, listener: TransferListener, stateFor: @escaping (String) -> ReceiveState,
         onFinished: @escaping (Finish) -> Void, onCancelled: @escaping (String) -> Void) {
        self.peer = peer
        self.sink = sink
        self.listener = listener
        self.stateFor = stateFor
        self.onFinished = onFinished
        self.onCancelled = onCancelled
    }

    func attach() {
        peer.handler = { [weak self] r in await self?.handle(r) }
    }

    func abortCurrent() {
        writer?.abort()
        writer = nil
        current = nil
    }

    private func handle(_ r: DirectRecord) async {
        switch r.type {
        case DirectType.offer:
            guard let offer = try? r.decode(Offer.self) else { return }
            let st = stateFor(offer.sessionId)
            state = st
            items = Dictionary(offer.items.map { ($0.k, $0) }, uniquingKeysWith: { a, _ in a })
            let accept = await sink.plan(offer, done: st.done)
            if accept.ok {
                let skip = Set(accept.skip ?? [])
                skip.forEach { st.set($0, "skipped", onlyIfMissing: true) }
                listener?.planned(offer.items, skip: skip)
            }
            try? await peer.channel.send(DirectType.accept, accept)
        case DirectType.item:
            abortCurrent()
            guard let k = try? r.decode(ItemStart.self).k, let item = items[k] else { return }
            current = item
            received = 0
            hasher = SHA256()
            writer = try? sink.begin(item) // nil: the bytes are read and dropped, END reports it
        case DirectType.data:
            guard let item = current else { return }
            hasher.update(data: r.body)
            received += Int64(r.body.count)
            if let w = writer {
                do { try w.write(r.body) } catch { w.abort(); writer = nil }
            }
            listener?.bytes(Int64(r.body.count), item)
        case DirectType.end:
            guard let end = try? r.decode(ItemEnd.self), let item = current, item.k == end.k else { return }
            let sha = Hashing.hex(hasher.finalize())
            let w = writer
            writer = nil
            current = nil
            var ack: ItemAck
            if let e = end.error {
                w?.abort()
                ack = ItemAck(k: item.k, result: "failed", error: e)
            } else if let w {
                if received != end.size || sha != end.sha256 {
                    w.abort()
                    ack = ItemAck(k: item.k, result: "failed", error: "checksum_mismatch")
                } else {
                    do {
                        ack = try await w.commit(sha256: end.sha256)
                        ack.k = item.k
                    } catch let e as TransferError {
                        w.abort()
                        ack = ItemAck(k: item.k, result: "failed", error: e.code)
                    } catch {
                        w.abort()
                        let ns = error as NSError
                        ack = ItemAck(k: item.k, result: "failed",
                                      error: ns.domain == NSCocoaErrorDomain && ns.code == NSFileWriteOutOfSpaceError ? "insufficient_storage" : "io_error")
                    }
                }
            } else {
                ack = ItemAck(k: item.k, result: "failed", error: "io_error")
            }
            state?.set(item.k, ack.result)
            listener?.itemDone(item, ack)
            try? await peer.channel.send(DirectType.ack, ack)
        case DirectType.done:
            let f = state?.finish() ?? Finish(saved: 0, skipped: 0, failed: 0)
            try? await peer.channel.send(DirectType.finish, f)
            onFinished(f)
        case DirectType.cancel:
            abortCurrent()
            onCancelled((try? r.decode(CancelMsg.self).reason) ?? "peer")
            peer.channel.close()
        default:
            break
        }
    }
}
