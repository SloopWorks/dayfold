import SwiftUI
import client

// ADR 0044 Phase B — the iOS app host. S0: minimal shell that renders the shared Compose
// MainViewController (launches to the AUTH-S5 sign-in gate; the busy-family session + notification
// glue are wired in S1). The process-global notification glue (UN/CL delegates + BGTaskScheduler
// register/submit) is installed from AppDelegate in S1/S3 — delegates MUST be set on the main thread
// in didFinishLaunching (incl. the background-launch path) or CL/UN callbacks silently never fire.
@main
struct DayfoldApp: App {
  @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
  var body: some Scene {
    WindowGroup {
      ContentView().ignoresSafeArea()
    }
  }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    // Main thread. Sets the (retained) UN delegate, warms the shared ContentStore, requests notif auth.
    // Geofence CL delegate + BGTaskScheduler.register/submit are added in S3.
    IosNotifGlue.shared.start()
    // S2 verification scaffold — arm an exact local notification ~15s out (UNTimeIntervalNotificationTrigger).
    // Removed once the settings toggle drives the real reconcileExactSchedules path (S4).
    IosNotifGlue.shared.debugScheduleTest()
    return true
  }
}
