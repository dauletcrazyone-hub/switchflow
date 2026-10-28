import SwiftUI

@main
struct SwitchFlowCompanionApp: App {
    @StateObject private var controller = CompanionController()
    @StateObject private var direct = DirectController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(controller)
                .environmentObject(direct)
                .task { controller.start() }
                .onChange(of: scenePhase) { phase in
                    controller.foreground = phase == .active
                    if phase == .active { controller.becameActive() }
                }
        }
    }
}
