import CryptoKit
import Foundation

// Phone-to-phone transfer (docs/DIRECT.md). Keep in sync with
// android/app/src/main/java/app/switchflow/companion/direct/DirectSession.kt.

enum DirectType {
    static let hello: UInt8 = 1
    static let role: UInt8 = 2
    static let offer: UInt8 = 3
    static let accept: UInt8 = 4
    static let item: UInt8 = 5
    static let data: UInt8 = 6
    static let end: UInt8 = 7
    static let ack: UInt8 = 8
    static let done: UInt8 = 9
    static let finish: UInt8 = 10
    static let cancel: UInt8 = 11
    static let ping: UInt8 = 12
}

enum DirectIntent {
    static let send = "send"
    static let receive = "receive"
    static let any = "any"

    /// Who sends, from both intents. Nil: a conflict (the iPhone never has "any": no cable).
    static func resolve(mine: String, theirs: String) -> String? {
        if mine == send && theirs != send { return send }
        if mine == receive && theirs != receive { return receive }
        if mine == any && theirs == send { return receive }
        if mine == any && theirs == receive { return send }
        return nil
    }
}

struct PeerDevice: Codable, Equatable {
    var id: String
    var name: String
    var model: String
    var platform: String
    var os: String
    var app: String
}

struct Hello: Codable {
    var v: Int = 1
    var device: PeerDevice
    var intent: String
}

struct RoleMsg: Codable { var intent: String }

struct AppMeta: Codable {
    var pkg: String
    var label: String
    var versionCode: Int64
    var part: Int
    var parts: Int
    var minSdk: Int?
}

struct OfferItem: Codable {
    var k: String
    var c: String
    var n: String
    var s: Int64
    var m: String?
    var t: Int64?
    var a: String?
    var cnt: Int?
    var app: AppMeta?
}

struct Offer: Codable {
    var sessionId: String
    var items: [OfferItem]
}

struct Accept: Codable {
    var ok: Bool
    var skip: [String]?
    var freeBytes: Int64?
    var error: String?
    /// Why a key is skipped: "exists", "installed", "old_android" (sent by Android receivers).
    var why: [String: String]?
}

struct ItemStart: Codable { var k: String }
struct ItemEnd: Codable { var k: String; var size: Int64; var sha256: String; var error: String? }
struct ItemAck: Codable { var k: String; var result: String; var error: String? }
struct Finish: Codable, Equatable { var saved: Int; var skipped: Int; var failed: Int }
struct CancelMsg: Codable { var reason: String }

enum DirectError: Error, Equatable {
    case auth, closed, cancelled(String), refused(String), timeout, badMessage
}

enum DirectCrypto {
    static let keyBytes = 32

    static func randomBytes(_ n: Int) -> Data {
        var d = Data(count: n)
        let ok = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        precondition(ok == errSecSuccess, "no randomness")
        return d
    }

    static func sessionKey(key: Data, clientNonce: Data, serverNonce: Data) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: key),
            salt: clientNonce + serverNonce,
            info: Data("SwitchFlow direct v1".utf8),
            outputByteCount: 32
        )
    }

    /// direction · 0 0 0 · counter (big-endian).
    static func nonce(direction: UInt8, counter: UInt64) -> Data {
        var d = Data([direction, 0, 0, 0])
        withUnsafeBytes(of: counter.bigEndian) { d.append(contentsOf: $0) }
        return d
    }

    /// Ciphertext ‖ tag of `type · body`.
    static func seal(type: UInt8, body: Data, key: SymmetricKey, direction: UInt8, counter: UInt64) throws -> Data {
        var plain = Data([type])
        plain.append(body)
        let box = try AES.GCM.seal(plain, using: key, nonce: AES.GCM.Nonce(data: nonce(direction: direction, counter: counter)))
        return box.ciphertext + box.tag
    }

    static func open(_ sealed: Data, key: SymmetricKey, direction: UInt8, counter: UInt64) throws -> (UInt8, Data) {
        guard sealed.count >= 17 else { throw DirectError.auth }
        do {
            let ct = sealed.prefix(sealed.count - 16)
            let tag = sealed.suffix(16)
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce(direction: direction, counter: counter)), ciphertext: ct, tag: tag)
            let plain = try AES.GCM.open(box, using: key)
            guard let type = plain.first else { throw DirectError.auth }
            return (type, Data(plain.dropFirst()))
        } catch {
            throw DirectError.auth
        }
    }
}

/// Contents of the QR code a receiving phone shows.
struct DirectTarget: Equatable {
    var hosts: [String]
    var port: Int
    var key: Data
    var name: String
    var platform: String
}

enum DirectQR {
    static func isDirect(_ raw: String) -> Bool {
        raw.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("switchflow://direct")
    }

    static func build(_ t: DirectTarget) -> String {
        // Strict encoding: URLComponents would leave "&", "=" and "+" in the name as they are.
        let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        let name = String(t.name.prefix(40)).addingPercentEncoding(withAllowedCharacters: unreserved) ?? "Phone"
        return "switchflow://direct?v=1&h=\(t.hosts.joined(separator: ","))&p=\(t.port)&k=\(base64url(t.key))&n=\(name)&o=\(t.platform)"
    }

    static func parse(_ raw: String) -> DirectTarget? {
        guard let comps = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              comps.scheme == "switchflow", comps.host == "direct", let items = comps.queryItems else { return nil }
        var q: [String: String] = [:]
        for i in items { if let v = i.value { q[i.name] = v } }
        guard q["v"] == "1" else { return nil }
        var hosts: [String] = []
        for h in (q["h"] ?? "").split(separator: ",").map({ String($0).trimmingCharacters(in: .whitespaces) })
        where PairingURI.isIPv4(h) && !hosts.contains(h) && hosts.count < 8 { hosts.append(h) }
        guard !hosts.isEmpty, let port = Int(q["p"] ?? ""), (1...65535).contains(port) else { return nil }
        guard let k = q["k"], k.count == 43, let key = unbase64url(k), key.count == DirectCrypto.keyBytes else { return nil }
        let name = String((q["n"] ?? "").filter { !$0.isNewline }.prefix(40))
        return DirectTarget(hosts: hosts, port: port, key: key, name: name.isEmpty ? "Phone" : name, platform: q["o"] == "ios" ? "ios" : "android")
    }

    static func base64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func unbase64url(_ s: String) -> Data? {
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        guard s.allSatisfy({ allowed.contains($0) }) else { return nil }
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        return Data(base64Encoded: b)
    }
}

extension JSONEncoder {
    static let direct = JSONEncoder()
}

extension JSONDecoder {
    static let direct = JSONDecoder()
}
