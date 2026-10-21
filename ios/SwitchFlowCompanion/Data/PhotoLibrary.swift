import Foundation
import Photos
import UniformTypeIdentifiers

/// PhotoKit: lists originals, exports them to a temporary file for upload, saves received media.
enum PhotoLibrary {
    static func authorize() async -> String {
        let s = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        switch s {
        case .authorized: return PermissionState.granted
        case .limited: return PermissionState.limited
        default: return PermissionState.denied
        }
    }

    static var currentState: String {
        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
        case .authorized: return PermissionState.granted
        case .limited: return PermissionState.limited
        default: return PermissionState.denied
        }
    }

    /// The resource holding the original bytes (not an edited rendition).
    static func originalResource(_ asset: PHAsset) -> PHAssetResource? {
        let rs = PHAssetResource.assetResources(for: asset)
        let wanted: [PHAssetResourceType] = asset.mediaType == .video ? [.video, .fullSizeVideo] : [.photo, .fullSizePhoto]
        for t in wanted { if let r = rs.first(where: { $0.type == t }) { return r } }
        return rs.first
    }

    /// Lists photos or videos. Ids are PHAsset local identifiers.
    static func list(category: String) -> (items: [SourceItem], albums: [Album]) {
        let type: PHAssetMediaType = category == Category.videos ? .video : .image
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let assets = PHAsset.fetchAssets(with: type, options: opts)

        // Album membership: user albums plus Favorites.
        var membership: [String: [String]] = [:]
        var albums: [Album] = []
        var collections: [PHAssetCollection] = []
        PHAssetCollection.fetchAssetCollections(with: .album, subtype: .albumRegular, options: nil).enumerateObjects { c, _, _ in collections.append(c) }
        PHAssetCollection.fetchAssetCollections(with: .smartAlbum, subtype: .smartAlbumFavorites, options: nil).enumerateObjects { c, _, _ in collections.append(c) }
        var sizes: [String: Int64] = [:]

        var items: [SourceItem] = []
        assets.enumerateObjects { asset, _, _ in
            guard let r = originalResource(asset) else { return }
            // `fileSize` is not public API; fall back to an estimate and mark it as such.
            let size = (r.value(forKey: "fileSize") as? NSNumber)?.int64Value
            sizes[asset.localIdentifier] = size ?? 0
            items.append(SourceItem(
                id: asset.localIdentifier,
                category: category,
                name: r.originalFilename,
                size: size ?? (type == .video ? 50_000_000 : 3_000_000),
                sizeExact: size != nil,
                mime: UTType(r.uniformTypeIdentifier)?.preferredMIMEType ?? "",
                createdMs: asset.creationDate.map { Int64($0.timeIntervalSince1970 * 1000) },
                modifiedMs: asset.modificationDate.map { Int64($0.timeIntervalSince1970 * 1000) }
            ))
        }
        for c in collections {
            let inAlbum = PHAsset.fetchAssets(in: c, options: nil)
            var count: Int64 = 0
            var bytes: Int64 = 0
            inAlbum.enumerateObjects { a, _, _ in
                guard a.mediaType == type else { return }
                membership[a.localIdentifier, default: []].append(c.localIdentifier)
                count += 1
                bytes += sizes[a.localIdentifier] ?? 0
            }
            if count > 0 { albums.append(Album(id: c.localIdentifier, name: c.localizedTitle ?? "Album", count: count, totalBytes: bytes)) }
        }
        items = items.map { var i = $0; i.albumIds = membership[i.id] ?? []; return i }
        return (items, albums)
    }

    /// Writes the original to a temporary file, downloading from iCloud when needed.
    static func exportOriginal(id: String) async throws -> URL {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject,
              let r = originalResource(asset) else { throw TransferError.notFound }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sf-upload", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(UUID().uuidString + "-" + r.originalFilename)
        let opts = PHAssetResourceRequestOptions()
        opts.isNetworkAccessAllowed = true
        do {
            try await PHAssetResourceManager.default().writeData(for: r, toFile: url, options: opts)
        } catch {
            throw TransferError.icloudUnavailable
        }
        return url
    }

    private static func album(named name: String) throws -> PHAssetCollection? {
        let opts = PHFetchOptions()
        opts.predicate = NSPredicate(format: "title = %@", name)
        if let existing = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .albumRegular, options: opts).firstObject {
            return existing
        }
        var placeholder: PHObjectPlaceholder?
        try PHPhotoLibrary.shared().performChangesAndWait {
            placeholder = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: name).placeholderForCreatedAssetCollection
        }
        guard let id = placeholder?.localIdentifier else { return nil }
        return PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [id], options: nil).firstObject
    }

    /// Adds a received photo/video to the library with its original name and date.
    static func save(fileAt url: URL, name: String, isVideo: Bool, created: Date?, albumName: String?) async throws {
        let target = try albumName.flatMap { try album(named: $0) }
        try await PHPhotoLibrary.shared().performChanges {
            let req = PHAssetCreationRequest.forAsset()
            let opts = PHAssetResourceCreationOptions()
            opts.originalFilename = name
            req.addResource(with: isVideo ? .video : .photo, fileURL: url, options: opts)
            if let created { req.creationDate = created }
            if let target, let ph = req.placeholderForCreatedAsset {
                PHAssetCollectionChangeRequest(for: target)?.addAssets([ph] as NSArray)
            }
        }
    }
}

enum TransferError: Error {
    case notFound, icloudUnavailable, checksumMismatch, unsupportedFormat, permissionDenied

    var code: String {
        switch self {
        case .notFound: return "not_found"
        case .icloudUnavailable: return "icloud_unavailable"
        case .checksumMismatch: return "checksum_mismatch"
        case .unsupportedFormat: return "unsupported_format"
        case .permissionDenied: return "permission_denied"
        }
    }
}
