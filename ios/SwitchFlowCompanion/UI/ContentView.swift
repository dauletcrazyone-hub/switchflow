import SwiftUI

// Same palette as the Windows app.
extension Color {
    static let sfBg = Color(red: 8 / 255, green: 11 / 255, blue: 18 / 255)
    static let sfSurface = Color(red: 25 / 255, green: 34 / 255, blue: 49 / 255)
    static let sfAccent = Color(red: 71 / 255, green: 139 / 255, blue: 255 / 255)
    static let sfAccent2 = Color(red: 117 / 255, green: 200 / 255, blue: 255 / 255)
    static let sfSuccess = Color(red: 53 / 255, green: 201 / 255, blue: 138 / 255)
    static let sfWarning = Color(red: 242 / 255, green: 180 / 255, blue: 80 / 255)
    static let sfDanger = Color(red: 255 / 255, green: 107 / 255, blue: 107 / 255)
    static let sfText2 = Color(red: 156 / 255, green: 170 / 255, blue: 192 / 255)
}

/// `LocalizedStringKey("role_\(x)")` builds the format key "role_%@" and shows the raw key on screen;
/// a runtime key has to be passed as a plain String.
func localized(_ key: String) -> LocalizedStringKey { LocalizedStringKey(key) }

struct ContentView: View {
    @EnvironmentObject var c: CompanionController
    @EnvironmentObject var d: DirectController
    @State private var scanning = false
    /// The PC screen is open (from the home screen's "Windows PC" card).
    @State private var pcOpen = false
    /// First start: the "allow everything" screen until it is answered once.
    @State private var allowAll = !UserDefaults.standard.bool(forKey: "all_asked") && AllowAllView.needed
    @State private var confirmRemove = false

    var body: some View {
        ZStack {
            Color.sfBg.ignoresSafeArea()
            RadialGradient(colors: [Color.sfAccent.opacity(0.18), .clear], center: .topTrailing, startRadius: 10, endRadius: 420)
                .ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    header
                    if allowAll {
                        AllowAllView {
                            UserDefaults.standard.set(true, forKey: "all_asked")
                            allowAll = false
                        }
                    } else if d.stage != .idle {
                        DirectView(scan: { scanning = true })
                            .transition(.opacity.combined(with: .move(edge: .trailing)))
                    } else if pcOpen || c.phase != .idle {
                        if c.phase == .idle {
                            Button { pcOpen = false; c.message = nil } label: {
                                Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold))
                                    .frame(width: 42, height: 42).background(Color.sfSurface, in: Circle())
                            }
                            .buttonStyle(PressableStyle())
                        }
                        Group {
                            switch c.phase {
                            case .idle: welcome
                            case .connecting: waiting(String(localized: "connecting \(c.desktopName ?? "PC")"), code: nil)
                            case .awaitingApproval: waiting(String(localized: "waiting_approval"), code: c.verificationCode)
                            case .connected, .reconnecting: connected
                            }
                        }
                        .transition(.asymmetric(insertion: .opacity.combined(with: .offset(y: 12)), removal: .opacity))
                    } else {
                        HomeView(openPC: { pcOpen = true }, scan: { scanning = true })
                            .transition(.opacity)
                    }
                    addedContactsCard
                }
                .padding(22)
                .animation(.spring(response: 0.42, dampingFraction: 0.86), value: c.phase)
                .animation(.spring(response: 0.42, dampingFraction: 0.86), value: d.stage)
                .animation(.spring(response: 0.42, dampingFraction: 0.86), value: pcOpen)
            }
        }
        .foregroundStyle(.white)
        .preferredColorScheme(.dark)
        .sheet(isPresented: $scanning) {
            ZStack(alignment: .bottom) {
                QRScannerView { code in
                    scanning = false
                    route(code)
                }
                .ignoresSafeArea()
                Text("scan_prompt_any").font(.callout.weight(.medium)).padding(14)
                    .background(.ultraThinMaterial, in: Capsule()).padding(.bottom, 40)
            }
        }
        .sheet(isPresented: $c.askingDocuments, onDismiss: { c.documentsPicked([]) }) {
            DocumentPicker { urls in c.documentsPicked(urls) }
        }
        .background(EmptyView().sheet(isPresented: $d.askingDocuments, onDismiss: { d.documentsPicked([]) }) {
            DocumentPicker { urls in d.documentsPicked(urls) }
        })
        .fullScreenCover(isPresented: Binding(get: { d.picking != nil }, set: { if !$0 { d.picking = nil } })) {
            if let c = d.picking {
                ZStack {
                    Color.sfBg.ignoresSafeArea()
                    MediaPickerView(category: c, items: d.media(c), albums: d.albums(c), initial: d.picked[c],
                                    onDone: { d.setPicked(c, $0) }, onBack: { d.picking = nil })
                }
                .foregroundStyle(.white)
                .preferredColorScheme(.dark)
            }
        }
    }

    /// One scanner for both kinds of code: another phone's, or the PC's.
    private func route(_ code: String) {
        if DirectQR.isDirect(code) {
            d.connect(code)
        } else if code.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("switchflow://pair") {
            if d.stage != .idle { d.leave() }
            pcOpen = true
            c.onScanned(code)
        } else if d.stage != .idle {
            d.connect(code)
        } else {
            c.onScanned(code)
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Image(systemName: "point.3.connected.trianglepath.dotted")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(LinearGradient(colors: [.sfAccent, .sfAccent2], startPoint: .bottomLeading, endPoint: .topTrailing))
                .frame(width: 38, height: 38)
                .background(Color.sfSurface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text("SwitchFlow").font(.title3.weight(.semibold))
                Text("slogan").font(.caption).foregroundStyle(Color.sfText2)
            }
        }
    }

    private var welcome: some View {
        VStack(spacing: 18) {
            PulsingPhone().frame(height: 170)
            Text("connect").font(.system(size: 28, weight: .semibold)).multilineTextAlignment(.center)
            Text("connect_hint").font(.body).foregroundStyle(Color.sfText2).multilineTextAlignment(.center)
            PrimaryButton(title: "connect", icon: "qrcode.viewfinder") {
                c.message = nil
                scanning = true
            }
            if let m = c.message {
                Text(localized("msg_" + m.rawValue)).font(.callout).padding(16).frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.sfDanger.opacity(0.15), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            Label("secure", systemImage: "lock.fill").font(.caption.weight(.medium)).foregroundStyle(Color.sfSuccess)
        }
        .frame(maxWidth: .infinity)
    }

    private func waiting(_ title: String, code: String?) -> some View {
        VStack(spacing: 18) {
            if let code {
                Text("compare_code").font(.body).foregroundStyle(Color.sfText2).multilineTextAlignment(.center)
                Text(verbatim: "\(code.prefix(3)) \(code.suffix(3))").font(.system(size: 44, weight: .semibold, design: .rounded)).monospacedDigit().kerning(6)
            }
            ProgressView().tint(.sfAccent)
            Text(title).font(.callout).foregroundStyle(Color.sfText2).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 24)
    }

    private func card<Content: View>(tint: Color = .sfSurface, @ViewBuilder _ content: () -> Content) -> some View {
        content().padding(20).frame(maxWidth: .infinity, alignment: .leading)
            .background(tint, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var connected: some View {
        VStack(spacing: 14) {
            card {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 10) {
                        Circle().fill(c.phase == .connected ? Color.sfSuccess : Color.sfWarning).frame(width: 10, height: 10)
                        Text(c.phase == .connected ? String(localized: "connected_to \(c.desktopName ?? "PC")") : String(localized: "reconnecting"))
                            .font(.headline)
                    }
                    Text(localized("role_" + c.role)).font(.callout).foregroundStyle(Color.sfText2)
                    Label(c.viaCable ? "secure_usb" : "secure_wifi", systemImage: "lock.fill").font(.caption).foregroundStyle(Color.sfSuccess)
                }
            }
            if let cats = c.askingAccess {
                card(tint: Color.sfAccent.opacity(0.18)) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("access_title").font(.headline)
                        Text(cats.map { NSLocalizedString("cat_\($0)", comment: "") }.joined(separator: ", ")).font(.callout)
                        PrimaryButton(title: "allow") { c.allowAccessTapped() }
                    }
                }
            }
            if let t = c.transfer {
                card {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(transferTitle(t)).font(.headline)
                        if t.itemsTotal > 0 {
                            ProgressView(value: Double(t.itemsDone), total: Double(max(t.itemsTotal, 1))).tint(.sfAccent)
                            Text("\(t.itemsDone) / \(t.itemsTotal)").font(.callout).monospacedDigit()
                        } else if t.finished == nil {
                            ProgressView().tint(.sfAccent)
                            Text("\(t.itemsDone)").font(.callout).monospacedDigit()
                        }
                        if let name = t.currentName { Text(name).font(.caption).foregroundStyle(Color.sfText2).lineLimit(1) }
                        if t.finished == nil { Text("keep_open").font(.caption).foregroundStyle(Color.sfWarning) }
                    }
                }
            }
            Text("originals_safe").font(.caption).foregroundStyle(Color.sfText2)
            SecondaryButton(title: "disconnect", color: .sfDanger) { c.disconnect() }
                .padding(.top, 6)
        }
    }

    /// One tap removes the contacts SwitchFlow created here, after a confirmation.
    @ViewBuilder private var addedContactsCard: some View {
        if c.addedContacts > 0 || c.removedContacts != nil || c.removeFailed {
            card {
                VStack(alignment: .leading, spacing: 10) {
                    if c.addedContacts > 0 {
                        Text("added_contacts \(c.addedContacts)").font(.headline)
                        Text("added_contacts_hint").font(.caption).foregroundStyle(Color.sfText2)
                        Button(role: .destructive) { confirmRemove = true } label: { Text("remove_added") }
                            .buttonStyle(.bordered)
                    }
                    if let n = c.removedContacts { Text("removed_contacts \(n)").font(.callout).foregroundStyle(Color.sfSuccess) }
                    if c.removeFailed { Text("remove_failed").font(.callout).foregroundStyle(Color.sfDanger) }
                }
            }
            .confirmationDialog(Text("remove_added_confirm \(c.addedContacts)"), isPresented: $confirmRemove, titleVisibility: .visible) {
                Button(role: .destructive) { c.removeAddedContacts() } label: { Text("remove_added") }
            }
        }
    }

    private func transferTitle(_ t: TransferUI) -> LocalizedStringKey {
        switch t.finished {
        case "done": return "done"
        case "cancelled": return "cancelled"
        default: return t.paused ? "paused" : (t.sending ? "sending" : "receiving")
        }
    }
}

private struct PulsingPhone: View {
    @State private var pulse = false
    var body: some View {
        ZStack {
            Circle().stroke(Color.sfAccent.opacity(pulse ? 0 : 0.5), lineWidth: 2)
                .frame(width: 120, height: 120).scaleEffect(pulse ? 1.35 : 0.85)
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(Color.sfText2, style: StrokeStyle(lineWidth: 2.5, dash: [7, 6]))
                .frame(width: 52, height: 104)
        }
        .onAppear { withAnimation(.easeOut(duration: 2.2).repeatForever(autoreverses: false)) { pulse = true } }
    }
}
