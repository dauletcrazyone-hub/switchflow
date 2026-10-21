import CryptoKit
import Foundation
import Security
import UIKit

struct SavedPairing: Codable {
    let host: String
    let port: Int
    let fingerprint: String
    let token: String
    let desktopName: String
}

/// Device id in UserDefaults; the pairing token in the Keychain (this device only, never synced).
enum Store {
    private static let service = "app.switchflow.companion.pairing"

    static var deviceId: String {
        if let id = UserDefaults.standard.string(forKey: "device_id") { return id }
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: "device_id")
        return id
    }

    static var lastSeq: Int64 {
        get { Int64(UserDefaults.standard.integer(forKey: "last_seq")) }
        set { UserDefaults.standard.set(Int(newValue), forKey: "last_seq") }
    }

    static var pairing: SavedPairing? {
        get {
            let q: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecReturnData as String: true,
                kSecMatchLimit as String: kSecMatchLimitOne,
            ]
            var out: CFTypeRef?
            guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
            return try? JSONDecoder().decode(SavedPairing.self, from: data)
        }
        set {
            let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
            SecItemDelete(base as CFDictionary)
            guard let newValue, let data = try? JSONEncoder().encode(newValue) else { return }
            var add = base
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    /// SHA-256 of files already received, so a repeated transfer does not create duplicates.
    static func wasReceived(_ sha: String) -> Bool {
        (UserDefaults.standard.stringArray(forKey: "received") ?? []).contains(sha)
    }

    static func markReceived(_ sha: String) {
        var all = UserDefaults.standard.stringArray(forKey: "received") ?? []
        all.append(sha)
        if all.count > 200_000 { all.removeFirst(all.count - 200_000) }
        UserDefaults.standard.set(all, forKey: "received")
    }
}

enum DeviceEnv {
    static func storage() -> (free: Int64, total: Int64) {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey, .volumeTotalCapacityKey])
        return (values?.volumeAvailableCapacityForImportantUsage ?? 0, Int64(values?.volumeTotalCapacity ?? 0))
    }

    @MainActor
    static func battery() -> (Int?, Bool?) {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let level = UIDevice.current.batteryLevel
        let state = UIDevice.current.batteryState
        return (level < 0 ? nil : Int((level * 100).rounded()), state == .unknown ? nil : (state == .charging || state == .full))
    }

    /// Hardware identifier, e.g. "iPhone16,1".
    static func identifier() -> String {
        var info = utsname()
        uname(&info)
        return withUnsafePointer(to: &info.machine) { $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) } }
    }

    /// Marketing name for common identifiers; falls back to the generic model.
    static func modelName() -> String {
        let id = identifier()
        let known: [String: String] = [
            "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus", "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max",
            "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus", "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max",
            "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus", "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max",
            "iPhone17,5": "iPhone 16e", "iPhone14,6": "iPhone SE (3rd gen)", "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro",
            "iPhone14,3": "iPhone 13 Pro Max", "iPhone14,4": "iPhone 13 mini", "iPhone13,2": "iPhone 12", "iPhone13,3": "iPhone 12 Pro",
        ]
        return known[id] ?? UIDevice.current.model
    }

    @MainActor
    static func info() -> DeviceInfo {
        let (free, total) = storage()
        let (battery, charging) = battery()
        return DeviceInfo(
            id: Store.deviceId,
            model: modelName(),
            modelCode: identifier(),
            osVersion: UIDevice.current.systemVersion,
            name: UIDevice.current.name,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0",
            storageFreeBytes: free,
            storageTotalBytes: total,
            batteryPercent: battery,
            charging: charging
        )
    }

    /// Changes every time the app process starts.
    static let instanceId = UUID().uuidString

    @MainActor
    static func heartbeat(foreground: Bool) -> Heartbeat {
        let (free, total) = storage()
        let (battery, charging) = battery()
        return Heartbeat(storageFreeBytes: free, storageTotalBytes: total, batteryPercent: battery, charging: charging,
                         appState: foreground ? "foreground" : "background", instanceId: instanceId)
    }
}

enum Hashing {
    static func hex<D: Digest>(_ d: D) -> String { d.map { String(format: "%02x", $0) }.joined() }

    /// Streams a file through SHA-256 in 1 MiB blocks.
    static func sha256(fileAt url: URL) throws -> (String, Int64) {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        var hasher = SHA256()
        var total: Int64 = 0
        while let chunk = try h.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
            total += Int64(chunk.count)
        }
        return (hex(hasher.finalize()), total)
    }
}
