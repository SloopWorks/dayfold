package com.sloopworks.dayfold.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.cards.LocalAnimatedVisibilityScope
import com.sloopworks.dayfold.client.cards.LocalSharedTransitionScope
import com.sloopworks.dayfold.client.cards.cardSharedBounds
import com.sloopworks.dayfold.client.cards.vettedOpenUri
import kotlinx.datetime.TimeZone
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.ui.loading.ErrorRetry
import com.sloopworks.dayfold.client.ui.loading.ListSkeleton
import com.sloopworks.dayfold.client.ui.loading.rememberReduceMotion

// ── Hubs surface (ADR 0006 render · ADR 0030 visibility) ─────────────────────
// f(state)→UI. Rich per-type block rendering (checklist boxes, budget bars, maps)
// is a follow-up; this renders the structure, the typed-block content, and the
// per-member visibility treatment (the gated ADR 0030 delta). M3 semantic colors
// resolve to the Dayfold warm palette via DayfoldTheme.

@Composable
fun DayfoldBottomNav(hubsActive: Boolean, onNow: () -> Unit, onHubs: () -> Unit) {
  NavigationBar {
    NavigationBarItem(
      selected = !hubsActive, onClick = onNow,
      // designs/*.dc.html: Now=today (filled when active). Icon decorative — the "Now"
      // label + selected state carry a11y meaning; NavigationBarItem tints by selection.
      icon = { androidx.compose.material3.Icon(if (!hubsActive) DayfoldIcons.TodayFilled else DayfoldIcons.Today, contentDescription = null, modifier = Modifier.clearAndSetSemantics {}) },
      label = { Text("Now") },
    )
    NavigationBarItem(
      selected = hubsActive, onClick = onHubs,
      icon = { androidx.compose.material3.Icon(if (hubsActive) DayfoldIcons.DashboardFilled else DayfoldIcons.Dashboard, contentDescription = null, modifier = Modifier.clearAndSetSemantics {}) },
      label = { Text("Hubs") },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubListScreen(
  state: AppState,
  onOpenHub: (String) -> Unit = {},
  onNow: () -> Unit = {},
  onFilter: (String) -> Unit = {},
  onRetry: () -> Unit = {},
) {
  val shown = state.hubs.filter { when (state.hubFilter) { "active" -> it.status == "active"; "planning" -> it.status == "planning"; else -> true } }
  Scaffold(
    topBar = { TopAppBar(title = { Text("Hubs", fontWeight = FontWeight.SemiBold) }) },
    bottomBar = { DayfoldBottomNav(hubsActive = true, onNow = onNow, onHubs = {}) },
  ) { pad ->
    Column(Modifier.fillMaxSize().padding(pad)) {
      if (state.hubs.isNotEmpty()) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterPill("All", state.hubFilter == "all") { onFilter("all") }
          FilterPill("Active", state.hubFilter == "active") { onFilter("active") }
          FilterPill("Planning", state.hubFilter == "planning") { onFilter("planning") }
        }
      }
      when {
        state.hubs.isEmpty() && state.hubsBusy -> ListSkeleton(rows = 4, modifier = Modifier.fillMaxSize())
        state.hubs.isEmpty() && state.hubError != null ->
          Box(Modifier.fillMaxSize(), Alignment.Center) { ErrorRetry(state.hubError, onRetry = onRetry) }
        state.hubs.isEmpty() ->
          Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
              "When a big family event shows up — a trip, a move, a birthday — a hub appears here.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(40.dp),
            )
          }
        shown.isEmpty() ->
          Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("No ${state.hubFilter} hubs.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        else -> LazyColumn(
          Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(shown, key = { it.id }) { hub -> HubRow(hub, onClick = { onOpenHub(hub.id) }) }
        }
      }
    }
  }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(9.dp),
    modifier = Modifier.clickable(onClick = onClick)
      .then(if (selected) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp))),
  ) {
    Text(
      label,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
      color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
    )
  }
}

@Composable
private fun HubRow(hub: Hub, onClick: () -> Unit) {
  val count = hubWhenLabel(hub.countdownTo, hub.startAt, hub.endAt, kotlin.time.Clock.System.now().toString())
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    shape = RoundedCornerShape(24.dp),
  ) {
    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
      // ADR 0036: fixed 1:1 leading slot (image → icon+accent tile fallback). Shown
      // only when enriched, so unenriched hubs keep their current look (no regression).
      val media = hub.media
      if (media.isEnriched()) {
        EnrichedThumbnail(
          imageUrl = media!!.thumbnailUrl ?: media.heroUrl, fit = media.heroFit, icon = media.icon,
          accentHex = media.accentColor, alt = media.imageAlt, size = 56.dp, corner = 16.dp,
        )
        Spacer(Modifier.width(14.dp))
      }
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(hub.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
          if (hub.visibility == "restricted") {
            // calm restricted marker — warm-neutral, never error-red. Icon-only →
            // give the screen reader "Private" instead of announcing the lock glyph.
            androidx.compose.material3.Icon(DayfoldIcons.Lock, contentDescription = "Private", tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 7.dp).size(15.dp))
          }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
          StatusChip(hub.status)
          Text(
            if (hub.visibility == "restricted") "Private" else hubTypeLabel(hub.type),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
          )
        }
      }
      // right-aligned countdown (the "when" — core to event hubs). Takes the hub's
      // derived accent (decorative reinforcement) when enriched, else theme primary.
      if (count != null && hub.status != "archived") {
        val countColor = rememberAccentRoles(media?.accentColor)?.edge ?: MaterialTheme.colorScheme.primary
        Text(count, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = countColor, modifier = Modifier.padding(start = 10.dp))
      }
    }
  }
}

@Composable
private fun StatusChip(status: String) {
  val (bg, fg) = when (status) {
    "active" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    "planning" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
  }
  Surface(color = bg, shape = RoundedCornerShape(7.dp)) {
    Text(status.uppercase(), style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class, ExperimentalTransitionApi::class)
@Composable
fun HubDetailScreen(
  state: AppState,
  onBack: () -> Unit = {},
  onNow: () -> Unit = {},
  onOpenAudience: () -> Unit = {},
  onRetry: () -> Unit = {},
  // Slice 4 (ADR 0038): a member toggle → (blockId, itemId, newDone); the shell routes
  // it to HubEngine.toggleItem → ContentStore.enqueueBlockToggle. onSyncNow forces a
  // drain ("Sync now" pill); onRetryBlock re-queues a block parked 'failed'.
  onToggleItem: (String, String, Boolean) -> Unit = { _, _, _ -> },
  onRetryBlock: (String) -> Unit = {},
  onSyncNow: () -> Unit = {},
  // Slice 5b (ADR 0038 §W4/§W5): delete = author-gated egress; hide/unhide = local-only;
  // onSetShowHidden flips the "Hidden for you" view toggle. The shell routes these to HubEngine.
  onDeleteBlock: (String) -> Unit = {},
  onHideBlock: (String) -> Unit = {},
  onUnhideBlock: (String) -> Unit = {},
  onSetShowHidden: (Boolean) -> Unit = {},
  // ADR 0045 — hub timeline card + detail overlay (Task 13a: static integration, no shared-element morph)
  onOpenTimeline: (TimelineScale) -> Unit = {},
  onCloseTimeline: () -> Unit = {},
  onTimelineAction: (CardAction) -> Unit = {},
) {
  val tree = state.currentHubTree
  // ADR 0045: compute once — used by both the hoisted TimelineCard item and the detail overlay.
  val authoredTimeline = tree?.hub?.timeline
  // tz: an authored timeline carries its own; a derived one falls back family→device (device for now).
  val tz = authoredTimeline?.let { runCatching { TimeZone.of(it.tz) }.getOrElse { TimeZone.currentSystemDefault() } }
    ?: TimeZone.currentSystemDefault()
  // ADR 0046: when no authored timeline, derive one from the hub's already-dated blocks (≥2 stops).
  val tl = authoredTimeline ?: tree?.let { deriveTimeline(it, tz) }
  // "No timeline yet" nudge: no authored + not enough to derive, but exactly one dated block.
  val showTimelineNudge = authoredTimeline == null && tl == null && tree != null && hubHasSingleDate(tree, tz)
  val nowIso = kotlin.time.Clock.System.now().toString()
  // ADR 0045 13b: shared-element container-transform (card→detail morph). Both branches of
  // AnimatedContent apply cardSharedBounds("timeline") so the card bounds morph into the detail
  // and back. Reduced motion → snapTo (no animation). System back dispatches CloseTimelineDetail
  // → state.timelineDetail = null → morph back; no gesture handler needed for correctness.
  val seekable = remember { SeekableTransitionState<TimelineScale?>(state.timelineDetail) }
  val reduceMotion = rememberReduceMotion()
  LaunchedEffect(state.timelineDetail, reduceMotion) {
    if (seekable.currentState != state.timelineDetail) {
      if (reduceMotion) seekable.snapTo(state.timelineDetail)
      else seekable.animateTo(state.timelineDetail, animationSpec = tween(if (state.timelineDetail != null) 360 else 280, easing = EmphasizedDecelerate))
    }
  }
  val transition = rememberTransition(seekable, label = "hub-timeline")
  SharedTransitionLayout {
    transition.AnimatedContent(
      contentKey = { it != null },
      transitionSpec = {
        val opening = targetState != null
        val dur = if (opening) 360 else 280
        (fadeIn(tween(dur)) + slideInVertically(tween(dur)) { h -> h / 16 }) togetherWith fadeOut(tween(dur))
      },
    ) { scale ->
      CompositionLocalProvider(
        LocalSharedTransitionScope provides this@SharedTransitionLayout,
        LocalAnimatedVisibilityScope provides this@AnimatedContent,
      ) {
        if (scale != null && tl != null) {
          Box(Modifier.fillMaxSize().cardSharedBounds("timeline")) {
            TimelineDetail(tl, scale, nowIso, tz, onBack = onCloseTimeline, onAction = onTimelineAction)
          }
        } else {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(tree?.hub?.title ?: "Hub", fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
          // glyph-only control → give the screen reader a real label, not the icon
          IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to hubs" }) {
            androidx.compose.material3.Icon(DayfoldIcons.ArrowBack, contentDescription = null, modifier = Modifier.clearAndSetSemantics {})
          }
        },
      )
    },
    bottomBar = { DayfoldBottomNav(hubsActive = true, onNow = onNow, onHubs = {}) },
  ) { pad ->
    when {
      tree == null && state.hubsBusy -> ListSkeleton(rows = 5, modifier = Modifier.fillMaxSize().padding(pad))
      tree == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
        ErrorRetry(state.hubError ?: "Hub unavailable", onRetry = onRetry)
      }
      else -> {
      val listState = rememberLazyListState()
      // deep-link arrival: scroll the focused block into view. hasCountdown/restricted
      // must mirror the header items emitted below (the helper counts them).
      val hasCountdown = hubWhenLabel(tree.hub.countdownTo, tree.hub.startAt, tree.hub.endAt, kotlin.time.Clock.System.now().toString()) != null && tree.hub.status != "archived"
      LaunchedEffect(state.hubFocusBlockId, tree) {
        focusedBlockItemIndex(
          tree, state.hubFocusBlockId, hasCountdown, tree.hub.visibility == "restricted",
          // one hoisted slot when a (non-hidden) timeline card OR the "No timeline yet" nudge shows
          hasTimelineCard = (tl != null && !state.hiddenIds.contains("timeline:${tree.hub.id}") &&
            presentTimelineCard(tl, nowIso, tz) != null) || showTimelineNudge,
        )?.let { listState.animateScrollToItem(it) }
      }
      // Slice 4 (ADR 0038, States screen): the optimistic-write status, derived off the
      // blocks' local_state — one calm queue affordance, never a per-row alarm.
      val pendingWrites = tree.blocks.count { it.localState == "pending" }
      val failedWrites = tree.blocks.count { it.localState == "failed" }
      LazyColumn(
        Modifier.fillMaxSize().padding(pad),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        // Offline/queued banner: shown only when a sync is actually failing AND we hold
        // unsynced writes — the only honest moment to say "saved here, will sync" (D4).
        if (state.error != null && (pendingWrites > 0 || failedWrites > 0)) {
          item(key = "sync-banner") { OfflineSavedBanner() }
        }
        // Queue pill: a single tappable "N waiting · Sync now" while writes are in flight.
        if (pendingWrites > 0) {
          item(key = "queue-pill") { QueuePill(pendingWrites, onSyncNow = onSyncNow) }
        }
        item {
          // ADR 0036: enriched hub gets a height-capped hero banner (image → icon+
          // accent fallback) above the status row. Part of the first item so the
          // deep-link scroll index math (focusedBlockItemIndex) is unaffected.
          if (tree.hub.media.isEnriched()) {
            EnrichedHeroBanner(
              tree.hub.media, tree.hub.title,
              hubWhenLabel(tree.hub.countdownTo, tree.hub.startAt, tree.hub.endAt, kotlin.time.Clock.System.now().toString()),
              modifier = Modifier.padding(bottom = 14.dp),
            )
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(tree.hub.status)
            if (tree.hub.visibility == "restricted") {
              // tappable → "Who can see this hub" sheet
              Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(999.dp),
                // onClickLabel tells the screen reader what the tap does; 🔒/⌄ are decorative
                modifier = Modifier.padding(start = 8.dp).clickable(onClick = onOpenAudience, onClickLabel = "See who can see this hub"),
              ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                  androidx.compose.material3.Icon(DayfoldIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp).clearAndSetSemantics {})
                  Text("Private", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 6.dp))
                  androidx.compose.material3.Icon(DayfoldIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 3.dp).size(18.dp).clearAndSetSemantics {})
                }
              }
            }
          }
        }
        hubWhenLabel(tree.hub.countdownTo, tree.hub.startAt, tree.hub.endAt, kotlin.time.Clock.System.now().toString())?.let { c ->
          if (tree.hub.status != "archived") item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              androidx.compose.material3.Icon(DayfoldIcons.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
              Text(c, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
          }
        }
        if (tree.hub.visibility == "restricted") {
          item {
            Text(
              "Only people you choose can open this hub.",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        // a freshly-created hub (e.g. just `push --hub`'d) has no content yet —
        // show a calm note instead of a blank void under the header.
        if (tree.sections.isEmpty()) {
          item(key = "hub-empty") {
            Text(
              "Nothing here yet — this hub's details will appear as they're added.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 12.dp),
            )
          }
        }
        // ADR 0045: hoisted timeline card — hub-level, above all content sections.
        // presentTimelineCard selects Day vs Hub scale automatically from the stop cadence.
        // Per-member "Hide for me" (ADR 0039 §W5): local-only, keyed by a synthetic id so the
        // card partitions into the "Hidden for you" section like any other dossier element.
        val timelineHideId = "timeline:${tree.hub.id}"
        val timelineHidden = tl != null && state.hiddenIds.contains(timelineHideId)
        tl?.let { timeline ->
          if (!timelineHidden) {
            presentTimelineCard(timeline, nowIso, tz)?.let { model ->
              item(key = "timeline") {
                // Swipe (on-brand) + a screen-reader custom action, mirroring HubBlockCard's W5 hide.
                val dismissState = rememberSwipeToDismissBoxState(
                  confirmValueChange = { v ->
                    if (v == SwipeToDismissBoxValue.EndToStart || v == SwipeToDismissBoxValue.StartToEnd) {
                      onHideBlock(timelineHideId); false
                    } else false
                  },
                )
                SwipeToDismissBox(
                  state = dismissState,
                  backgroundContent = { HideSwipeBackground() },
                  modifier = Modifier.fillMaxWidth().semantics {
                    customActions = listOf(CustomAccessibilityAction("Hide timeline for me") {
                      onHideBlock(timelineHideId); true
                    })
                  },
                ) {
                  Box(Modifier.cardSharedBounds("timeline")) {
                    TimelineCard(model) { onOpenTimeline(model.scale) }
                  }
                }
              }
            }
          }
        }
        // Discoverability (ADR 0045 §4.2 / designs Tap-To-Detail): when a timeline has BOTH scales,
        // the card shows one auto-selected scale; a small row invites the other (opens the detail there).
        if (tl != null && !timelineHidden && presentTimelineCard(tl, nowIso, tz) != null &&
            hasBothScales(tl, nowIso, tz)) {
          val cardScale = selectScale(tl, nowIso, tz)
          item(key = "timeline-scale-hint") {
            TimelineScaleHintRow(cardScale) {
              onOpenTimeline(if (cardScale == TimelineScale.Day) TimelineScale.Hub else TimelineScale.Day)
            }
          }
        }
        // ADR 0046: "No timeline yet" nudge — one dated block, not enough to lay out. Teaches the
        // mechanic (add a couple more dates and a timeline appears) without faking a fuller hub.
        if (showTimelineNudge) {
          item(key = "timeline-nudge") { TimelineNudgeCard() }
        }
        // sections (ordered) each followed by their blocks (grouped by section_id). Hidden
        // blocks (W5) are filtered out of the live sections and collected into the one
        // "Hidden for you" section below — hide is a pure VIEW split, never a deletion (D4).
        val selfId = state.session?.userId
        tree.sections.sortedBy { it.ord }.forEach { section ->
          val all = tree.blocks.filter { it.sectionId == section.id }.sortedBy { it.ord }
          val blocks = partitionHidden(all, state.hiddenIds).visible
          if (blocks.isEmpty()) return@forEach          // skip a section with no visible content — no bare header
          item(key = "sec-${section.id}") {
            Text(
              (section.title ?: "").uppercase(),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          items(blocks, key = { "blk-${it.id}" }) { block ->
            HubBlockCard(
              block, focused = block.id == state.hubFocusBlockId,
              // author-gate (W4): delete is offered only to the author (set-once created_by);
              // a null author (legacy / loop-authored) is never deletable by a member.
              canDelete = block.createdBy != null && block.createdBy == selfId,
              onToggleItem = onToggleItem, onRetryBlock = onRetryBlock,
              onDeleteBlock = onDeleteBlock, onHideBlock = onHideBlock,
            )
          }
        }
        // ── "Hidden for you" (W5) — a collapsed, personal section; never a family-visible
        // signal. Each hidden item carries the "You hid this" self-reminder + an Unhide path.
        val hidden = tree.blocks.filter { state.hiddenIds.contains(it.id) }.sortedBy { it.ord }
        if (hidden.isNotEmpty() || timelineHidden) {
          item(key = "hidden-header") {
            HiddenForYouHeader(hidden.size + (if (timelineHidden) 1 else 0), state.showHidden, onToggle = { onSetShowHidden(!state.showHidden) })
          }
          if (state.showHidden) {
            if (timelineHidden) {
              item(key = "hidden-timeline") {
                HiddenTimelineCard(onUnhide = { onUnhideBlock(timelineHideId) })
              }
            }
            items(hidden, key = { "hidden-${it.id}" }) { block ->
              HiddenBlockCard(block, onUnhide = { onUnhideBlock(block.id) })
            }
          }
        }
      }
      }
    }
  }
        } // closes else { dossier branch of AnimatedContent scale check
      } // closes CompositionLocalProvider
    } // closes AnimatedContent
  } // closes SharedTransitionLayout
}

// Pure: the LazyColumn item index of the focused block (or null = not present / no
// focus), so the arrival can scroll it into view. Mirrors HubDetailScreen's emission
// order: [status header] + [countdown?] + [honesty?] + per section [header] + [blocks].
// Unit-tested so it can't silently drift from the render.
fun focusedBlockItemIndex(tree: HubTree, focusBlockId: String?, hasCountdown: Boolean, restricted: Boolean, hasTimelineCard: Boolean = false): Int? {
  if (focusBlockId == null) return null
  var idx = 1                                       // status header (always)
  if (hasCountdown) idx += 1
  if (restricted) idx += 1
  if (hasTimelineCard) idx += 1
  for (section in tree.sections.sortedBy { it.ord }) {
    val blocks = tree.blocks.filter { it.sectionId == section.id }.sortedBy { it.ord }
    if (blocks.isEmpty()) continue                   // empty sections render nothing → don't count a header
    idx += 1                                          // section header
    val pos = blocks.indexOfFirst { it.id == focusBlockId }
    if (pos >= 0) return idx + pos
    idx += blocks.size
  }
  return null
}

// "Who can see this hub" (ADR 0030, the signed-off Hubs-Visibility sheet). Display
// only at MVP — in-app editing of the allow-list is push/CLI (OQ-hub-collab). A
// scrim + bottom panel; the roster shows permitted (filled check) vs hidden (ring).
@Composable
fun WhoCanSeeSheet(state: AppState, onClose: () -> Unit = {}, onRetryAudience: () -> Unit = {}) {
  val aud = state.currentHubAudience
  Box(Modifier.fillMaxSize()) {
    // scrim
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)).clickable(onClick = onClose))
    Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
      modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
    ) {
      Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Who can see this hub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
          "Private hubs are visible only to the people you pick. The family owner isn't added automatically.",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
          state.audienceError != null ->
            ErrorRetry(state.audienceError, onRetry = onRetryAudience)
          aud == null ->
            androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
          else -> aud.members.forEach { m -> AudienceRow(m, isYou = m.uid == state.session?.userId) }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
          Text(
            "Removing someone hides this hub from their devices on the next sync.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(13.dp),
          )
        }
        androidx.compose.material3.Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
      }
    }
  }
}

@Composable
private fun AudienceRow(m: HubAudienceMember, isYou: Boolean) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    val initials = (m.displayName ?: "?").split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
      Text(initials, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Column(Modifier.padding(horizontal = 13.dp).weight(1f)) {
      Text((m.displayName ?: "Member") + if (isYou) " · You" else "", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(m.role.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // permitted = filled coral check; hidden = empty ring
    Box(
      Modifier.size(30.dp).clip(RoundedCornerShape(50))
        .background(if (m.permitted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
        .then(if (m.permitted) Modifier else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))),
      contentAlignment = Alignment.Center,
    ) { if (m.permitted) Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium) }
  }
}

/** Human label for a hub's catalog `type` slug (ADR 0004/0006: vacation,
 *  starting-college, party-event, …). Title-cases the slug generically so a new
 *  catalog key needs no map change — "starting-college" → "Starting College".
 *  Pure; blank/null → "". */
internal fun hubTypeLabel(type: String?): String =
  type?.takeIf { it.isNotBlank() }
    ?.split('-')
    ?.joinToString(" ") { w -> w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    ?: ""

/** True when a payload-driven block has no usable typed payload but DOES carry
 *  `body_md` — authors routinely put a contact/checklist/document's content in
 *  markdown (no structured payload), so the renderer must show that markdown rather
 *  than an empty typed layout (which rendered just "Contact"/"document"/blank). Pure. */
internal fun blockFallsBackToBodyMd(block: HubBlock): Boolean =
  !block.bodyMd.isNullOrBlank() && !hasTypedPayload(block)

// ADR 0035 Option C: tolerant of BOTH the client render names and the canonical
// schema names (document `docRef`|`ref`; location `lat`/`lng`|`mapUrl`) so a block
// authored per the frozen schema is recognized as typed and renders (not blank).
private fun hasTypedPayload(block: HubBlock): Boolean = when (block.type) {
  "checklist" -> !block.payload?.items.isNullOrEmpty()
  "link", "document" -> block.payload?.run { url != null || label != null || docRef != null || ref != null } ?: false
  "contact" -> block.payload?.run { name != null || phone != null || email != null || role != null } ?: false
  "location" -> block.payload?.run { address != null || lat != null || label != null || mapUrl != null } ?: false
  "budget" -> block.payload?.run { total != null || !items.isNullOrEmpty() } ?: false
  else -> true   // text/markdown/milestone/unknown render body_md in their own branch
}

// Budget spent/total: the client `total`/`spent` summary if present, else derived
// from a canonical itemized budget (schema `items[{amount, paid}]`) — ADR 0035.
internal fun budgetTotals(p: BlockPayload?): Pair<Double, Double> {
  if (p?.total != null) return p.total to (p.spent ?: 0.0)
  val items = p?.items.orEmpty()
  val total = items.sumOf { it.amount ?: 0.0 }
  val spent = items.filter { it.paid == true }.sumOf { it.amount ?: 0.0 }
  return total to spent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubBlockCard(
  block: HubBlock,
  focused: Boolean = false,
  canDelete: Boolean = false,
  onToggleItem: (String, String, Boolean) -> Unit = { _, _, _ -> },
  onRetryBlock: (String) -> Unit = {},
  onDeleteBlock: (String) -> Unit = {},
  onHideBlock: (String) -> Unit = {},
) {
  var menuOpen by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }

  // W5 hide — swipe (the on-brand path; mirrors the fold). A swipe-end fires hide + resets
  // the box: the row leaves the visible list the moment the hide flows back through the DB,
  // so there's nothing to keep "dismissed". Hide is local + reversible — no confirm.
  val dismissState = rememberSwipeToDismissBoxState(
    confirmValueChange = { v ->
      if (v == SwipeToDismissBoxValue.EndToStart || v == SwipeToDismissBoxValue.StartToEnd) {
        onHideBlock(block.id); false
      } else false
    },
  )
  SwipeToDismissBox(
    state = dismissState,
    backgroundContent = { HideSwipeBackground() },
    modifier = Modifier.fillMaxWidth(),
  ) {
    Card(
      Modifier.fillMaxWidth()
        .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)) else Modifier),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
      shape = RoundedCornerShape(22.dp),
    ) {
      Box {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          // deep-link arrival badge (the design's "FROM YOUR BRIEFING" pulse)
          if (focused) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(7.dp)) {
              Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(DayfoldIcons.ArrowOutward, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(end = 3.dp).size(13.dp))
                Text("FROM YOUR BRIEFING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
              }
            }
          }
          when {
            // typed block whose content is in body_md (no usable payload) → show the markdown
            blockFallsBackToBodyMd(block) -> Text(rememberRenderedMarkdown(block.bodyMd ?: ""), style = MaterialTheme.typography.bodyMedium)
            else -> when (block.type) {
              "text", "markdown" -> Text(rememberRenderedMarkdown(block.bodyMd ?: ""), style = MaterialTheme.typography.bodyMedium)
              "checklist" -> ChecklistBlock(block, onToggleItem = onToggleItem, onRetryBlock = onRetryBlock)
              "link", "document" -> LinkRow(block)
              "contact" -> ContactRow(block.payload)
              "location" -> LocationBlock(block.payload)
              "milestone" -> MilestoneRow(block.payload, block.bodyMd)
              "budget" -> BudgetBar(block.payload)
              else -> Text(rememberRenderedMarkdown(block.bodyMd ?: block.type), style = MaterialTheme.typography.bodyMedium)
            }
          }
          block.provenance?.source?.let { src -> ProvenanceChip(src) }
        }
        // Overflow (the a11y fallback — keyboard- and screen-reader-reachable): Hide for me
        // always, Delete only for the author. Both gestures reach the same actions.
        Box(Modifier.align(Alignment.TopEnd)) {
          IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = "More options" }) {
            androidx.compose.material3.Icon(DayfoldIcons.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clearAndSetSemantics {})
          }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
              text = { Text("Hide for me") },
              leadingIcon = { androidx.compose.material3.Icon(DayfoldIcons.VisibilityOff, contentDescription = null, modifier = Modifier.size(20.dp)) },
              onClick = { menuOpen = false; onHideBlock(block.id) },
            )
            if (canDelete) {
              DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { androidx.compose.material3.Icon(DayfoldIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) },
                onClick = { menuOpen = false; confirmDelete = true },
              )
            }
          }
        }
      }
    }
  }

  // W4 delete — the one calm, ACL-aware warn sheet (not a red alert). Delete is the single
  // destructive op that removes the thing family-wide, so it asks once. Only reachable here
  // when canDelete (author) — non-authors never see the option.
  if (confirmDelete) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = { confirmDelete = false }, sheetState = sheetState) {
      Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(deleteSheetTitle(block), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
          "Your family will no longer see it. This can't be undone after sync.",
          style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
          onClick = { confirmDelete = false; onDeleteBlock(block.id) },
          colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError,
          ),
          modifier = Modifier.fillMaxWidth(),
        ) { Text("Delete for everyone") }
        TextButton(onClick = { confirmDelete = false }, modifier = Modifier.fillMaxWidth()) { Text("Keep it") }
      }
    }
  }
}

// The swipe-reveal background behind a block: a calm "Hide for me" affordance (the personal
// colour, never the coral delete owns).
@Composable
private fun HideSwipeBackground() {
  // fillMaxSize (not fillMaxWidth): the affordance must match the card's FULL height so
  // its rounded corners align with the card's. With fillMaxWidth it wrapped its content
  // (~48dp), top-aligned, and its rounded top corners peeked through the card's rounded
  // top corners at rest — the stray "greenish corners".
  Box(
    Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp))
      .background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 20.dp),
  ) {
    Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      androidx.compose.material3.Icon(DayfoldIcons.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
      Text("Hide", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
  }
}

// "Hidden for you · N" header — the expandable, dashed personal section. The copy never says
// "deleted"/"removed", only "hidden for you"; the family still sees these.
@Composable
private fun HiddenForYouHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp),
    modifier = Modifier.fillMaxWidth().clickable(
      onClick = onToggle,
      onClickLabel = if (expanded) "Hide the hidden section" else "Show hidden",
    ),
  ) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      androidx.compose.material3.Icon(DayfoldIcons.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
      Column(Modifier.weight(1f).padding(start = 10.dp)) {
        Text("Hidden for you · $count", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text("Your family still sees these", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Text(if (expanded) "Hide" else "Show hidden", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
  }
}

// A single hidden block, shown when "Show hidden" is on: the content (dimmed) + the quiet
// "You hid this" self-reminder + an Unhide path to bring it back.
@Composable
private fun HiddenBlockCard(block: HubBlock, onUnhide: () -> Unit) {
  Card(
    Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    shape = RoundedCornerShape(22.dp),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.alpha(0.6f)) {
        Text(blockPreviewText(block), style = MaterialTheme.typography.bodyMedium)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(DayfoldIcons.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text("You hid this", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp).weight(1f))
        TextButton(onClick = onUnhide) { Text("Unhide") }
      }
    }
  }
}

// Second-scale discoverability row (designs/hub-timeline Tap-To-Detail): the card shows one scale;
// this invites the other. cardScale = the shown scale → the row names the OTHER one.
@Composable
private fun TimelineScaleHintRow(cardScale: TimelineScale, onOpen: () -> Unit) {
  val cs = MaterialTheme.colorScheme
  val otherIsRoadmap = cardScale == TimelineScale.Day
  Surface(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    color = cs.surfaceContainer,
    shape = RoundedCornerShape(16.dp),
  ) {
    Row(
      modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 15.dp, vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
      androidx.compose.material3.Icon(
        if (otherIsRoadmap) DayfoldIcons.CalendarMonth else DayfoldIcons.WbSunny,
        contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(19.dp),
      )
      Text(
        buildAnnotatedString {
          append("This hub also has a ")
          withStyle(SpanStyle(color = cs.onSurface, fontWeight = FontWeight.SemiBold)) {
            append(if (otherIsRoadmap) "roadmap" else "day view")
          }
          append(" — open to switch scales")
        },
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      androidx.compose.material3.Icon(
        DayfoldIcons.ArrowOutward, contentDescription = null,
        tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp),
      )
    }
  }
}

// ADR 0046 "No timeline yet" nudge — shown when a hub has exactly one dated block (not enough to
// derive a timeline). Teaches the mechanic; honest that nothing is authored.
@Composable
private fun TimelineNudgeCard() {
  val cs = MaterialTheme.colorScheme
  Card(
    Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    shape = RoundedCornerShape(22.dp),
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
      Box(
        modifier = Modifier.size(40.dp).background(cs.surface, RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
      ) {
        androidx.compose.material3.Icon(DayfoldIcons.Event, contentDescription = null, tint = cs.outline, modifier = Modifier.size(22.dp))
      }
      Text("No timeline yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        "This hub has just one date so far. Add due dates to a couple of checklist items or a milestone and they’ll lay out here automatically — nothing to set up.",
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
      )
    }
  }
}

// The hidden hoisted timeline card (ADR 0045 / 0039 §W5): dimmed label + "You hid this" +
// Unhide, mirroring HiddenBlockCard. The family still sees the timeline; this is local-only.
@Composable
private fun HiddenTimelineCard(onUnhide: () -> Unit) {
  Card(
    Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    shape = RoundedCornerShape(22.dp),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.alpha(0.6f)) {
        Text("Timeline", style = MaterialTheme.typography.bodyMedium)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(DayfoldIcons.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text("You hid this", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp).weight(1f))
        TextButton(onClick = onUnhide) { Text("Unhide") }
      }
    }
  }
}

// The delete-sheet title: name the thing when we can ("Delete "Grocery run list"?"), else a
// calm generic ("Delete this?"). Blocks have no title field — derive a short label.
internal fun deleteSheetTitle(block: HubBlock): String =
  blockPreviewText(block).takeIf { it.isNotBlank() }?.let { "Delete “${it.take(40).trim()}”?" } ?: "Delete this?"

// A one-line preview of a block for the hidden card + the delete sheet: the first non-blank
// line of body_md, else a typed-payload label/name, else the block type.
internal fun blockPreviewText(block: HubBlock): String {
  block.bodyMd?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }?.let { return it.removePrefix("#").trim() }
  block.payload?.let { p -> (p.label ?: p.name ?: p.items?.firstOrNull { it.text != null }?.text)?.let { return it } }
  return block.type
}

// Slice 4 (ADR 0038 §4) — the interactive checklist. The check IS a fold: a freshly
// tapped row stays live + struck through one shared ~2s burst, then the whole batch
// folds into a collapsed "N done" section (newest-first, count-only past ~20). The
// block carries the optimistic-write state (local_state) — a saving hairline while it
// syncs, a calm Retry if it gives up — plus the one honest sharing claim (D4).
@Composable
private fun ChecklistBlock(
  block: HubBlock,
  onToggleItem: (String, String, Boolean) -> Unit,
  onRetryBlock: (String) -> Unit,
) {
  val items = block.payload?.items ?: emptyList()
  // Interactive iff items carry stable ids (ADR 0038 CLI stamp-on-push) — only then can a
  // member toggle merge. A legacy/display-only list (no ids, e.g. a loop card with plain
  // items) renders as static struck rows: no fold, no sync chip — the "synced when online"
  // claim is only honest (D4) where a real member-write boundary exists.
  val interactive = items.any { it.id != null }
  if (!interactive) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { items.forEach { ChecklistRow(it, onToggle = null) } }
    return
  }
  // The burst set lives in UI state; the DB done-triple is the source of truth. Each
  // tap re-arms ONE shared debounce (LaunchedEffect re-keys on the set) so a rapid
  // batch folds together, not staggered. Touch has already ended on a discrete tap.
  var fold by remember { mutableStateOf(ChecklistFold()) }
  LaunchedEffect(fold.checking) {
    if (fold.checking.isNotEmpty()) {
      kotlinx.coroutines.delay(2_000)
      fold = fold.burstFolded()
    }
  }
  val active = ChecklistFoldView.activeItems(items, fold.checking)
  val done = ChecklistFoldView.doneItems(items, fold.checking)

  fun toggle(item: ChecklistItem, newDone: Boolean) {
    val id = item.id ?: return                        // no stable id → can't merge; non-interactive
    fold = fold.toggled(id, newDone)
    onToggleItem(block.id, id, newDone)
  }

  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    active.forEach { item -> ChecklistRow(item, onToggle = item.id?.let { { nd -> toggle(item, nd) } }) }
    if (done.isNotEmpty()) {
      DoneSection(done, collapsedOnly = ChecklistFoldView.doneCollapsedOnly(done.size), onToggle = ::toggle)
    }
    // optimistic-write state for the whole-block PUT (the real boundary): a saving
    // hairline while pending, a calm inline Retry (never a dialog) once it's failed.
    when (block.localState) {
      "pending" -> LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).padding(top = 4.dp))
      "failed" -> FailedRetry(onRetry = { onRetryBlock(block.id) })
    }
    // Honest claim (ADR 0022 D4): a member toggle really is shared + synced to the family.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Shared with your family · synced when online",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun ChecklistRow(item: ChecklistItem, onToggle: ((Boolean) -> Unit)? = null) {
  val reduceMotion = rememberReduceMotion()
  val haptics = LocalHapticFeedback.current
  val done = item.done
  // box fills coral with a quick scale-overshoot; the ✓ draws in (M3 emphasized). The
  // strike wipes left→right via drawWithContent (animate between states, never snap).
  val checkScale by animateFloatAsState(
    targetValue = if (done) 1f else 0f,
    animationSpec = if (reduceMotion) tween(0) else spring(dampingRatio = 0.45f, stiffness = 900f),
    label = "check-scale",
  )
  val strike by animateFloatAsState(
    targetValue = if (done) 1f else 0f,
    animationSpec = if (reduceMotion) tween(0) else tween(180),
    label = "strike",
  )
  val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
  // Whole-row 48dp hit target, Role.Checkbox + state. The actor (who toggled) rides in
  // the state description so a screen reader hears "checked by Mom".
  val rowMod = if (onToggle != null) {
    Modifier.fillMaxWidth().height(48.dp).toggleable(    // ≥48dp hit target only where it's tappable
      value = done, role = Role.Checkbox,
      onValueChange = { nd -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); onToggle(nd) },
    ).semantics {
      stateDescription = buildString {
        append(if (done) "checked" else "not checked")
        if (done && item.doneBy != null) append(", by ${item.doneBy}")
      }
    }
  } else Modifier.fillMaxWidth()

  Row(rowMod, verticalAlignment = Alignment.CenterVertically) {
    val box = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
      Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(box)
        .then(if (done) Modifier else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))),
      contentAlignment = Alignment.Center,
    ) {
      if (checkScale > 0.01f) {
        Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.graphicsLayer { scaleX = checkScale; scaleY = checkScale })
      }
    }
    Column(Modifier.padding(start = 12.dp).weight(1f)) {
      Text(
        item.text ?: "",
        style = MaterialTheme.typography.bodyMedium,
        color = if (done) onSurfaceVar else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.drawWithContent {
          drawContent()
          if (strike > 0f) {
            val y = size.height / 2f
            drawLine(onSurfaceVar, Offset(0f, y), Offset(size.width * strike, y), strokeWidth = 2.dp.toPx())
          }
        },
      )
      // subline: the conflict/remote byline ("✓ Mom") when done, else due · assignee.
      val sub = if (done && item.doneBy != null) "✓ ${item.doneBy}"
        else listOfNotNull(item.due?.let { "Due $it" }, item.assignee).joinToString(" · ")
      if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = onSurfaceVar)
    }
  }
}

// The collapsed "N done" foldaway (Role.Button, expand/collapse). Past ~20 done it's a
// calm count-only line (no expand). Expanded rows stay tappable so a fold can be undone.
@Composable
private fun DoneSection(done: List<ChecklistItem>, collapsedOnly: Boolean, onToggle: (ChecklistItem, Boolean) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Column {
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth().then(
        if (collapsedOnly) Modifier
        else Modifier.clickable(role = Role.Button, onClickLabel = if (open) "Collapse done items" else "Expand done items") { open = !open }
      ),
    ) {
      Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${done.size} done", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    AnimatedVisibility(visible = open && !collapsedOnly, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
      Column(Modifier.padding(top = 4.dp)) {
        done.forEach { item -> ChecklistRow(item, onToggle = item.id?.let { { nd -> onToggle(item, nd) } }) }
      }
    }
  }
}

// Calm failure (ADR 0022 D4 / States screen): neutral-toned, data preserved, a single
// focusable Retry — never red, never a modal.
@Composable
private fun FailedRetry(onRetry: () -> Unit) {
  Row(Modifier.fillMaxWidth().alpha(0.85f), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Couldn't save — nothing lost", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
      Text("We'll keep it here and retry.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton(onClick = onRetry) { Text("Retry") }
  }
}

// One quiet offline/queued banner (not a per-row alarm). Honest whether the network is
// down or the server is unreachable: the local copy is saved and will sync.
@Composable
private fun OfflineSavedBanner() {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      Column {
        Text("Saved here", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text("Your changes will sync when you're back online.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

// The queue pill (AssistChip-shaped): "N waiting · Sync now". Tapping forces a sync; it
// never blocks. Shown only while writes are pending.
@Composable
private fun QueuePill(pending: Int, onSyncNow: () -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(999.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      val label = if (pending == 1) "1 change saving" else "$pending changes saving"
      Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
      Text("Sync now", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Sync now") { onSyncNow() })
    }
  }
}

@Composable
private fun LinkRow(block: HubBlock) {
  val p = block.payload
  // The block is TAPPABLE when it carries an openable (https-vetted) URL — opens via the same installed
  // LocalUriHandler (PlatformUriHandler → re-vetted PlatformActions.openUri) that inline body-links use,
  // so no new callback threading. A LINK keeps its URL in `url`; a DOCUMENT keeps it in `ref`/`docRef`
  // (e.g. a PDF link) — open whichever vets as https. A non-URL ref (internal doc id) won't vet → inert.
  // (Bug fix: LinkRow showed the "opens externally" arrow but had no click handler → nothing on tap; a
  // first pass only handled `url`, missing document blocks whose URL is in `ref`.)
  val uriHandler = LocalUriHandler.current
  val openUrl = (p?.url ?: p?.docRef ?: p?.ref)?.let { vettedOpenUri(it) }
  val rowModifier = if (openUrl != null) {
    Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${p?.label ?: p?.domain ?: "link"}") { uriHandler.openUri(openUrl) }
  } else Modifier.fillMaxWidth()
  Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
    // ADR 0036: link/document preview thumbnail (image → icon-tile fallback).
    if (p?.thumbnailUrl != null) {
      EnrichedThumbnail(
        imageUrl = p.thumbnailUrl, fit = "cover",
        icon = if (block.type == "document") "document" else "link",
        accentHex = p.accentColor, alt = p.thumbnailAlt, size = 48.dp, corner = 12.dp,
      )
    } else {
      IconTile(if (block.type == "document") DayfoldIcons.Document else DayfoldIcons.Link, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
    }
    Column(Modifier.padding(horizontal = 13.dp).weight(1f)) {
      // document ref is the canonical schema name; docRef is the client alias (ADR 0035)
      val refStr = p?.docRef ?: p?.ref
      Text(p?.label ?: p?.url ?: refStr ?: block.type, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
      (p?.domain ?: p?.url ?: refStr)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
    androidx.compose.material3.Icon(DayfoldIcons.ArrowOutward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))  // opens externally (calm)
  }
}

@Composable
private fun ContactRow(p: BlockPayload?) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    // ADR 0036: avatarUrl photo → initials fallback (invisible on miss).
    ContactAvatar(p?.name, p?.avatarUrl)
    Column(Modifier.padding(horizontal = 13.dp).weight(1f)) {
      Text(p?.name ?: "Contact", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      p?.role?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    // round Call / Text affordances (OS handoff when wired — display here)
    if (p?.phone != null) { RoundAffordance(DayfoldIcons.Call, "Call"); Box(Modifier.width(8.dp)); RoundAffordance(DayfoldIcons.Message, "Message") }
  }
}

@Composable
private fun LocationBlock(p: BlockPayload?) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // map placeholder tile (Coil3 map render is M1; a calm warm tile + pin at M0)
    Box(
      Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) { androidx.compose.material3.Icon(DayfoldIcons.Location, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(p?.label ?: "Location", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        p?.address?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Directions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        androidx.compose.material3.Icon(DayfoldIcons.ArrowOutward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 3.dp).size(16.dp))
      }
    }
  }
}

@Composable
private fun MilestoneRow(p: BlockPayload?, bodyMd: String?) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(11.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))  // timeline dot
    Column(Modifier.padding(start = 12.dp)) {
      Text(p?.label ?: bodyMd ?: "Milestone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      p?.date?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
  }
}

@Composable
private fun BudgetBar(p: BlockPayload?) {
  val (total, spent) = budgetTotals(p)
  val frac = if (total > 0) (spent / total).coerceIn(0.0, 1.0).toFloat() else 0f
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.Bottom) {
      Text("${'$'}${spent.toInt()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      Text(" / ${'$'}${total.toInt()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
      Text("${'$'}${(total - spent).toInt()} left", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
    }
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
      Box(Modifier.fillMaxWidth(frac).height(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondary))
    }
  }
}

@Composable
private fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: androidx.compose.ui.graphics.Color, tint: androidx.compose.ui.graphics.Color) =
  Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(bg), contentAlignment = Alignment.Center) {
    androidx.compose.material3.Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
  }

// `label` is the screen-reader name for the action affordance (was a bare emoji before).
@Composable
private fun RoundAffordance(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) =
  Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
    androidx.compose.material3.Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
  }

@Composable
private fun ProvenanceChip(src: String) {
  Text(
    when (src) { "claude" -> "✦ Added by Claude"; "email" -> "From your email"; "user" -> "You added this"; else -> "Saved" },
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
