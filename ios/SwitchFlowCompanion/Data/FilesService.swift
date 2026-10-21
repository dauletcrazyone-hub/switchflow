import Foundation

/// Received documents and music go to Documents/SwitchFlow/<Category>, visible in the Files app.
enum FilesService {
    static var root: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("SwitchFlow", isDirectory: true)
    }

    static func folder(for category: String) -> URL {
        root.appendingPathComponent(category == Category.music ? "Music" : category == Category.photos || category == Category.videos ? "Media" : "Documents", isDirectory: true)
    }

    static func safeName(_ name: String) -> String {
        let bad = CharacterSet(charactersIn: "/\\:?%*|\"<>").union(.controlCharacters)
        let cleaned = name.components(separatedBy: bad).joined(separator: "_").trimmingCharacters(in: .whitespaces)
        return cleaned.isEmpty ? "file" : String(cleaned.prefix(180))
    }

    /// "a.pdf" → "a (2).pdf" when taken.
    static func uniqueURL(in dir: URL, name: String) -> URL {
        let fm = FileManager.default
        var url = dir.appendingPathComponent(safeName(name))
        let stem = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        var n = 2
        while fm.fileExists(atPath: url.path) {
            url = dir.appendingPathComponent(ext.isEmpty ? "\(stem) (\(n))" : "\(stem) (\(n)).\(ext)")
            n += 1
        }
        return url
    }

    /// Moves a verified temp file into place and re-reads it to prove it was stored intact.
    static func store(tempFile: URL, category: String, name: String) throws -> (URL, String) {
        let dir = folder(for: category)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = uniqueURL(in: dir, name: name)
        try FileManager.default.moveItem(at: tempFile, to: dest)
        let (sha, _) = try Hashing.sha256(fileAt: dest)
        return (dest, sha)
    }
}
