import SwiftUI
import client

// Bridges the shared Compose UI (client.framework MainViewController) into SwiftUI. Kotlin's
// top-level MainViewController() is exposed to Swift under the file-class name MainViewControllerKt.
struct ContentView: UIViewControllerRepresentable {
  func makeUIViewController(context: Context) -> UIViewController {
    MainViewControllerKt.MainViewController()
  }
  func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
