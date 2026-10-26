import Foundation

// Wire types of docs/PROTOCOL.md. Keep in sync with desktop/src-tauri/src/model.rs.

enum Category {
    static let photos = "photos"
    static let videos = "videos"
    static let contacts = "contacts"
    static let documents = "documents"
    static let music = "music"
    static let calendars = "calendars"
    static let all = [photos, videos, contacts, documents, music, calendars]
}

enum PermissionState {
    static let granted = "granted"
    static let limited = "limited"
    static let denied = "denied"
    static let unavailable = "unavailable"
}

struct DeviceInfo: Codable {
    var id: String
    var platform = "ios"
    var manufacturer = "Apple"
    /// Marketing name ("iPhone 15 Pro"); `name` is whatever the user called the phone.
    var model: String
    var modelCode: String?
    var osVersion: String
    var name: String
    var appVersion: String
    var storageFreeBytes: Int64
    var storageTotalBytes: Int64
    var batteryPercent: Int?
    var charging: Bool?
}

struct PairRequest: Encodable { let secret: String; let device: DeviceInfo }
struct PairResponse: Decodable { let pairingId: String; let verificationCode: String }
struct PairStatus: Decodable { let status: String; let token: String?; let desktopName: String? }

struct Heartbeat: Encodable {
    let storageFreeBytes: Int64
    let storageTotalBytes: Int64
    let batteryPercent: Int?
    let charging: Bool?
    let appState: String
    /// Random per process: lets the PC notice that the app was restarted and resend work.
    let instanceId: String
}

struct HeartbeatResponse: Decodable { let role: String }

/// A command from the PC. The payload is kept as raw JSON and decoded per type.
struct Command: Decodable {
    let seq: Int64
    let type: String
    let payload: Data

    enum CodingKeys: String, CodingKey { case seq, type, payload }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        seq = try c.decode(Int64.self, forKey: .seq)
        type = try c.decode(String.self, forKey: .type)
        payload = (try? c.decode(JSONValue.self, forKey: .payload)).flatMap { try? JSONEncoder().encode($0) } ?? Data("{}".utf8)
    }

    func decode<T: Decodable>(_ t: T.Type) throws -> T { try JSONDecoder().decode(t, from: payload) }
}

struct CommandsResponse: Decodable { let commands: [Command] }

struct SourceItem: Codable {
    let id: String
    let category: String
    let name: String
    let size: Int64
    var sizeExact = true
    var mime = ""
    var createdMs: Int64?
    var modifiedMs: Int64?
    var albumIds: [String] = []
    var itemCount: Int?
}

struct Album: Codable { let id: String; let name: String; let count: Int64; let totalBytes: Int64 }

/// One contact in the exported vCard, so the PC can offer a pick list. `id` is the card's position.
struct Entry: Codable, Equatable {
    let id: String
    let name: String
    var detail: String = ""
}

struct CategoryInventory: Codable {
    let category: String
    let permission: String
    let count: Int64
    let totalBytes: Int64
    var albums: [Album] = []
    var items: [SourceItem] = []
    var note: String?
    var entries: [Entry] = []
}

struct InventoryBody: Encodable { let categories: [CategoryInventory] }

struct PrepareResult: Encodable {
    let sessionId: String
    let ok: Bool
    let permissions: [String: String]
    let storageFreeBytes: Int64
    var error: String?
}

struct UploadStatus: Decodable { let receivedBytes: Int64; let state: String }
struct ChunkAck: Decodable { let receivedBytes: Int64 }
struct CompleteBody: Encodable { let size: Int64; let sha256: String }
struct FailBody: Encodable { let error: String }

struct DeliveryItem: Decodable {
    let key: String
    let category: String
    let name: String
    let size: Int64
    let sha256: String
    var mime: String?
    var createdMs: Int64?
    var modifiedMs: Int64?
    var albumName: String?
    var itemCount: Int?
    var contactsPolicy: String?
}

struct NextResponse: Decodable { let state: String; let item: DeliveryItem? }

struct AckDetail: Codable { var imported: Int64 = 0; var merged: Int64 = 0; var skipped: Int64 = 0 }

struct AckBody: Encodable {
    let result: String
    var sha256: String?
    var verification: String?
    var error: String?
    var detail: AckDetail?
}

struct UploadEntry: Decodable { let key: String; let sourceId: String }
struct UploadCommand: Decodable { let sessionId: String; let items: [UploadEntry] }
struct PrepareCommand: Decodable { let sessionId: String; let categories: [String]; let totalBytes: Int64; let contactsPolicy: String? }
struct SessionCommand: Decodable { let sessionId: String }
struct ScanCommand: Decodable { let categories: [String] }
struct RoleCommand: Decodable { let role: String }
struct ErrorBody: Decodable { let error: String?; let receivedBytes: Int64? }

/// Minimal JSON value used to re-encode arbitrary command payloads.
enum JSONValue: Codable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let s): try c.encode(s)
        case .number(let n):
            // Keep integers integral so Int64 fields decode again.
            if n.rounded() == n && abs(n) < 9e15 { try c.encode(Int64(n)) } else { try c.encode(n) }
        case .bool(let b): try c.encode(b)
        case .object(let o): try c.encode(o)
        case .array(let a): try c.encode(a)
        case .null: try c.encodeNil()
        }
    }
}
