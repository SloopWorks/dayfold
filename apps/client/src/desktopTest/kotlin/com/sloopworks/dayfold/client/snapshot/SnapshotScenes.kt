package com.sloopworks.dayfold.client.snapshot

import androidx.compose.runtime.Composable
import com.sloopworks.dayfold.client.cards.DetailScreen
import com.sloopworks.dayfold.client.currentDetailCard
import com.sloopworks.dayfold.client.FeedScreen
import com.sloopworks.dayfold.client.HubDetailScreen
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import org.reduxkotlin.snapshot.SnapshotApp
import org.reduxkotlin.snapshot.SnapshotInput
import org.reduxkotlin.snapshot.cli.runCli
import org.reduxkotlin.snapshot.snapshotApp

// One registry, two consumers: assertGolden tests (Task 6/7) and the :client:snapshotUi
// CLI (Task 5). presets -> SnapshotStates -> the state-based composables under DayfoldTheme.
val clientSnapshots: SnapshotApp = snapshotApp {
  defaults { width = 411; height = 891; density = 2f; theme = "light" }

  scene("feed") {
    presets("busy", "empty", "caught-up", "syncing", "offline", "typed", "enriched")
    render { args ->
      val state = SnapshotStates.feed(presetName(args.input))
      themed(args.theme) { FeedScreen(state) }
    }
  }

  scene("hub-detail") {
    presets("canonical", "enriched")
    render { args ->
      val tree = SnapshotStates.hubTree(presetName(args.input))
      val state = AppState(currentHubId = tree.hub.id, currentHubTree = tree)
      themed(args.theme) { HubDetailScreen(state) }
    }
  }

  scene("detail") {
    presets("file", "link", "invite", "contact", "geo", "email")
    render { args ->
      val id = presetName(args.input)
      val state = SnapshotStates.TYPED_FEED.copy(detailStack = listOf(id))
      val card = currentDetailCard(state)!!
      themed(args.theme) { DetailScreen(card, onBack = {}, onAction = {}) }
    }
  }
}

// Presets only (this registry doesn't accept ad-hoc --state-json; keep the surface small).
private fun presetName(input: SnapshotInput): String = when (input) {
  is SnapshotInput.Preset -> input.name
  is SnapshotInput.Json -> error("this registry takes --preset, not --state-json")
}

private fun themed(theme: String?, content: @Composable () -> Unit): @Composable () -> Unit =
  { DayfoldTheme(darkTheme = theme == "dark") { content() } }

fun main(argv: Array<String>) {
  clientSnapshots.runCli(argv)   // argv = OPTIONS only, no leading "snapshot"
  kotlin.system.exitProcess(0)   // Skiko leaves non-daemon threads alive
}
