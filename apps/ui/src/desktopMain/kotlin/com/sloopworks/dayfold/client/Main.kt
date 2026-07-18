package com.sloopworks.dayfold.client

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.sloopworks.dayfold.client.fake.fakeClientForApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.reduxkotlin.compose.rememberSelectorStore

/** Desktop host: owns one host-safe runtime graph and only retains native platform actions. */
fun main() = application {
  val api = System.getenv("DAYFOLD_API") ?: ""
  val fakeHttp = remember { fakeClientForApi(api) }
  val isFake = fakeHttp != null
  val clientApi = if (isFake) "http://fake.local" else api
  val devSecret = if (isFake) "fake" else System.getenv("DEV_AUTH_SECRET")
  val driver = remember { DriverFactory().createDriver() }
  val contentStore = remember(driver) { ContentStore(driver) }
  val graph = remember {
    try {
      DayfoldRuntimeFactory(
        api = clientApi,
        contentStore = contentStore,
        tokenStore = FileTokenStore(File(System.getProperty("user.home"), ".dayfold/session.json")),
        notificationContext = mainNotificationContext(),
        httpClientFactory = { fakeHttp ?: io.ktor.client.HttpClient() },
        devSecret = devSecret,
        onResourcesClosed = driver::close,
      ).create()
    } catch (error: Throwable) {
      driver.close()
      throw error
    }
  }
  val platformActions = remember { com.sloopworks.dayfold.client.cards.PlatformActions() }
  val selectorStore = rememberSelectorStore(graph.store)
  val stablePlatformActions = remember(platformActions, devSecret) {
    StablePlatformActions(
      platformActions = platformActions,
      onSignIn = { provider ->
        // Desktop has no native provider UI. Only an explicitly configured dev backend may sign in.
        if (devSecret != null) graph.commands.signInWithDevProvider(provider)
      },
      onDevSignIn = graph.commands::devSignIn,
    )
  }
  val shutdownOwner = remember(graph) {
    DesktopShutdownOwner(
      cancelRuntime = graph::cancel,
      awaitRuntimeClosed = graph::awaitClosed,
      exitApplication = { exitApplication() },
    )
  }

  LaunchedEffect(graph) {
    if (isFake) withContext(Dispatchers.IO) { contentStore.wipe() }
    graph.start()
    graph.resume()
  }

  Window(
    onCloseRequest = shutdownOwner::requestClose,
    title = "Dayfold",
  ) {
    FeedApp(
      store = selectorStore,
      commands = graph.commands,
      platformActions = stablePlatformActions,
    )
  }
}
