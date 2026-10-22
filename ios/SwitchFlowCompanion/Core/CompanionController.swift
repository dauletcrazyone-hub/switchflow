import Contacts
import EventKit
import Foundation
import Photos
import SwiftUI
import UIKit
import os

enum Phase { case idle, connecting, awaitingApproval, connected, reconnecting }

enum UserMessage: String { case badQR, unreachable, denied, expired, forgotten }

struct TransferUI {
    var sessionId: String
    var sending: Bool
    var itemsDone = 0
    var itemsTotal = 0
    var bytes: Int64 = 0
    var currentName: String?
    var paused = false
    var finished: String?
}

/// The companion's brain: pairing, heartbeat, the PC's command queue and the session workers.
/// Diagnostics without personal data: no file names, contact fields or tokens are logged.
let sfLog = Logger(subsystem: "app.switchflow.companion", category: "companion")

@MainActor
final class CompanionController: ObservableObject {
    @Published var phase: Phase = .idle
    @Published var desktopName: String?
    @Published var verificationCode: String?
    @Published var role = "none"
    @Published var message: UserMessage?
    @Published var transfer: TransferUI?
    @Published var askingAccess: [String]?   // categories the PC asked for
    @Published var askingDocuments = false
    @Published var viaCable = false

    var foreground = true
    private var api: APIClient?
    private var loops: [Task<Void, Never>] = []
    private var sessionTasks: [String: Task<Void, Never>] = [:]
    private var queued: [String: [UploadEntry]] = [:]
    private var names: [String: String] = [:]
    private let catalog = SourceCatalog()
    private let controls = SessionControls()
    private var accessContinuation: CheckedContinuation<Void, Never>?
    /// Scans and prepares run one after another so one permission card never replaces another.
    private var permissionChain: Task<Void, Never>?

    /// Queues work that may ask the user for permission behind any earlier such work.
    private func serially(_ work: @escaping @MainActor () async -> Void) {
        let previous = permissionChain
        permissionChain = Task { @MainActor in
            await previous?.value
            await work()
        }
    }
    private var documentsContinuation: CheckedContinuation<[URL], Never>?

    func start() {
        startCable()
        #if DEBUG
        // Test hook for the cloud end-to-end test: the simulator has no camera, so the QR content
        // arrives as a launch argument (-SFPairingURI "switchflow://pair?..."). Release builds ignore it.
        if let uri = UserDefaults.standard.string(forKey: "SFPairingURI"), !uri.isEmpty {
            onScanned(uri)
            return
        }
        #endif
        if let saved = Store.pairing { connect(saved) }
    }

    /// True only when iOS would actually show a permission dialog; no extra tap when access is already decided.
    private func needsPrompt(_ cats: [String]) -> Bool {
        cats.contains { cat in
            switch cat {
            case Category.photos, Category.videos: return PHPhotoLibrary.authorizationStatus(for: .readWrite) == .notDetermined
            case Category.contacts: return CNContactStore.authorizationStatus(for: .contacts) == .notDetermined
            case Category.calendars: return EKEventStore.authorizationStatus(for: .event) == .notDetermined
            default: return false
            }
        }
    }

    // MARK: Pairing

    func onScanned(_ raw: String) {
        guard let target = PairingURI.parse(raw) else {
            sfLog.error("pairing code rejected")
            message = .badQR
            return
        }
        sfLog.info("pairing code accepted, \(target.hosts.count) addresses")
        Task { await pair(target) }
    }

    private func reachable(_ t: PairingTarget) async -> (APIClient, String)? {
        let found: [String] = await withTaskGroup(of: String?.self) { group in
            for h in t.hosts { group.addTask { await APIClient(host: h, port: t.port, fingerprint: t.fingerprint).ping() ? h : nil } }
            var ok: [String] = []
            for await h in group { if let h { ok.append(h) } }
            return ok
        }
        // Keep the QR's priority order among the addresses that answered.
        guard let host = t.hosts.first(where: found.contains) else { return nil }
        return (APIClient(host: host, port: t.port, fingerprint: t.fingerprint), host)
    }

    private func pair(_ t: PairingTarget) async {
        message = nil
        phase = .connecting
        desktopName = t.desktopName
        guard let found = await reachable(t) else { phase = .idle; message = .unreachable; return }
        let (client, host) = found
        do {
            let resp = try await client.pair(PairRequest(secret: t.secret, device: DeviceEnv.info()))
            verificationCode = resp.verificationCode
            phase = .awaitingApproval
            for _ in 0..<150 {
                try await Task.sleep(nanoseconds: 1_000_000_000)
                let s = try await client.pairStatus(resp.pairingId)
                switch s.status {
                case "approved":
                    sfLog.info("pairing approved")
                    guard let token = s.token else { continue }
                    let saved = SavedPairing(host: host, port: t.port, fingerprint: t.fingerprint, token: token, desktopName: s.desktopName ?? t.desktopName)
                    Store.pairing = saved
                    Store.lastSeq = 0
                    connect(saved)
                    return
                case "denied": phase = .idle; message = .denied; return
                case "expired": phase = .idle; message = .expired; return
                default: continue
                }
            }
            phase = .idle; message = .expired
        } catch let e as APIError {
            phase = .idle; message = e.status == 403 ? .badQR : .unreachable
        } catch {
            phase = .idle; message = .unreachable
        }
    }

    // MARK: Cable

    private let cable = CableLink()
    /// Where the PC is reachable without the cable (from its HELLO), to fall back on unplug.
    private var cableHosts: [String] = []
    private var cableServerPort = 0
    private static let loopback = "127.0.0.1"

    private func startCable() {
        cable.start(
            onHello: { [weak self] uri in Task { @MainActor in await self?.onCableHello(uri) } },
            onClosed: { [weak self] in Task { @MainActor in await self?.onCableClosed() } }
        )
    }

    /// The app came to the foreground: make sure the PC can reach it over the cable again.
    func becameActive() { cable.resume() }

    /// The cable carries what the QR code carries: pair without the camera, or reuse the pairing.
    private func onCableHello(_ raw: String) async {
        guard let t = PairingURI.parse(raw) else { return }
        cableHosts = t.hosts.filter { $0 != Self.loopback }
        cableServerPort = t.port
        let port = Int(CableLink.localPort)
        if let saved = Store.pairing, saved.fingerprint == t.fingerprint {
            let p = SavedPairing(host: Self.loopback, port: port, fingerprint: saved.fingerprint, token: saved.token, desktopName: saved.desktopName)
            Store.pairing = p
            connect(p)
        } else {
            await pair(PairingTarget(hosts: [Self.loopback], port: port, fingerprint: t.fingerprint, secret: t.secret, desktopName: t.desktopName))
        }
    }

    /// Unplugged: carry on over Wi-Fi when the PC is reachable there; otherwise wait for the cable.
    private func onCableClosed() async {
        guard let saved = Store.pairing, saved.host == Self.loopback, saved.port == Int(CableLink.localPort), !cableHosts.isEmpty else { return }
        let target = PairingTarget(hosts: cableHosts, port: cableServerPort, fingerprint: saved.fingerprint, secret: "", desktopName: saved.desktopName)
        guard let found = await reachable(target) else { return }
        let p = SavedPairing(host: found.1, port: cableServerPort, fingerprint: saved.fingerprint, token: saved.token, desktopName: saved.desktopName)
        Store.pairing = p
        connect(p)
    }

    /// Only the source phone picks documents; a pick still waiting when the phone becomes the
    /// receiver (direction swapped on the PC) is dropped instead of keeping a stale card on screen.
    private func setRole(_ r: String) {
        role = r
        if r == "target", let pending = documentsContinuation {
            documentsContinuation = nil
            askingDocuments = false
            pending.resume(returning: [])
        }
    }

    // MARK: Connection

    private func connect(_ p: SavedPairing) {
        let client = APIClient(host: p.host, port: p.port, fingerprint: p.fingerprint)
        client.token = p.token
        api = client
        desktopName = p.desktopName
        verificationCode = nil
        viaCable = p.host == "127.0.0.1"
        phase = .connected
        loops.forEach { $0.cancel() }
        loops = [
            Task { await heartbeatLoop(client) },
            Task { await commandLoop(client) },
        ]
    }

    private func heartbeatLoop(_ client: APIClient) async {
        while !Task.isCancelled {
            do {
                let r = try await client.heartbeat(DeviceEnv.heartbeat(foreground: foreground))
                setRole(r.role)
                phase = .connected
            } catch let e as APIError where e.status == 401 {
                forget(.forgotten); return
            } catch {
                if phase == .connected { phase = .reconnecting }
            }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
        }
    }

    private func commandLoop(_ client: APIClient) async {
        var after = Store.lastSeq
        while !Task.isCancelled {
            do {
                let r = try await client.commands(after: after)
                for c in r.commands {
                    after = max(after, c.seq)
                    Store.lastSeq = after
                    handle(c)
                }
            } catch let e as APIError where e.status == 401 {
                forget(.forgotten); return
            } catch {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    private func handle(_ c: Command) {
        switch c.type {
        case "role": setRole((try? c.decode(RoleCommand.self).role) ?? role)
        case "scan": if let cmd = try? c.decode(ScanCommand.self) { serially { await self.scan(cmd.categories) } }
        case "prepare": if let cmd = try? c.decode(PrepareCommand.self) { serially { await self.prepare(cmd) } }
        case "upload": if let cmd = try? c.decode(UploadCommand.self) { enqueue(cmd) }
        case "download": if let cmd = try? c.decode(SessionCommand.self) { receive(cmd.sessionId) }
        case "pause": controls.paused = true; transfer?.paused = true
        case "resume": controls.paused = false; transfer?.paused = false
        case "cancel": if let cmd = try? c.decode(SessionCommand.self) { cancel(cmd.sessionId) }
        case "unpair": forget(nil)
        default: break
        }
    }

    // MARK: Permissions (only for the categories the PC asked for)

    private func waitForAccessTap(_ categories: [String]) async {
        #if DEBUG
        // Cloud end-to-end test: nobody can tap the card; permissions are pre-granted with simctl.
        if UserDefaults.standard.bool(forKey: "SFSkipAccessCard") { return }
        #endif
        askingAccess = categories
        await withCheckedContinuation { accessContinuation = $0 }
        askingAccess = nil
    }

    func allowAccessTapped() {
        accessContinuation?.resume()
        accessContinuation = nil
    }

    private func authorize(_ category: String, write: Bool) async -> String {
        switch category {
        case Category.photos, Category.videos: return await PhotoLibrary.authorize()
        case Category.contacts: return await ContactsService.authorize()
        case Category.calendars: return await CalendarService.authorize()
        case Category.documents: return PermissionState.granted
        case Category.music: return write ? PermissionState.granted : PermissionState.unavailable
        default: return PermissionState.unavailable
        }
    }

    func documentsPicked(_ urls: [URL]) {
        documentsContinuation?.resume(returning: urls)
        documentsContinuation = nil
        askingDocuments = false
    }

    // MARK: Source

    private func scan(_ categories: [String]) async {
        guard let client = api else { return }
        let cats = categories.filter(Category.all.contains)
        // The user taps once in the app; then iOS shows its own dialog per category.
        sfLog.info("scan requested: \(cats.joined(separator: ","), privacy: .public); photos=\(PHPhotoLibrary.authorizationStatus(for: .readWrite).rawValue) contacts=\(CNContactStore.authorizationStatus(for: .contacts).rawValue)")
        if needsPrompt(cats) { await waitForAccessTap(cats) }
        var states: [String: String] = [:]
        for c in cats { states[c] = await authorize(c, write: false) }
        sfLog.info("scan permissions: \(states.map { "\($0.key)=\($0.value)" }.sorted().joined(separator: ","), privacy: .public)")
        if cats.contains(Category.documents), role != "target" {
            askingDocuments = true
            let urls = await withCheckedContinuation { documentsContinuation = $0 }
            await catalog.setPicked(urls)
        }
        var inventory: [CategoryInventory] = []
        for c in cats {
            do {
                let inv = try await catalog.scan(c, permission: states[c] ?? PermissionState.denied)
                inv.items.forEach { names[$0.id] = $0.name }
                inventory.append(inv)
                sfLog.info("scan \(c, privacy: .public): \(inv.count) items, \(inv.totalBytes) bytes")
            } catch {
                // Report the failure instead of leaving the PC waiting on this category forever.
                sfLog.error("scan \(c, privacy: .public) failed: \(String(describing: type(of: error)), privacy: .public)")
                inventory.append(CategoryInventory(category: c, permission: PermissionState.unavailable, count: 0, totalBytes: 0, note: "scan_failed"))
            }
        }
        await sendWithRetry("inventory") { try await client.inventory(InventoryBody(categories: inventory)) }
    }

    /// Results the PC is waiting for must arrive even through a short network hiccup.
    private func sendWithRetry(_ what: String, _ send: () async throws -> Void) async {
        for attempt in 1...5 {
            do { try await send(); return } catch {
                sfLog.error("sending \(what, privacy: .public) failed (attempt \(attempt))")
                try? await Task.sleep(nanoseconds: UInt64(attempt) * 1_000_000_000)
            }
        }
    }

    private func enqueue(_ cmd: UploadCommand) {
        guard let client = api else { return }
        if transfer?.sessionId != cmd.sessionId || transfer?.sending != true {
            transfer = TransferUI(sessionId: cmd.sessionId, sending: true)
        }
        // The PC may resend the list after a pause or reconnect: skip keys still waiting.
        let waiting = Set((queued[cmd.sessionId] ?? []).map(\.key))
        let fresh = cmd.items.filter { !waiting.contains($0.key) }
        transfer?.itemsTotal += fresh.count
        transfer?.finished = nil
        queued[cmd.sessionId, default: []].append(contentsOf: fresh)
        guard sessionTasks["up:\(cmd.sessionId)"] == nil else { return }
        controls.paused = false
        UIApplication.shared.isIdleTimerDisabled = true
        let uploader = Uploader(api: client, catalog: catalog, controls: controls) { [weak self] n, name in
            Task { @MainActor in self?.transfer?.bytes += n; self?.transfer?.currentName = name }
        }
        sessionTasks["up:\(cmd.sessionId)"] = Task {
            while let entry = queued[cmd.sessionId]?.first {
                queued[cmd.sessionId]?.removeFirst()
                do {
                    try await uploader.upload(cmd.sessionId, entry, name: names[entry.sourceId])
                } catch let e as APIError where e.status == 404 || e.status == 410 {
                    break
                } catch {
                    sfLog.error("upload queue stopped: \(String(describing: type(of: error)), privacy: .public)")
                    break // the PC re-sends the upload list after a reconnect
                }
                transfer?.itemsDone += 1
                if Task.isCancelled { break }
            }
            if let t = transfer, t.itemsDone >= t.itemsTotal { transfer?.finished = "done"; transfer?.currentName = nil }
            sessionTasks["up:\(cmd.sessionId)"] = nil
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    // MARK: Target

    private func prepare(_ cmd: PrepareCommand) async {
        guard let client = api else { return }
        let cats = cmd.categories.filter(Category.all.contains)
        if needsPrompt(cats) { await waitForAccessTap(cats) }
        var perms: [String: String] = [:]
        for c in cats {
            let s = await authorize(c, write: true)
            perms[c] = s == PermissionState.limited ? PermissionState.granted : s
        }
        let ok = perms.values.allSatisfy { $0 == PermissionState.granted }
        sfLog.info("prepare: \(perms.map { "\($0.key)=\($0.value)" }.sorted().joined(separator: ","), privacy: .public)")
        await sendWithRetry("prepare result") {
            try await client.prepareResult(PrepareResult(sessionId: cmd.sessionId, ok: ok, permissions: perms, storageFreeBytes: DeviceEnv.storage().free))
        }
    }

    private func receive(_ sid: String) {
        guard let client = api, sessionTasks["down:\(sid)"] == nil else { return }
        controls.paused = false
        transfer = TransferUI(sessionId: sid, sending: false)
        UIApplication.shared.isIdleTimerDisabled = true
        let receiver = Receiver(api: client, controls: controls, progress: { [weak self] n, name in
            Task { @MainActor in self?.transfer?.bytes += n; self?.transfer?.currentName = name }
        }, itemDone: { [weak self] in
            Task { @MainActor in self?.transfer?.itemsDone += 1 }
        })
        sessionTasks["down:\(sid)"] = Task {
            let end = await receiver.run(sid)
            transfer?.finished = end
            transfer?.currentName = nil
            sessionTasks["down:\(sid)"] = nil
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    private func cancel(_ sid: String) {
        sessionTasks.removeValue(forKey: "up:\(sid)")?.cancel()
        sessionTasks.removeValue(forKey: "down:\(sid)")?.cancel()
        queued[sid] = nil
        Task { await catalog.clear() }
        transfer?.finished = "cancelled"
        transfer?.currentName = nil
        UIApplication.shared.isIdleTimerDisabled = false
    }

    // MARK: Ending

    func disconnect() { forget(nil) }

    private func forget(_ msg: UserMessage?) {
        loops.forEach { $0.cancel() }
        loops = []
        sessionTasks.values.forEach { $0.cancel() }
        sessionTasks = [:]
        queued = [:]
        accessContinuation?.resume(); accessContinuation = nil
        documentsContinuation?.resume(returning: []); documentsContinuation = nil
        Task { await catalog.clear() }
        api = nil
        Store.pairing = nil
        Store.lastSeq = 0
        phase = .idle
        role = "none"
        transfer = nil
        askingAccess = nil
        askingDocuments = false
        message = msg
        UIApplication.shared.isIdleTimerDisabled = false
    }
}
