import Foundation

/// Apps that came from an Android phone. They cannot run on an iPhone, so the list is kept for the
/// user to find each one in the App Store (most popular apps exist on both). Stored on this phone only.
enum AppStoreList {
    struct App: Codable, Equatable {
        let pkg: String
        let label: String
    }

    /// `android-apps.json` as the PC writes it (desktop engine.rs, AppListEntry).
    struct Payload: Decodable {
        let apps: [App]
    }

    private static let key = "android_apps"

    static var all: [App] {
        guard let data = UserDefaults.standard.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([App].self, from: data)) ?? []
    }

    /// Adds apps not listed yet (a second transfer does not duplicate lines).
    static func add(_ apps: [App]) {
        var list = all
        for a in apps where !list.contains(where: { $0.pkg == a.pkg }) {
            list.append(App(pkg: String(a.pkg.prefix(150)), label: String(a.label.prefix(80))))
        }
        if let data = try? JSONEncoder().encode(list) { UserDefaults.standard.set(data, forKey: key) }
    }

    static func clear() { UserDefaults.standard.removeObject(forKey: key) }

    /// Opens the App Store app on its search for the name.
    static func searchURL(_ name: String) -> URL? {
        var c = URLComponents(string: "itms-apps://search.itunes.apple.com/WebObjects/MZSearch.woa/wa/search")
        c?.queryItems = [URLQueryItem(name: "media", value: "software"), URLQueryItem(name: "term", value: name)]
        return c?.url
    }
}
