import SwiftUI

@main
struct SwitchFlowCompanionApp: App {
    @StateObject private var controller = CompanionController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(controller)
                .task { controller.start() }
                .onChange(of: scenePhase) { phase in
                    controller.foreground = phase == .active
                }
        }
    }
}
