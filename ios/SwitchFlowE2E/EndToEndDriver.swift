import XCTest

/// Drives the app during the cloud end-to-end test (.github/workflows/ios.yml, job "e2e").
///
/// The transfer itself is checked on the PC side by desktop/src-tauri/examples/ios_e2e_host.rs.
/// This test only plays the person holding the phone: it opens the app with the pairing code
/// and answers the system permission dialogs, which `simctl privacy grant` no longer pre-answers
/// for full Photos access on recent simulators.
final class EndToEndDriver: XCTestCase {
    /// Buttons a person would tap to let SwitchFlow read and save photos and contacts.
    /// Never "Limit Access…", "Select Contacts" or "Don't Allow".
    private let allowLabels = NSPredicate(format: """
        label IN {'Allow Full Access', 'Allow', 'OK', 'Continue', 'Allow Access', \
        'Разрешить полный доступ', 'Разрешить', 'Продолжить'} OR label BEGINSWITH 'Share All' \
        OR label BEGINSWITH 'Поделиться всеми'
        """)

    func testTransferWithRealCore() throws {
        let env = ProcessInfo.processInfo.environment
        guard let uri = env["SF_PAIRING_URI"], !uri.isEmpty else {
            throw XCTSkip("Runs only inside the cloud end-to-end job (SF_PAIRING_URI is not set).")
        }
        let doneFile = env["SF_DONE_FILE"]
        let timeout = env["SF_TIMEOUT"].flatMap(Double.init) ?? 1500

        let app = XCUIApplication()
        app.launchArguments = ["-SFPairingURI", uri, "-SFSkipAccessCard", "YES",
                               "-AppleLanguages", "(ru)", "-AppleLocale", "ru_RU"]
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

        let deadline = Date().addingTimeInterval(timeout)
        while doneFile.map({ !FileManager.default.fileExists(atPath: $0) }) ?? true {
            if Date() > deadline {
                print("SF-E2E springboard at timeout:\n\(springboard.debugDescription)")
                XCTFail("the PC side did not finish within \(Int(timeout)) s")
                return
            }
            for owner in [springboard, app] {
                let button = owner.buttons.matching(allowLabels).firstMatch
                if button.exists && button.isHittable {
                    print("SF-E2E tapping system button \"\(button.label)\"")
                    button.tap()
                }
            }
            Thread.sleep(forTimeInterval: 1)
        }
        print("SF-E2E the PC side finished")
    }
}
