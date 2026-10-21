import Foundation
import SwiftUI
import UIKit

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
    private var documentsContinuation: CheckedContinuation<[URL], Never>?

    func start() {
        if let saved = Store.pairing { connect(saved) }
    }

    // MARK: Pairing

    func onScanned(_ raw: String) {
        guard let target = PairingURI.parse(raw) else { message = .badQR; return }
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
                role = r.role
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
        case "role": role = (try? c.decode(RoleCommand.self).role) ?? role
        case "scan": if let cmd = try? c.decode(ScanCommand.self) { Task { await scan(cmd.categories) } }
        case "prepare": if let cmd = try? c.decode(PrepareCommand.self) { Task { await prepare(cmd) } }
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
        let needsPrompt = cats.contains { [Category.photos, Category.videos, Category.contacts, Category.calendars].contains($0) }
        if needsPrompt { await waitForAccessTap(cats) }
        var states: [String: String] = [:]
        for c in cats { states[c] = await authorize(c, write: false) }
        if cats.contains(Category.documents) {
            askingDocuments = true
            let urls = await withCheckedContinuation { documentsContinuation = $0 }
            await catalog.setPicked(urls)
        }
        var inventory: [CategoryInventory] = []
        for c in cats {
            if let inv = try? await catalog.scan(c, permission: states[c] ?? PermissionState.denied) {
                inv.items.forEach { names[$0.id] = $0.name }
                inventory.append(inv)
            }
        }
        try? await client.inventory(InventoryBody(categories: inventory))
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
        let needsPrompt = cats.contains { [Category.photos, Category.videos, Category.contacts, Category.calendars].contains($0) }
        if needsPrompt { await waitForAccessTap(cats) }
        var perms: [String: String] = [:]
        for c in cats {
            let s = await authorize(c, write: true)
            perms[c] = s == PermissionState.limited ? PermissionState.granted : s
        }
        let ok = perms.values.allSatisfy { $0 == PermissionState.granted }
        try? await client.prepareResult(PrepareResult(sessionId: cmd.sessionId, ok: ok, permissions: perms, storageFreeBytes: DeviceEnv.storage().free))
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
