import SwiftUI
import BackgroundTasks
import FirebaseCore
import GoogleSignIn
import client

// ADR 0044 Phase B — the iOS app host. Renders the shared Compose MainViewController; installs the
// process-global notification glue (UN/CL delegates) + the BGTaskScheduler reconcile lane. Delegates are
// set on the main thread in didFinishLaunching (incl. the background-launch path) or CL/UN callbacks
// would silently never fire.
@main
struct DayfoldApp: App {
  @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
  var body: some Scene {
    WindowGroup {
      ContentView(authHost: appDelegate.authCoordinator).ignoresSafeArea()
    }
  }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
  // Must match Info.plist BGTaskSchedulerPermittedIdentifiers.
  private let bgTaskId = "com.sloopworks.dayfold.now.refresh"
  let authCoordinator = AuthCoordinator()

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    FirebaseApp.configure()
    // ADR 0020 R3 — the BGTask now also drives the headless refresh pass (sync + reconcile), not
    // reconcile alone. register() MUST happen before didFinishLaunching returns.
    BGTaskScheduler.shared.register(forTaskWithIdentifier: bgTaskId, using: nil) { [weak self] task in
      // Review round 2 — expirationHandler and bgRefresh's completion callback can each fire on a
      // different thread with no ordering guarantee between them. Without a shared guard, both
      // could call task.setTaskCompleted: BGTaskScheduler defines calling it more than once as API
      // misuse, and it crashes. completionGuard.complete lets whichever fires first win and makes
      // the other a no-op.
      let completionGuard = TaskCompletionGuard()

      // iOS grants ~30s. Without an expirationHandler an overrun kills the app AND reduces how
      // often the system schedules this task afterwards — a self-inflicted freshness penalty.
      task.expirationHandler = {
        IosBackgroundNotifyKt.bgCancelRefresh()
        completionGuard.complete { task.setTaskCompleted(success: false) }
      }
      // bgRefresh is ASYNC. Completing the task here would let iOS suspend the app before
      // the work finished — silently, with no error and no log. Complete in the callback.
      IosBackgroundNotifyKt.bgRefresh {
        self?.submitReconcile()             // re-arm the next opportunistic run
        completionGuard.complete { task.setTaskCompleted(success: true) }
      }
    }

    // Main thread. Sets the (retained) UN + CL delegates, warms the shared ContentStore, requests notif auth.
    IosNotifGlue.shared.start()
    submitReconcile()

    #if DEBUG
    // DEBUG/sim affordance — auto-enable device-local proximity (drives the permission ladder + registers
    // geofences for the seeded place) so a simulator location crossing fires the pass without navigating
    // the settings toggle. Absent in release/TestFlight, where proximity is opt-in via Settings only.
    // Slight delay lets MainViewController seed the ContentStore first.
    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
      IosNotifGlue.shared.debugEnableProximity()
    }
    #endif
    return true
  }

  func application(
    _ app: UIApplication,
    open url: URL,
    options: [UIApplication.OpenURLOptionsKey: Any] = [:]
  ) -> Bool {
    GIDSignIn.sharedInstance.handle(url)
  }

  func applicationDidEnterBackground(_ application: UIApplication) {
    submitReconcile()
  }

  private func submitReconcile() {
    let request = BGAppRefreshTaskRequest(identifier: bgTaskId)
    request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
    try? BGTaskScheduler.shared.submit(request)
  }
}

// Review round 2 — ensures a BGTask's `setTaskCompleted` runs at most once even when the
// `expirationHandler` and an async completion callback race on different threads. Kotlin's own
// `isActive`-based guard was tried first and rejected: it read the coroutine's Job state on one
// thread while `expirationHandler` could fire on another with no synchronization between them, so
// it could not reliably prevent a double call. A lock-guarded flag here is the actual fix —
// `BGTaskScheduler` documents calling `setTaskCompleted` more than once as API misuse, and it
// crashes, so this must be robust rather than merely "usually correct."
private final class TaskCompletionGuard {
  private let lock = NSLock()
  private var completed = false

  /// Runs [action] only the first time this is called; every later call is a no-op.
  func complete(_ action: () -> Void) {
    lock.lock()
    defer { lock.unlock() }
    guard !completed else { return }
    completed = true
    action()
  }
}
