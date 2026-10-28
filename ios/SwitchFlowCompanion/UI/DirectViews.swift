import SwiftUI

/// Press feedback for every tappable thing: a springy shrink and a light haptic tick.
struct PressableStyle: ButtonStyle {
    var scale: CGFloat = 0.95

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .brightness(configuration.isPressed ? -0.04 : 0)
            .animation(.spring(response: 0.28, dampingFraction: 0.55), value: configuration.isPressed)
            .onChange(of: configuration.isPressed) { pressed in
                if pressed { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
            }
    }
}

let accentGradient = LinearGradient(colors: [.sfAccent, .sfAccent2], startPoint: .leading, endPoint: .trailing)

struct PrimaryButton: View {
    let title: LocalizedStringKey
    var icon: String?
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if let icon { Image(systemName: icon).font(.system(size: 18, weight: .semibold)) }
                Text(title).font(.headline)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 18)
            .background(accentGradient, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .foregroundStyle(.white)
            .opacity(enabled ? 1 : 0.4)
        }
        .buttonStyle(PressableStyle(scale: 0.97))
        .disabled(!enabled)
    }
}

struct SecondaryButton: View {
    let title: LocalizedStringKey
    var color: Color = .white
    var icon: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon { Image(systemName: icon) }
                Text(title).font(.body.weight(.medium))
            }
            .frame(maxWidth: .infinity).padding(.vertical, 15)
            .background(Color.sfSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .foregroundStyle(color)
        }
        .buttonStyle(PressableStyle(scale: 0.97))
    }
}

func sfCard<Content: View>(tint: Color = .sfSurface, @ViewBuilder _ content: () -> Content) -> some View {
    content().padding(18).frame(maxWidth: .infinity, alignment: .leading)
        .background(tint, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
}

struct IconBadge: View {
    let icon: String
    let color: Color
    var size: CGFloat = 44

    var body: some View {
        Image(systemName: icon).font(.system(size: size * 0.42, weight: .semibold)).foregroundStyle(color)
            .frame(width: size, height: size).background(color.opacity(0.16), in: Circle())
    }
}

struct CheckDot: View {
    let checked: Bool
    var enabled = true

    var body: some View {
        ZStack {
            Circle().strokeBorder(checked ? Color.sfAccent : Color.sfText2.opacity(0.5), lineWidth: 2)
                .background(Circle().fill(checked ? Color.sfAccent : .clear))
            Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
                .scaleEffect(checked ? 1 : 0.3).opacity(checked ? 1 : 0)
        }
        .frame(width: 26, height: 26)
        .opacity(enabled ? 1 : 0.35)
        .animation(.spring(response: 0.3, dampingFraction: 0.5), value: checked)
    }
}

func categoryIcon(_ c: String) -> String {
    switch c {
    case Category.photos: return "photo.fill"
    case Category.videos: return "video.fill"
    case Category.contacts: return "person.crop.circle.fill"
    case Category.calendars: return "calendar"
    case Category.music: return "music.note"
    default: return "doc.fill"
    }
}

func categoryColor(_ c: String) -> Color {
    switch c {
    case Category.photos: return Color(red: 0.31, green: 0.55, blue: 1)
    case Category.videos: return Color(red: 0.71, green: 0.43, blue: 1)
    case Category.contacts: return .sfSuccess
    case Category.calendars: return Color(red: 1, green: 0.48, blue: 0.35)
    case Category.music: return Color(red: 1, green: 0.36, blue: 0.58)
    default: return .sfWarning
    }
}

func byteString(_ n: Int64) -> String { ByteCountFormatter.string(fromByteCount: max(0, n), countStyle: .file) }

// MARK: Home

struct HomeView: View {
    @EnvironmentObject var d: DirectController
    let openPC: () -> Void
    let scan: () -> Void
    @State private var float = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("home_title").font(.system(size: 30, weight: .semibold))
            Text("home_subtitle").font(.body).foregroundStyle(Color.sfText2)
            HStack(spacing: 12) {
                bigAction("send", hint: "send_hint", icon: "arrow.up", color: .sfAccent) { d.startSend(); scan() }
                bigAction("receive", hint: "receive_hint", icon: "arrow.down", color: .sfSuccess) { d.startReceive() }
            }
            Label("via_qr_ios", systemImage: "wifi").font(.caption.weight(.medium)).foregroundStyle(Color.sfAccent2)
                .padding(.horizontal, 12).padding(.vertical, 7).background(Color.sfAccent2.opacity(0.14), in: Capsule())
            Button(action: openPC) {
                HStack(spacing: 14) {
                    IconBadge(icon: "desktopcomputer", color: .sfAccent2)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("pc_title").font(.headline)
                        Text("pc_hint").font(.callout).foregroundStyle(Color.sfText2)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(Color.sfText2)
                }
                .padding(18).background(Color.sfSurface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            }
            .buttonStyle(PressableStyle(scale: 0.97))
            Label("direct_secure", systemImage: "lock.fill").font(.caption.weight(.medium)).foregroundStyle(Color.sfSuccess)
                .padding(.horizontal, 6)
        }
        .onAppear { withAnimation(.easeInOut(duration: 1.8).repeatForever()) { float = true } }
    }

    private func bigAction(_ title: LocalizedStringKey, hint: LocalizedStringKey, icon: String, color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 0) {
                Image(systemName: icon).font(.system(size: 26, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 60, height: 60)
                    .background(LinearGradient(colors: [color, color.opacity(0.65)], startPoint: .topLeading, endPoint: .bottomTrailing),
                                in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .offset(y: float ? -5 : 5)
                Spacer().frame(height: 18)
                Text(title).font(.system(size: 20, weight: .semibold))
                Text(hint).font(.callout).foregroundStyle(Color.sfText2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18).background(Color.sfSurface, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        }
        .buttonStyle(PressableStyle(scale: 0.94))
    }
}

// MARK: Phone to phone

struct DirectView: View {
    @EnvironmentObject var d: DirectController
    let scan: () -> Void
    @State private var confirmCancel = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if d.stage != .transferring && d.stage != .reconnecting {
                HStack(spacing: 12) {
                    Button { d.leave() } label: {
                        Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold))
                            .frame(width: 42, height: 42).background(Color.sfSurface, in: Circle())
                    }
                    .buttonStyle(PressableStyle())
                    Text(LocalizedStringKey(d.role == DirectIntent.receive ? "receive" : "send")).font(.title2.weight(.semibold))
                }
            }
            if let cats = d.askingAccess {
                sfCard(tint: Color.sfAccent.opacity(0.18)) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("access_title_direct").font(.headline)
                        Text(cats.map { NSLocalizedString("cat_\($0)", comment: "") }.joined(separator: ", ")).font(.callout)
                        PrimaryButton(title: "allow") { d.allowAccessTapped() }
                    }
                }
            }
            Group {
                switch d.stage {
                case .waiting: if d.role == DirectIntent.receive { receiveCode } else { scanPrompt }
                case .idle, .connecting: connecting
                case .choosing: if d.role == DirectIntent.send { choose } else { waitForSender }
                case .transferring, .reconnecting: transfer
                case .finished: finished
                case .failed: failed
                }
            }
            .transition(.asymmetric(insertion: .opacity.combined(with: .move(edge: .trailing)), removal: .opacity))
        }
        .animation(.spring(response: 0.45, dampingFraction: 0.85), value: d.stage)
        .alert(Text("cancel_confirm"), isPresented: $confirmCancel) {
            Button(role: .destructive) { d.cancel() } label: { Text("cancel") }
            Button(role: .cancel) {} label: { Text("keep_going") }
        } message: { Text("cancel_confirm_body") }
    }

    private var receiveCode: some View {
        VStack(spacing: 18) {
            if let img = d.qr {
                QRBox(image: img)
                Text("qr_title").font(.title3.weight(.semibold))
                Text("qr_hint").font(.callout).foregroundStyle(Color.sfText2).multilineTextAlignment(.center)
            } else {
                sfCard(tint: Color.sfWarning.opacity(0.15)) {
                    VStack(alignment: .leading, spacing: 10) {
                        IconBadge(icon: "wifi.slash", color: .sfWarning)
                        Text("no_network_title").font(.headline)
                        Text("no_network_body").font(.callout)
                        SecondaryButton(title: "refresh", icon: "arrow.clockwise") { d.startReceive() }
                    }
                }
            }
            hint("wifi", "qr_network")
            if d.qr != nil { Button("refresh_code") { d.startReceive() }.font(.callout).tint(.sfAccent2) }
        }
        .frame(maxWidth: .infinity)
    }

    private func hint(_ icon: String, _ text: LocalizedStringKey) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(Color.sfAccent2)
            Text(text).font(.callout).foregroundStyle(Color.sfText2)
            Spacer(minLength: 0)
        }
        .padding(14).background(Color.sfSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var scanPrompt: some View {
        VStack(spacing: 16) {
            TwoPhones().frame(height: 120)
            Text("scan_title").font(.title3.weight(.semibold)).multilineTextAlignment(.center)
            Text("scan_body").font(.callout).foregroundStyle(Color.sfText2).multilineTextAlignment(.center)
            PrimaryButton(title: "scan_qr", icon: "qrcode.viewfinder", action: scan)
        }
        .frame(maxWidth: .infinity)
    }

    private var connecting: some View {
        VStack(spacing: 18) {
            TwoPhones().frame(height: 120)
            Text("connecting_direct").font(.headline)
        }
        .frame(maxWidth: .infinity).padding(.top, 30)
    }

    @ViewBuilder private var peerChip: some View {
        if let p = d.peer {
            HStack(spacing: 12) {
                Circle().fill(d.stage == .reconnecting ? Color.sfWarning : Color.sfSuccess).frame(width: 10, height: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.name).font(.headline).lineLimit(1)
                    Text(verbatim: "\(p.model) · \(p.platform == "ios" ? "iOS" : "Android") \(p.os)").font(.caption).foregroundStyle(Color.sfText2).lineLimit(1)
                }
                Spacer()
                Label("via_wifi", systemImage: "wifi").font(.caption.weight(.medium)).foregroundStyle(Color.sfSuccess)
                    .padding(.horizontal, 10).padding(.vertical, 6).background(Color.sfSuccess.opacity(0.14), in: Capsule())
            }
            .padding(14).background(Color.sfSurface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
    }

    private var waitForSender: some View {
        VStack(spacing: 22) {
            peerChip
            TwoPhones().frame(height: 120)
            Text("waiting_sender \(d.peer?.name ?? "")").font(.headline).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }

    private var choose: some View {
        VStack(alignment: .leading, spacing: 10) {
            peerChip
            Text("choose_title").font(.title3.weight(.semibold)).padding(.top, 4)
            if d.needsAccess {
                sfCard(tint: Color.sfAccent.opacity(0.15)) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("read_access_title").font(.headline)
                        Text("read_access_body_ios").font(.callout)
                        PrimaryButton(title: "allow") { d.requestReadAccess() }
                    }
                }
            }
            if d.scanning && d.categories.isEmpty {
                ProgressView().tint(.sfAccent).frame(maxWidth: .infinity).padding(30)
            }
            ForEach(d.categories) { row in
                Button { d.toggle(row.category) } label: { categoryRow(row) }
                    .buttonStyle(PressableStyle(scale: 0.97))
                    .disabled(row.count == 0 && row.category != Category.documents)
            }
            if d.peer?.platform == "android" {
                Text("apps_from_iphone").font(.caption).foregroundStyle(Color.sfText2).padding(.horizontal, 6)
            }
            Group {
                if d.selectedCount > 0 {
                    PrimaryButton(title: "send_btn \(byteString(d.selectedBytes))", icon: "arrow.up") { d.send() }
                } else {
                    PrimaryButton(title: "nothing_selected", icon: "arrow.up", enabled: false) {}
                }
            }
            .padding(.top, 6)
            Text("originals_safe").font(.caption).foregroundStyle(Color.sfText2).padding(.horizontal, 6)
        }
    }

    private func categoryRow(_ row: CategoryRow) -> some View {
        let subtitle: String
        if row.permission == PermissionState.denied { subtitle = String(localized: "no_access") }
        else if row.category == Category.documents && row.count == 0 { subtitle = String(localized: "docs_hint") }
        else if row.count == 0 { subtitle = String(localized: "empty") }
        else if row.category == Category.contacts || row.category == Category.calendars { subtitle = "\(row.count)" }
        else { subtitle = "\(row.count) · \(byteString(row.bytes))" }
        return HStack(spacing: 14) {
            IconBadge(icon: categoryIcon(row.category), color: categoryColor(row.category))
            VStack(alignment: .leading, spacing: 2) {
                Text(localized("cat_" + row.category)).font(.headline)
                Text(subtitle).font(.callout).foregroundStyle(Color.sfText2)
            }
            Spacer()
            CheckDot(checked: row.selected, enabled: row.count > 0 || row.category == Category.documents)
        }
        .padding(14)
        .background(row.selected ? categoryColor(row.category).opacity(0.12) : Color.sfSurface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .foregroundStyle(.white)
    }

    private var transfer: some View {
        let p = d.progress
        let fraction = p.bytesTotal > 0 ? Double(p.bytesDone) / Double(p.bytesTotal) : (p.itemsTotal > 0 ? Double(p.itemsDone) / Double(p.itemsTotal) : 0)
        return VStack(spacing: 16) {
            peerChip
            ProgressRing(fraction: fraction) {
                VStack(spacing: 2) {
                    Text(verbatim: "\(Int(fraction * 100))%").font(.system(size: 46, weight: .semibold, design: .rounded)).monospacedDigit()
                    Text(LocalizedStringKey(d.role == DirectIntent.send ? "sending" : "receiving")).font(.callout).foregroundStyle(Color.sfText2)
                }
            }
            .frame(width: 250, height: 250).padding(.vertical, 8)
            Text(verbatim: "\(p.itemsDone) / \(p.itemsTotal)").font(.headline).monospacedDigit()
            Text(verbatim: bytesLine(p)).font(.callout).foregroundStyle(Color.sfText2).monospacedDigit()
            if let name = p.current, let cat = p.currentCategory {
                HStack(spacing: 12) {
                    IconBadge(icon: categoryIcon(cat), color: categoryColor(cat), size: 34)
                    Text(name).font(.callout).lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(12).background(Color.sfSurface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .transition(.opacity)
            }
            if d.stage == .reconnecting {
                sfCard(tint: Color.sfWarning.opacity(0.16)) { Text("reconnecting_direct").font(.callout) }
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            if p.failed > 0 { Text("failed_count \(p.failed)").font(.caption).foregroundStyle(Color.sfDanger) }
            Text("keep_open_direct").font(.caption).foregroundStyle(Color.sfWarning).multilineTextAlignment(.center)
            SecondaryButton(title: "cancel", color: .sfDanger) { confirmCancel = true }
        }
        .frame(maxWidth: .infinity)
    }

    private func bytesLine(_ p: DirectProgress) -> String {
        var s = String(format: NSLocalizedString("progress_bytes", comment: ""), byteString(p.bytesDone), byteString(p.bytesTotal))
        if p.bytesPerSec > 0 {
            s += " · " + String(format: NSLocalizedString("speed", comment: ""), byteString(p.bytesPerSec))
            if p.bytesTotal > p.bytesDone {
                let sec = Double(p.bytesTotal - p.bytesDone) / Double(p.bytesPerSec)
                let f = DateComponentsFormatter()
                f.allowedUnits = sec >= 3600 ? [.hour, .minute] : sec >= 60 ? [.minute] : [.second]
                f.unitsStyle = .short
                s += " · ~" + (f.string(from: max(1, sec)) ?? "")
            }
        }
        return s
    }

    private var finished: some View {
        VStack(spacing: 16) {
            SuccessMark().frame(width: 120, height: 120)
            Text(LocalizedStringKey(d.role == DirectIntent.send ? "finished_send" : "finished_receive")).font(.system(size: 28, weight: .semibold))
            if let p = d.peer {
                Group {
                    if d.role == DirectIntent.send { Text("to_peer \(p.name)") } else { Text("from_peer \(p.name)") }
                }
                .font(.callout).foregroundStyle(Color.sfText2)
            }
            if let r = d.result {
                HStack(spacing: 10) {
                    stat(r.saved, "stat_saved", .sfSuccess)
                    stat(r.skipped, "stat_skipped", .sfAccent2)
                    stat(r.failed, "stat_failed", r.failed > 0 ? .sfDanger : .sfText2)
                }
            }
            PrimaryButton(title: "done_btn") { d.leave() }.padding(.top, 8)
        }
        .frame(maxWidth: .infinity)
    }

    private func stat(_ n: Int, _ label: LocalizedStringKey, _ color: Color) -> some View {
        VStack(spacing: 2) {
            Text(verbatim: "\(n)").font(.system(size: 22, weight: .semibold, design: .rounded)).foregroundStyle(color)
            Text(label).font(.caption).foregroundStyle(Color.sfText2)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 14)
        .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var failed: some View {
        VStack(spacing: 18) {
            Image(systemName: "exclamationmark.circle").font(.system(size: 46)).foregroundStyle(Color.sfDanger)
                .frame(width: 96, height: 96).background(Color.sfDanger.opacity(0.14), in: Circle())
            Text(localized("err_" + (d.error ?? "generic"))).font(.headline).multilineTextAlignment(.center)
            PrimaryButton(title: "try_again") {
                if d.role == DirectIntent.receive { d.startReceive() } else { d.startSend(); scan() }
            }
            SecondaryButton(title: "back") { d.leave() }
        }
        .frame(maxWidth: .infinity).padding(.top, 20)
    }
}

/// QR code on white inside a slowly turning gradient ring.
struct QRBox: View {
    let image: UIImage

    var body: some View {
        // Driven by the clock: a gradient's angle is not something withAnimation interpolates.
        TimelineView(.animation) { ctx in
            let angle = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 6) / 6 * 360
            ZStack {
                RoundedRectangle(cornerRadius: 36, style: .continuous)
                    .fill(AngularGradient(colors: [.sfAccent, .sfAccent2, .sfSuccess, .sfAccent], center: .center, angle: .degrees(angle)))
                Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                    .padding(22).background(Color.white, in: RoundedRectangle(cornerRadius: 32, style: .continuous)).padding(5)
            }
        }
        .frame(width: 280, height: 280)
    }
}

struct ProgressRing<Content: View>: View {
    let fraction: Double
    @ViewBuilder let content: () -> Content
    @State private var spin = false

    var body: some View {
        ZStack {
            Circle().stroke(Color.sfSurface, lineWidth: 14)
            Circle().trim(from: 0, to: min(max(fraction, 0), 1))
                .stroke(AngularGradient(colors: [.sfAccent, .sfAccent2, .sfAccent], center: .center), style: StrokeStyle(lineWidth: 14, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.easeOut(duration: 0.6), value: fraction)
            Circle().fill(Color.white.opacity(0.55)).frame(width: 6, height: 6)
                .offset(y: -118).rotationEffect(.degrees(spin ? 360 : 0))
            content()
        }
        .onAppear { withAnimation(.linear(duration: 2.4).repeatForever(autoreverses: false)) { spin = true } }
    }
}

struct SuccessMark: View {
    @State private var circle: CGFloat = 0
    @State private var tick: CGFloat = 0

    var body: some View {
        GeometryReader { g in
            let w = g.size.width
            ZStack {
                Circle().fill(Color.sfSuccess.opacity(0.14)).scaleEffect(circle)
                Circle().trim(from: 0, to: circle).stroke(Color.sfSuccess, style: StrokeStyle(lineWidth: w * 0.05, lineCap: .round))
                    .rotationEffect(.degrees(-90)).padding(w * 0.06)
                Path { p in
                    p.move(to: CGPoint(x: w * 0.30, y: w * 0.52))
                    p.addLine(to: CGPoint(x: w * 0.45, y: w * 0.66))
                    p.addLine(to: CGPoint(x: w * 0.71, y: w * 0.38))
                }
                .trim(from: 0, to: tick)
                .stroke(Color.sfSuccess, style: StrokeStyle(lineWidth: w * 0.07, lineCap: .round, lineJoin: .round))
            }
        }
        .onAppear {
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            withAnimation(.easeOut(duration: 0.5)) { circle = 1 }
            withAnimation(.easeOut(duration: 0.35).delay(0.5)) { tick = 1 }
        }
    }
}

/// Two phones with dots hopping between them.
struct TwoPhones: View {
    var body: some View {
        TimelineView(.animation) { ctx in
            let t = CGFloat(ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.4) / 1.4)
            GeometryReader { g in
                let w = min(g.size.width, 220)
                let h = g.size.height
                let x0 = (g.size.width - w) / 2
                ZStack(alignment: .topLeading) {
                    RoundedRectangle(cornerRadius: 12).stroke(Color.sfText2, lineWidth: 2.5)
                        .frame(width: w * 0.22, height: h * 0.9).position(x: x0 + w * 0.11, y: h / 2)
                    RoundedRectangle(cornerRadius: 12).stroke(Color.sfText2, lineWidth: 2.5)
                        .frame(width: w * 0.22, height: h * 0.9).position(x: x0 + w * 0.89, y: h / 2)
                    ForEach(0..<3, id: \.self) { i in
                        let q = (t + CGFloat(i) / 3).truncatingRemainder(dividingBy: 1)
                        Circle().fill(Color.sfAccent).frame(width: 12, height: 12)
                            .opacity(Double(1 - abs(q - 0.5) * 2))
                            .position(x: x0 + w * 0.26 + w * 0.48 * q, y: h / 2)
                    }
                }
            }
        }
    }
}
