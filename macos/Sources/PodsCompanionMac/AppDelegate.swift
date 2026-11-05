import AppKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var controller: PodsController?
    private var menu: StatusMenu?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let controller = PodsController()
        self.controller = controller
        menu = StatusMenu(controller: controller)
        controller.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        controller?.stop()
    }
}
