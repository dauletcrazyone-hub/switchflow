import CryptoKit
import Foundation

/// What this iPhone offers as a source. Photos/videos are exported to a temporary file
/// right before upload; contacts and calendars are exported once and deleted afterwards.
actor SourceCatalog {
    private var files: [String: URL] = [:]
    var pickedDocuments: [URL] = []

    private var exportDir: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("sf-export", isDirectory: true)
    }

    func setPicked(_ urls: [URL]) { pickedDocuments = urls }

    func scan(_ category: String, permission: String) throws -> CategoryInventory {
        try FileManager.default.createDirectory(at: exportDir, withIntermediateDirectories: true)
        switch category {
        case Category.documents:
            var items: [SourceItem] = []
            for (i, url) in pickedDocuments.enumerated() {
                let id = "documents:\(i)"
                files[id] = url
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { $0 }.map(Int64.init) ?? 0
                items.append(SourceItem(id: id, category: category, name: url.lastPathComponent, size: size))
            }
            return CategoryInventory(category: category, permission: PermissionState.granted,
                                     count: Int64(items.count), totalBytes: items.reduce(0) { $0 + $1.size }, items: items,
                                     note: items.isEmpty ? "not_picked" : nil)
        case Category.music:
            // iOS gives third-party apps no way to export the Music library.
            return CategoryInventory(category: category, permission: PermissionState.unavailable, count: 0, totalBytes: 0)
        default:
            break
        }
        guard permission == PermissionState.granted || permission == PermissionState.limited else {
            return CategoryInventory(category: category, permission: permission, count: 0, totalBytes: 0)
        }
        switch category {
        case Category.photos, Category.videos:
            let (items, albums) = PhotoLibrary.list(category: category)
            return CategoryInventory(category: category, permission: permission, count: Int64(items.count),
                                     totalBytes: items.reduce(0) { $0 + $1.size }, albums: albums, items: items)
        case Category.contacts:
            let (data, count) = try ContactsService.exportAll()
            let url = exportDir.appendingPathComponent("contacts.vcf")
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            files[Category.contacts] = url
            let item = SourceItem(id: Category.contacts, category: category, name: "contacts.vcf", size: Int64(data.count), mime: "text/vcard", itemCount: count)
            return CategoryInventory(category: category, permission: permission, count: Int64(count), totalBytes: Int64(data.count), items: count > 0 ? [item] : [])
        case Category.calendars:
            let events = CalendarService.exportAll()
            let data = try JSONEncoder().encode(CalendarExport(events: events))
            let url = exportDir.appendingPathComponent("calendar-events.json")
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            files[Category.calendars] = url
            let item = SourceItem(id: Category.calendars, category: category, name: "calendar-events.json", size: Int64(data.count), mime: "application/json", itemCount: events.count)
            return CategoryInventory(category: category, permission: permission, count: Int64(events.count), totalBytes: Int64(data.count), items: events.isEmpty ? [] : [item])
        default:
            return CategoryInventory(category: category, permission: PermissionState.unavailable, count: 0, totalBytes: 0)
        }
    }

    /// A readable file for the item, and whether it is a temporary copy to delete afterwards.
    func file(for sourceId: String) async throws -> (URL, Bool) {
        if let f = files[sourceId] { return (f, false) }
        return (try await PhotoLibrary.exportOriginal(id: sourceId), true)
    }

    func clear() {
        files.removeAll()
        pickedDocuments.removeAll()
        try? FileManager.default.removeItem(at: exportDir)
        try? FileManager.default.removeItem(at: FileManager.default.temporaryDirectory.appendingPathComponent("sf-upload"))
    }
}

/// Shared pause flag, flipped by the PC's pause/resume commands and by 423 responses.
final class SessionControls: @unchecked Sendable {
    private let lock = NSLock()
    private var _paused = false
    var paused: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _paused }
        set { lock.lock(); _paused = newValue; lock.unlock() }
    }

    func awaitRunning() async throws {
        var waited = 0
        while paused {
            try await Task.sleep(nanoseconds: 1_000_000_000)
            waited += 1
            if waited >= 3 { paused = false } // re-check with the PC; a 423 pauses again
        }
    }
}

/// Source side: uploads the original in 4 MiB chunks, resuming from the PC's offset.
struct Uploader {
    static let chunk = 4 * 1024 * 1024
    let api: APIClient
    let catalog: SourceCatalog
    let controls: SessionControls
    let progress: @Sendable (Int64, String?) -> Void

    func upload(_ sid: String, _ entry: UploadEntry, name: String?) async throws {
        do {
            try await uploadAttempts(sid, entry, name: name)
        } catch let e as APIError where e.status == 404 || e.status == 410 {
            throw e
        } catch is URLError {
            throw URLError(.networkConnectionLost)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            // Reading the original failed, or the PC refused this file: report it and continue.
            try? await api.fail(sid, entry.key, error: "source_read_failed")
        }
    }

    private func uploadAttempts(_ sid: String, _ entry: UploadEntry, name: String?) async throws {
        for _ in 0..<4 {
            let status = try await api.uploadStatus(sid, entry.key)
            guard status.state == "pending" || status.state == "receiving" else { return }
            let file: URL
            let temporary: Bool
            do {
                (file, temporary) = try await catalog.file(for: entry.sourceId)
            } catch let e as TransferError {
                try await api.fail(sid, entry.key, error: e.code)
                return
            }
            defer { if temporary { try? FileManager.default.removeItem(at: file) } }

            let (sha, size) = try Hashing.sha256(fileAt: file)
            var offset = status.receivedBytes
            if offset > size {
                try await api.fail(sid, entry.key, error: "source_read_failed")
                return
            }
            let handle = try FileHandle(forReadingFrom: file)
            defer { try? handle.close() }
            try handle.seek(toOffset: UInt64(offset))
            var restart = false
            while offset < size {
                try await controls.awaitRunning()
                guard let data = try handle.read(upToCount: Uploader.chunk), !data.isEmpty else { break }
                do {
                    _ = try await sendChunk(sid, entry.key, offset, data)
                } catch let e as APIError where e.status == 409 {
                    restart = true
                    break
                }
                offset += Int64(data.count)
                progress(Int64(data.count), name)
            }
            if restart { continue }
            do {
                try await api.complete(sid, entry.key, size: offset, sha256: sha)
                return
            } catch let e as APIError where e.status == 422 || e.status == 409 {
                continue
            }
        }
        try await api.fail(sid, entry.key, error: "checksum_mismatch")
    }

    private func sendChunk(_ sid: String, _ key: String, _ offset: Int64, _ data: Data) async throws -> Int64 {
        var networkErrors = 0
        while true {
            do {
                return try await api.putChunk(sid, key, offset: offset, data: data)
            } catch let e as APIError {
                switch e.status {
                case 423: controls.paused = true; try await controls.awaitRunning()
                case 429: try await Task.sleep(nanoseconds: 2_000_000_000)
                default: throw e
                }
            } catch is URLError {
                networkErrors += 1
                if networkErrors > 20 { throw URLError(.networkConnectionLost) }
                try await Task.sleep(nanoseconds: UInt64(min(10, networkErrors)) * 1_000_000_000)
            }
        }
    }
}

/// Target side: pulls verified items in 8 MiB ranges, saves them, and acknowledges with SHA-256.
struct Receiver {
    static let range: Int64 = 8 * 1024 * 1024
    let api: APIClient
    let controls: SessionControls
    let progress: @Sendable (Int64, String?) -> Void
    let itemDone: @Sendable () -> Void

    func run(_ sid: String) async -> String {
        var errors = 0
        while !Task.isCancelled {
            do {
                let r = try await api.next(sid)
                errors = 0
                switch r.state {
                case "item":
                    controls.paused = false
                    if let item = r.item { await handle(sid, item) }
                case "paused":
                    controls.paused = true
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                case "done", "cancelled":
                    return r.state
                default:
                    controls.paused = false
                }
            } catch let e as APIError where e.status == 404 {
                return "cancelled"
            } catch {
                errors += 1
                try? await Task.sleep(nanoseconds: UInt64(min(10, errors)) * 1_000_000_000)
            }
        }
        return "cancelled"
    }

    private func download(_ sid: String, _ item: DeliveryItem) async throws -> (URL, String) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sf-download", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(UUID().uuidString + "-" + FilesService.safeName(item.name))
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let out = try FileHandle(forWritingTo: url)
        defer { try? out.close() }
        var hasher = SHA256()
        var written: Int64 = 0
        var failures = 0
        while written < item.size {
            try await controls.awaitRunning()
            do {
                let data = try await api.content(sid, item.key, from: written, to: min(item.size, written + Receiver.range) - 1)
                guard !data.isEmpty else { throw URLError(.zeroByteResource) }
                try out.write(contentsOf: data)
                hasher.update(data: data)
                written += Int64(data.count)
                progress(Int64(data.count), item.name)
            } catch let e as APIError where e.status == 423 {
                controls.paused = true
            } catch let e as APIError {
                throw e
            } catch {
                failures += 1
                if failures > 8 { throw error }
                try await Task.sleep(nanoseconds: UInt64(min(10, failures)) * 1_000_000_000)
            }
        }
        try out.synchronize()
        return (url, Hashing.hex(hasher.finalize()))
    }

    private func handle(_ sid: String, _ item: DeliveryItem) async {
        var ack = AckBody(result: "failed", error: "io_error")
        var tempFile: URL?
        do {
            if Store.wasReceived(item.sha256) {
                ack = AckBody(result: "duplicate")
            } else {
                let (file, sha) = try await download(sid, item)
                tempFile = file
                guard sha == item.sha256 else { throw TransferError.checksumMismatch }
                ack = try await save(item, file: file, sha: sha)
                if ack.result == "saved" { Store.markReceived(sha) }
            }
        } catch let e as TransferError {
            ack = AckBody(result: "failed", error: e.code)
        } catch let e as APIError where e.status == 404 || e.status == 410 {
            return
        } catch {
            let ns = error as NSError
            ack = AckBody(result: "failed", error: ns.domain == NSCocoaErrorDomain && ns.code == NSFileWriteOutOfSpaceError ? "insufficient_storage" : "io_error")
        }
        if ack.result == "failed" { sfLog.error("receive \(item.category, privacy: .public) failed: \(ack.error ?? "?", privacy: .public)") }
        if let tempFile { try? FileManager.default.removeItem(at: tempFile) }
        // Already saved: never lose that fact to a network blip.
        for attempt in 1...10 {
            do { try await api.ack(sid, item.key, ack); break }
            catch let e as APIError where (400..<500).contains(e.status) { break }
            catch { try? await Task.sleep(nanoseconds: UInt64(attempt) * 1_000_000_000) }
        }
        itemDone()
    }

    private func save(_ item: DeliveryItem, file: URL, sha: String) async throws -> AckBody {
        switch item.category {
        case Category.photos, Category.videos:
            do {
                try await PhotoLibrary.save(fileAt: file, name: item.name, isVideo: item.category == Category.videos,
                                            created: item.createdMs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) },
                                            albumName: item.albumName)
                // PhotoKit may store the asset differently, so the proof is the hash of the bytes handed to it.
                return AckBody(result: "saved", sha256: sha, verification: "written_file")
            } catch {
                // Photos refused the format (e.g. some Android-only codecs): keep the file in Files instead.
                let (_, stored) = try FilesService.store(tempFile: file, category: item.category, name: item.name)
                return AckBody(result: "saved", sha256: stored, verification: "read_back")
            }
        case Category.contacts:
            let detail = try ContactsService.importVCards(try Data(contentsOf: file), policy: item.contactsPolicy ?? "merge")
            return AckBody(result: "saved", sha256: sha, verification: "written_file", detail: detail)
        case Category.calendars:
            let export = try JSONDecoder().decode(CalendarExport.self, from: Data(contentsOf: file))
            let detail = try CalendarService.importEvents(export.events)
            return AckBody(result: "saved", sha256: sha, verification: "written_file", detail: detail)
        default:
            let (_, stored) = try FilesService.store(tempFile: file, category: item.category, name: item.name)
            return AckBody(result: "saved", sha256: stored, verification: "read_back")
        }
    }
}
