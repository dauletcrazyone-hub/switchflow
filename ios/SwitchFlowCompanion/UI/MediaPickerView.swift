import AVFoundation
import Contacts
import EventKit
import Photos
import SwiftUI

/// Pick exactly which photos or videos to send: a grid of previews with album filters.
struct MediaPickerView: View {
    let category: String
    let items: [SourceItem]
    let albums: [Album]
    let onDone: (Set<String>) -> Void
    let onBack: () -> Void
    @State private var chosen: Set<String>
    @State private var album: String?

    init(category: String, items: [SourceItem], albums: [Album], initial: Set<String>?, onDone: @escaping (Set<String>) -> Void, onBack: @escaping () -> Void) {
        self.category = category
        self.items = items
        self.albums = albums
        self.onDone = onDone
        self.onBack = onBack
        _chosen = State(initialValue: initial ?? Set(items.map(\.id)))
    }

    private var shown: [SourceItem] { album.map { a in items.filter { $0.albumIds.contains(a) } } ?? items }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button(action: onBack) {
                    Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold))
                        .frame(width: 42, height: 42).background(Color.sfSurface, in: Circle())
                }
                .buttonStyle(PressableStyle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(localized("cat_" + category)).font(.title3.weight(.semibold))
                    Text(verbatim: "\(chosen.count) / \(items.count) · \(byteString(items.filter { chosen.contains($0.id) }.reduce(0) { $0 + $1.size }))")
                        .font(.caption).foregroundStyle(Color.sfText2).monospacedDigit()
                }
                Spacer()
                let allShown = !shown.isEmpty && shown.allSatisfy { chosen.contains($0.id) }
                Button {
                    let ids = Set(shown.map(\.id))
                    if allShown { chosen.subtract(ids) } else { chosen.formUnion(ids) }
                } label: {
                    Text(LocalizedStringKey(allShown ? "clear_all" : "select_all"))
                }
                .font(.callout.weight(.medium)).tint(.sfAccent2)
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            if !albums.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        chip(Text("all_albums"), on: album == nil) { album = nil }
                        ForEach(albums, id: \.id) { a in chip(Text(verbatim: "\(a.name) · \(a.count)"), on: album == a.id) { album = a.id } }
                    }
                    .padding(.horizontal, 16)
                }
                .padding(.bottom, 8)
            }
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 4)], spacing: 4) {
                    ForEach(shown, id: \.id) { item in
                        let on = chosen.contains(item.id)
                        Button {
                            if on { chosen.remove(item.id) } else { chosen.insert(item.id) }
                        } label: {
                            AssetThumb(id: item.id, isVideo: item.category == Category.videos)
                                .aspectRatio(1, contentMode: .fill)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Color.sfAccent, lineWidth: on ? 3 : 0))
                                .scaleEffect(on ? 0.9 : 1)
                                .overlay(alignment: .topTrailing) {
                                    if on { CheckDot(checked: true).padding(6).transition(.scale.combined(with: .opacity)) }
                                }
                                .animation(.spring(response: 0.3, dampingFraction: 0.55), value: on)
                        }
                        .buttonStyle(PressableStyle(scale: 0.92))
                    }
                }
                .padding(12)
            }
            PrimaryButton(title: "picked_done \(chosen.count)") { onDone(chosen) }
                .padding(16)
        }
    }

    private func chip(_ label: Text, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            label.font(.caption.weight(.medium)).lineLimit(1)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(on ? Color.sfAccent : Color.sfSurface, in: Capsule())
                .foregroundStyle(.white)
        }
        .buttonStyle(PressableStyle())
    }
}

/// A square preview of one photo or video from PhotoKit.
struct AssetThumb: View {
    let id: String
    let isVideo: Bool
    @State private var image: UIImage?

    var body: some View {
        Color.sfSurface
            .overlay {
                if let image { Image(uiImage: image).resizable().scaledToFill() }
            }
            .overlay(alignment: .bottomLeading) {
                if isVideo { Image(systemName: "play.fill").font(.caption).foregroundStyle(.white).padding(6) }
            }
            .clipped()
            .task(id: id) { image = await Self.load(id) }
    }

    private static let manager = PHCachingImageManager()

    static func load(_ id: String) async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { return nil }
        let opts = PHImageRequestOptions()
        opts.deliveryMode = .opportunistic
        opts.isNetworkAccessAllowed = true
        opts.resizeMode = .fast
        return await withCheckedContinuation { cont in
            let once = Once()
            manager.requestImage(for: asset, targetSize: CGSize(width: 240, height: 240), contentMode: .aspectFill, options: opts) { img, info in
                // Opportunistic delivery calls twice (quick then sharp); keep the last non-degraded one.
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                if !degraded || img == nil { once.run { cont.resume(returning: img) } }
            }
        }
    }
}

/// First start: one tap asks iOS for everything SwitchFlow can transfer, one dialog after another.
struct AllowAllView: View {
    let done: () -> Void
    @State private var asking = false

    static var needed: Bool {
        PHPhotoLibrary.authorizationStatus(for: .readWrite) == .notDetermined
            || CNContactStore.authorizationStatus(for: .contacts) == .notDetermined
            || EKEventStore.authorizationStatus(for: .event) == .notDetermined
            || AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined
    }

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "checkmark.shield.fill").font(.system(size: 40)).foregroundStyle(Color.sfAccent)
                .frame(width: 88, height: 88).background(Color.sfAccent.opacity(0.16), in: Circle())
            Text("allow_all_title").font(.system(size: 28, weight: .semibold)).multilineTextAlignment(.center)
            Text("allow_all_body").font(.body).foregroundStyle(Color.sfText2).multilineTextAlignment(.center)
            sfCard {
                VStack(alignment: .leading, spacing: 12) {
                    row("photo.fill", categoryColor(Category.photos), "allow_all_media")
                    row("person.crop.circle.fill", categoryColor(Category.contacts), "cat_contacts")
                    row("calendar", categoryColor(Category.calendars), "cat_calendars")
                    row("camera.fill", .sfAccent2, "allow_all_camera")
                }
            }
            Label("allow_all_privacy", systemImage: "lock.fill").font(.caption.weight(.medium)).foregroundStyle(Color.sfSuccess)
            PrimaryButton(title: "allow_all") {
                guard !asking else { return }
                asking = true
                Task {
                    _ = await PhotoLibrary.authorize()
                    _ = await ContactsService.authorize()
                    _ = await CalendarService.authorize()
                    _ = await AVCaptureDevice.requestAccess(for: .video)
                    asking = false
                    done()
                }
            }
            Button("later", action: done).font(.callout).tint(.sfText2)
        }
    }

    private func row(_ icon: String, _ color: Color, _ text: LocalizedStringKey) -> some View {
        HStack(spacing: 12) {
            IconBadge(icon: icon, color: color, size: 36)
            Text(text).font(.body)
        }
    }
}
