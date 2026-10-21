import Foundation

/// Contents of the QR code shown by the SwitchFlow PC.
struct PairingTarget: Equatable {
    let hosts: [String]
    let port: Int
    /// SHA-256 of the PC's DER certificate, 64 lowercase hex chars.
    let fingerprint: String
    let secret: String
    let desktopName: String
}

enum PairingURI {
    /// Returns nil for anything that is not a well-formed SwitchFlow v1 pairing code.
    static func parse(_ raw: String) -> PairingTarget? {
        guard let comps = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              comps.scheme == "switchflow", comps.host == "pair",
              let items = comps.queryItems else { return nil }
        var q: [String: String] = [:]
        for item in items { if let v = item.value { q[item.name] = v } }

        guard q["v"] == "1" else { return nil }
        let hosts = (q["hosts"] ?? "").split(separator: ",").map { String($0).trimmingCharacters(in: .whitespaces) }.filter(isIPv4)
        guard !hosts.isEmpty else { return nil }
        guard let port = Int(q["port"] ?? ""), (1...65535).contains(port) else { return nil }
        guard let fp = q["fp"]?.lowercased(), fp.count == 64, fp.allSatisfy({ $0.isHexDigit }) else { return nil }
        let secretChars = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        guard let secret = q["s"], (16...64).contains(secret.count), secret.allSatisfy({ secretChars.contains($0) }) else { return nil }
        var unique: [String] = []
        for h in hosts where !unique.contains(h) && unique.count < 8 { unique.append(h) }
        let name = String((q["n"] ?? "PC").filter { !$0.isNewline }.prefix(40))
        return PairingTarget(hosts: unique, port: port, fingerprint: fp, secret: secret, desktopName: name.isEmpty ? "PC" : name)
    }

    static func isIPv4(_ s: String) -> Bool {
        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        return parts.count == 4 && parts.allSatisfy { p in
            guard (1...3).contains(p.count), let v = Int(p) else { return false }
            return (0...255).contains(v)
        }
    }
}
