import Contacts
import CoreImage.CIFilterBuiltins
import EventKit
import Foundation
import Photos
import UIKit

enum DirectStage: Equatable { case idle, waiting, connecting, choosing, transferring, reconnecting, finished, failed }

struct CategoryRow: Identifiable, Equatable {
    var id: String { category }
    let category: String
    var count: Int64
    var bytes: Int64
    var permission: String
    var selected: Bool
}

struct DirectProgress: Equatable {
    var itemsDone = 0
    var itemsTotal = 0
    var bytesDone: Int64 = 0
    var bytesTotal: Int64 = 0
    var current: String?
    var currentCategory: String?
    var failed = 0
    var bytesPerSec: Int64 = 0
}

/// Phone-to-phone on the iPhone: Wi-Fi + QR in both directions (the cable is Android-only).
@MainActor
final class DirectController: ObservableObject, TransferListener {
    @Published var stage: DirectStage = .idle
    @Published var role: String?
    @Published var qr: UIImage?
    @Published var peer: PeerDevice?
    @Published var categories: [CategoryRow] = []
    @Published var scanning = false
    @Published var needsAccess = false
    @Published var progress = DirectProgress()
    @Published var result: Finish?
    @Published var error: String?
    /// Categories the receiving side is about to ask iOS for; the card explains why first.
    @Published var askingAccess: [String]?
    @Published var askingDocuments = false
    /** The photo/video grid is open for this category. */
    @Published var picking: String?
    /// Hand-picked items per category (asset ids); a category missing here sends everything.
    @Published var picked: [String: Set<String>] = [:]
    @Published var pickedBytes: [String: Int64] = [:]

    private let wifi = DirectWifi()
    private let catalog = SourceCatalog()
    private var activePeer: DirectPeer?
    private var target: DirectTarget?
    private var sender: DirectSender?
    private var sendTask: Task<Void, Never>?
    private var receiver: DirectReceiver?
    private let receiveStates = ReceiveStates()
    private var inventories: [String: CategoryInventory] = [:]
    private var accessContinuation: CheckedContinuation<Void, Never>?

    static let readable = [Category.photos, Category.videos, Category.contacts, Category.calendars]

    private func me(_ intent: String) -> Hello {
        Hello(device: PeerDevice(
            id: Store.deviceId, name: UIDevice.current.name, model: DeviceEnv.modelName(), platform: "ios",
            os: UIDevice.current.systemVersion,
            app: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
        ), intent: intent)
    }

    // MARK: Receive

    func startReceive() {
        reset()
        role = DirectIntent.receive
        let key = DirectCrypto.randomBytes(DirectCrypto.keyBytes)
        let hosts = DirectWifi.localAddresses()
        do {
            let port = try wifi.listen(key: key) { [weak self] link in
                Task { @MainActor in await self?.onLink(link) }
            }
            if hosts.isEmpty {
                error = "no_network"
                qr = nil
            } else {
                qr = Self.qrImage(DirectQR.build(DirectTarget(hosts: hosts, port: Int(port), key: key, name: UIDevice.current.name, platform: "ios")))
                error = nil
            }
            stage = .waiting
            UIApplication.shared.isIdleTimerDisabled = true
        } catch {
            fail("listen_failed")
        }
    }

    private func becomeReceiver(_ p: DirectPeer) {
        let sink = PhoneSink { [weak self] cats in await self?.askWrite(cats) }
        let r = DirectReceiver(
            peer: p, sink: sink, listener: self,
            stateFor: { [states = receiveStates] sid in states.get(sid) },
            onFinished: { [weak self] f in Task { @MainActor in self?.receiveFinished(f) } },
            onCancelled: { [weak self] _ in Task { @MainActor in self?.fail("peer_cancelled") } }
        )
        receiver = r
        r.attach()
        p.onLost = { [weak self, weak p] _ in Task { @MainActor in if let p { self?.receiverLost(p) } } }
        role = DirectIntent.receive
        peer = p.hello.device
        stage = .choosing
    }

    /// The sender redials by itself over Wi-Fi for two minutes.
    private func receiverLost(_ p: DirectPeer) {
        guard activePeer === p else { return }
        receiver?.abortCurrent()
        if stage == .transferring {
            stage = .reconnecting
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 120_000_000_000)
                if self.activePeer === p, self.stage == .reconnecting { self.fail("lost") }
            }
        } else if stage == .choosing {
            activePeer = nil
            peer = nil
            stage = .waiting
        }
    }

    private func receiveFinished(_ f: Finish) {
        wifi.stop()
        result = f
        progress.current = nil
        stage = .finished
        UIApplication.shared.isIdleTimerDisabled = false
    }

    // MARK: Send

    func startSend() {
        reset()
        role = DirectIntent.send
        stage = .waiting
    }

    func connect(_ raw: String) {
        guard let t = DirectQR.parse(raw) else {
            role = DirectIntent.send
            fail("bad_qr")
            return
        }
        reset()
        role = DirectIntent.send
        target = t
        stage = .connecting
        Task {
            guard let link = await wifi.connect(t) else { fail("unreachable"); return }
            await onLink(link)
        }
    }

    private func becomeSender(_ p: DirectPeer) {
        p.onLost = { [weak self, weak p] _ in
            Task { @MainActor in if let self, let p, self.activePeer === p, self.stage == .choosing { self.fail("lost") } }
        }
        p.handler = { [weak self, weak p] r in
            guard r.type == DirectType.cancel, let p else { return }
            let reason = (try? r.decode(CancelMsg.self).reason) ?? ""
            await MainActor.run { if self?.activePeer === p { self?.fail(reason == "busy" ? "busy" : reason == "same_direction" ? "both_send" : "peer_cancelled") } }
        }
        role = DirectIntent.send
        peer = p.hello.device
        stage = .choosing
        Task { await scanAll() }
    }

    private func scanAll() async {
        scanning = true
        var states: [String: String] = [:]
        states[Category.photos] = PhotoLibrary.currentState
        states[Category.videos] = PhotoLibrary.currentState
        states[Category.contacts] = ContactsService.state
        states[Category.calendars] = CalendarService.state
        needsAccess = Self.readable.contains { states[$0] == PermissionState.denied }
        var inv: [String: CategoryInventory] = [:]
        for c in Self.readable {
            inv[c] = (try? await catalog.scan(c, permission: states[c] ?? PermissionState.denied))
                ?? CategoryInventory(category: c, permission: PermissionState.unavailable, count: 0, totalBytes: 0)
        }
        inv[Category.documents] = (try? await catalog.scan(Category.documents, permission: PermissionState.granted))
            ?? CategoryInventory(category: Category.documents, permission: PermissionState.granted, count: 0, totalBytes: 0)
        inventories = inv
        let prev = Dictionary(categories.map { ($0.category, $0.selected) }, uniquingKeysWith: { a, _ in a })
        categories = [Category.contacts, Category.calendars, Category.photos, Category.videos, Category.documents].compactMap { c in
            guard let i = inv[c] else { return nil }
            return CategoryRow(category: c, count: i.count, bytes: i.totalBytes, permission: i.permission,
                               selected: prev[c] ?? (i.count > 0 && c != Category.documents))
        }
        scanning = false
    }

    /// "Allow access": iOS asks per category, then everything is counted again.
    func requestReadAccess() {
        Task {
            _ = await PhotoLibrary.authorize()
            _ = await ContactsService.authorize()
            _ = await CalendarService.authorize()
            await scanAll()
        }
    }

    func toggle(_ c: String) {
        if c == Category.documents { askingDocuments = true; return }
        guard let i = categories.firstIndex(where: { $0.category == c }), categories[i].count > 0 else { return }
        categories[i].selected.toggle()
    }

    func documentsPicked(_ urls: [URL]) {
        askingDocuments = false
        guard !urls.isEmpty else { return }
        Task {
            let old = await catalog.pickedDocuments
            await catalog.setPicked(old + urls)
            if let docs = try? await catalog.scan(Category.documents, permission: PermissionState.granted),
               let i = categories.firstIndex(where: { $0.category == Category.documents }) {
                inventories[Category.documents] = docs
                categories[i].count = docs.count
                categories[i].bytes = docs.totalBytes
                categories[i].selected = docs.count > 0
            }
        }
    }

    func chosenCount(_ row: CategoryRow) -> Int64 { picked[row.category].map { Int64($0.count) } ?? row.count }
    func chosenBytes(_ row: CategoryRow) -> Int64 { pickedBytes[row.category] ?? row.bytes }
    var selectedBytes: Int64 { categories.filter(\.selected).reduce(0) { $0 + chosenBytes($1) } }
    var selectedCount: Int64 { categories.filter(\.selected).reduce(0) { $0 + chosenCount($1) } }

    // MARK: Photo/video grid

    func media(_ category: String) -> [SourceItem] { inventories[category]?.items ?? [] }

    func albums(_ category: String) -> [Album] { inventories[category]?.albums ?? [] }

    /// Grid closed with this choice; choosing everything is the same as no choice.
    func setPicked(_ category: String, _ ids: Set<String>) {
        let items = media(category)
        if ids.count == items.count {
            picked[category] = nil
            pickedBytes[category] = nil
        } else {
            picked[category] = ids
            pickedBytes[category] = items.filter { ids.contains($0.id) }.reduce(0) { $0 + $1.size }
        }
        if let i = categories.firstIndex(where: { $0.category == category }) { categories[i].selected = !ids.isEmpty }
        picking = nil
    }

    func send() {
        guard let p = activePeer else { fail("lost"); return }
        var items: [OfferItem] = []
        var sources: [String: String] = [:]
        var n = 0
        for row in categories where row.selected {
            guard let inv = inventories[row.category] else { continue }
            let albums = Dictionary(inv.albums.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
            let only = picked[row.category]
            for it in inv.items where only?.contains(it.id) ?? true {
                n += 1
                let k = String(n)
                items.append(OfferItem(k: k, c: it.category, n: it.name, s: it.size, m: it.mime.isEmpty ? nil : it.mime,
                                       t: it.createdMs, a: it.albumIds.first.flatMap { albums[$0] }, cnt: it.itemCount, app: nil))
                sources[k] = it.id
            }
        }
        guard !items.isEmpty else { return }
        let offer = Offer(sessionId: UUID().uuidString, items: items)
        let source = CatalogSource(catalog: catalog, ids: sources)
        startTransferUI()
        sendTask = Task { await runSend(p, offer, source) }
    }

    private func runSend(_ first: DirectPeer, _ offer: Offer, _ source: DirectSource) async {
        var p = first
        while true {
            let s = DirectSender(peer: p, offer: offer, source: source, listener: self)
            sender = s
            do {
                let f = try await s.run()
                result = f
                progress.current = nil
                stage = .finished
                p.channel.close()
                await catalog.clear()
                UIApplication.shared.isIdleTimerDisabled = false
                return
            } catch DirectError.cancelled(let reason) {
                fail(reason == "user" ? "cancelled" : "peer_cancelled"); return
            } catch DirectError.refused(let code) {
                fail(code); return
            } catch {
                if s.cancelled { fail("cancelled"); return }
                stage = .reconnecting
                guard let again = await reconnect() else { fail("lost"); return }
                p = again
                activePeer = again
                stage = .transferring
            }
        }
    }

    private func reconnect() async -> DirectPeer? {
        guard let t = target else { return nil }
        let deadline = Date().addingTimeInterval(120)
        while Date() < deadline {
            if sender?.cancelled == true { return nil }
            if let link = await wifi.connect(t, timeout: 3) {
                if let p = try? await DirectPeer.greet(link, mine: me(DirectIntent.send)) {
                    p.startReading()
                    return p
                }
                link.channel.close()
            }
            try? await Task.sleep(nanoseconds: 2_000_000_000)
        }
        return nil
    }

    func cancel() {
        sender?.cancelled = true
        if role == DirectIntent.receive, let p = activePeer {
            Task { try? await p.channel.send(DirectType.cancel, CancelMsg(reason: "user")); p.channel.close() }
            fail("cancelled")
        }
    }

    // MARK: Links

    private func onLink(_ link: DirectLink) async {
        let mine = role == DirectIntent.send ? DirectIntent.send : DirectIntent.receive
        let p: DirectPeer
        do {
            p = try await DirectPeer.greet(link, mine: me(mine))
        } catch {
            link.channel.close()
            if link.isClient, stage == .connecting { fail("old_qr") }
            return
        }
        // The sender came back after a dropped link.
        if stage == .reconnecting, role == DirectIntent.receive, p.hello.device.id == peer?.id {
            activePeer = p
            becomeReceiver(p)
            stage = .transferring
            p.startReading()
            return
        }
        let open: [DirectStage] = [.waiting, .connecting, .idle, .finished, .failed]
        if activePeer != nil, !open.contains(stage) {
            try? await link.channel.send(DirectType.cancel, CancelMsg(reason: "busy"))
            link.channel.close()
            return
        }
        activePeer?.channel.close()
        activePeer = p
        let resolved = DirectIntent.resolve(mine: mine, theirs: p.hello.intent)
        if resolved == DirectIntent.send {
            becomeSender(p)
        } else if resolved == DirectIntent.receive {
            becomeReceiver(p)
        } else {
            try? await link.channel.send(DirectType.cancel, CancelMsg(reason: "same_direction"))
            link.channel.close()
            activePeer = nil
            fail(mine == DirectIntent.send ? "both_send" : "both_receive")
            return
        }
        p.startReading()
    }

    // MARK: Progress (TransferListener; called from transfer tasks)

    private var sizes: [String: Int64] = [:]
    private var doneKeys: Set<String> = []
    private var failedKeys: Set<String> = []
    private var currentKey: String?
    private var currentBytes: Int64 = 0
    private var speedMark = (Date(), Int64(0))

    private func startTransferUI() {
        sizes = [:]
        doneKeys = []
        failedKeys = []
        currentKey = nil
        currentBytes = 0
        speedMark = (Date(), 0)
        progress = DirectProgress()
        result = nil
        error = nil
        stage = .transferring
        UIApplication.shared.isIdleTimerDisabled = true
    }

    nonisolated func planned(_ all: [OfferItem], skip: Set<String>) {
        Task { @MainActor in
            if self.role == DirectIntent.receive, self.stage != .transferring { self.startTransferUI() }
            self.sizes = Dictionary(all.map { ($0.k, $0.s) }, uniquingKeysWith: { a, _ in a })
            self.doneKeys.formUnion(skip)
            self.publish(nil, nil)
        }
    }

    nonisolated func bytes(_ n: Int64, _ item: OfferItem) {
        Task { @MainActor in
            if self.currentKey != item.k { self.currentKey = item.k; self.currentBytes = 0 }
            self.currentBytes += n
            self.publish(item.n, item.c)
        }
    }

    nonisolated func itemDone(_ item: OfferItem, _ ack: ItemAck) {
        Task { @MainActor in
            self.doneKeys.insert(item.k)
            if ack.result == "failed" { self.failedKeys.insert(item.k) } else { self.failedKeys.remove(item.k) }
            if self.currentKey == item.k { self.currentKey = nil; self.currentBytes = 0 }
            self.publish(self.progress.current, self.progress.currentCategory)
        }
    }

    private func publish(_ name: String?, _ category: String?) {
        let done = sizes.filter { doneKeys.contains($0.key) }.values.reduce(0, +) + currentBytes
        var speed = progress.bytesPerSec
        let elapsed = Date().timeIntervalSince(speedMark.0)
        if elapsed >= 1.5 {
            let inst = Int64(Double(done - speedMark.1) / elapsed)
            speed = speed == 0 ? inst : (speed * 2 + inst) / 3
            speedMark = (Date(), done)
        }
        progress = DirectProgress(
            itemsDone: min(doneKeys.count, sizes.count), itemsTotal: sizes.count,
            bytesDone: done, bytesTotal: sizes.values.reduce(0, +),
            current: name, currentCategory: category, failed: failedKeys.count, bytesPerSec: max(0, speed)
        )
    }

    // MARK: Permissions (receiving side)

    private func askWrite(_ categories: [String]) async {
        let cats = categories.filter { [Category.photos, Category.videos, Category.contacts, Category.calendars].contains($0) }
        let undecided = cats.contains { c in
            switch c {
            case Category.photos, Category.videos: return PHPhotoLibrary.authorizationStatus(for: .readWrite) == .notDetermined
            case Category.contacts: return CNContactStore.authorizationStatus(for: .contacts) == .notDetermined
            default: return EKEventStore.authorizationStatus(for: .event) == .notDetermined
            }
        }
        guard undecided else { return }
        askingAccess = cats
        await withCheckedContinuation { accessContinuation = $0 }
        askingAccess = nil
        for c in Set(cats) {
            switch c {
            case Category.photos, Category.videos: _ = await PhotoLibrary.authorize()
            case Category.contacts: _ = await ContactsService.authorize()
            default: _ = await CalendarService.authorize()
            }
        }
    }

    func allowAccessTapped() {
        accessContinuation?.resume()
        accessContinuation = nil
    }

    // MARK: Ending

    private func fail(_ code: String) {
        activePeer?.channel.close()
        wifi.stop()
        error = code
        qr = nil
        progress.current = nil
        stage = .failed
        UIApplication.shared.isIdleTimerDisabled = false
    }

    func leave() {
        reset()
        stage = .idle
        role = nil
    }

    private func reset() {
        sender?.cancelled = true
        sender = nil
        sendTask?.cancel()
        sendTask = nil
        receiver?.abortCurrent()
        receiver = nil
        activePeer?.channel.close()
        activePeer = nil
        target = nil
        wifi.stop()
        accessContinuation?.resume()
        accessContinuation = nil
        askingAccess = nil
        peer = nil
        qr = nil
        error = nil
        result = nil
        categories = []
        inventories = [:]
        picking = nil
        picked = [:]
        pickedBytes = [:]
        Task { await catalog.clear() }
        UIApplication.shared.isIdleTimerDisabled = false
    }

    static func qrImage(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Session states by id, shared between the main actor and the reader task.
final class ReceiveStates {
    private let lock = NSLock()
    private var all: [String: ReceiveState] = [:]

    func get(_ sid: String) -> ReceiveState {
        lock.lock(); defer { lock.unlock() }
        if let s = all[sid] { return s }
        let s = ReceiveState(sessionId: sid)
        all[sid] = s
        return s
    }
}

/// Reads originals through the catalog (photos are exported to a temporary file first).
struct CatalogSource: DirectSource {
    let catalog: SourceCatalog
    let ids: [String: String]

    func file(for item: OfferItem) async throws -> (URL, Bool) {
        guard let id = ids[item.k] else { throw TransferError.notFound }
        return try await catalog.file(for: id)
    }
}

/// Saving on the iPhone: Photos for photos and videos, Contacts, Calendar, and Files for the rest.
final class PhoneSink: DirectSink {
    private let ask: ([String]) async -> Void

    init(ask: @escaping ([String]) async -> Void) { self.ask = ask }

    static func receivedKey(_ i: OfferItem) -> String { "direct|\(i.c)|\(i.n)|\(i.s)" }

    func plan(_ offer: Offer, done: Set<String>) async -> Accept {
        await ask(Array(Set(offer.items.map(\.c))))
        // One read of the list instead of one per file: an offer can hold thousands of photos.
        let received = Set(UserDefaults.standard.stringArray(forKey: "received") ?? [])
        var skip: [String] = []
        for i in offer.items where done.contains(i.k) || i.c == "apps" || received.contains(Self.receivedKey(i)) {
            skip.append(i.k)
        }
        let skipSet = Set(skip)
        let need = offer.items.filter { !skipSet.contains($0.k) }.reduce(Int64(0)) { $0 + $1.s }
        let free = DeviceEnv.storage().free
        if need > 0, free - need < 200 * 1024 * 1024 {
            return Accept(ok: false, skip: skip, freeBytes: free, error: "insufficient_storage")
        }
        return Accept(ok: true, skip: skip, freeBytes: free, error: nil)
    }

    func begin(_ item: OfferItem) throws -> ItemWriter { try TempWriter(item) }

    final class TempWriter: ItemWriter {
        let item: OfferItem
        let url: URL
        private let handle: FileHandle

        init(_ item: OfferItem) throws {
            self.item = item
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sf-direct", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            url = dir.appendingPathComponent(UUID().uuidString + "-" + FilesService.safeName(item.n))
            FileManager.default.createFile(atPath: url.path, contents: nil)
            handle = try FileHandle(forWritingTo: url)
        }

        func write(_ data: Data) throws { try handle.write(contentsOf: data) }

        func commit(sha256: String) async throws -> ItemAck {
            try handle.synchronize()
            try handle.close()
            defer { try? FileManager.default.removeItem(at: url) }
            let ack = try await DirectSave.save(item, file: url, sha: sha256)
            if ack.result == "saved" { Store.markReceived(PhoneSink.receivedKey(item)) }
            return ack
        }

        func abort() {
            try? handle.close()
            try? FileManager.default.removeItem(at: url)
        }
    }
}

/// Same rules as the PC transfer (Transfer.swift): Photos first, Files when Photos refuses the format.
enum DirectSave {
    static func save(_ item: OfferItem, file: URL, sha: String) async throws -> ItemAck {
        switch item.c {
        case Category.photos, Category.videos:
            do {
                try await PhotoLibrary.save(fileAt: file, name: item.n, isVideo: item.c == Category.videos,
                                            created: item.t.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) },
                                            albumName: item.a)
                return ItemAck(k: item.k, result: "saved")
            } catch {
                let copy = file.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
                try FileManager.default.copyItem(at: file, to: copy)
                let (_, stored) = try FilesService.store(tempFile: copy, category: item.c, name: item.n)
                return ItemAck(k: item.k, result: stored == sha ? "saved" : "failed", error: stored == sha ? nil : "checksum_mismatch")
            }
        case Category.contacts:
            _ = try ContactsService.importVCards(try Data(contentsOf: file), policy: "merge")
            return ItemAck(k: item.k, result: "saved")
        case Category.calendars:
            let export = try JSONDecoder().decode(CalendarExport.self, from: Data(contentsOf: file))
            _ = try CalendarService.importEvents(export.events)
            return ItemAck(k: item.k, result: "saved")
        default:
            let copy = file.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
            try FileManager.default.copyItem(at: file, to: copy)
            let (_, stored) = try FilesService.store(tempFile: copy, category: item.c, name: item.n)
            return ItemAck(k: item.k, result: stored == sha ? "saved" : "failed", error: stored == sha ? nil : "checksum_mismatch")
        }
    }
}
