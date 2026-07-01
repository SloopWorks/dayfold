package com.sloopworks.dayfold.client

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sloopworks.dayfold.client.cards.CardAction
import com.sloopworks.dayfold.client.theme.DayfoldExtendedColors
import com.sloopworks.dayfold.client.theme.LocalDayfoldColors
import kotlinx.datetime.TimeZone

// ADR 0045 — full timeline detail view: grouped sticky-header feed, NOW line,
// entry rows with rail + content card, interactive attachment chips, provenance footnote.
// Opens at the auto-selected [scale]; a day↔hub scope toggle appears when both scales are
// meaningful (ephemeral — resets to [scale] on each open).

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelineDetail(
    tl: Timeline,
    scale: TimelineScale,
    nowIso: String,
    tz: TimeZone,
    onBack: () -> Unit,
    onAction: (CardAction) -> Unit,
) {
    // Both scales present → offer the ephemeral day↔hub scope toggle (resets to the
    // auto-selected [scale] each time the detail is opened; spec §5).
    val both = remember(tl, nowIso, tz) { hasBothScales(tl, nowIso, tz) }
    var selectedScale by remember(scale) { mutableStateOf(scale) }
    val active = if (both) selectedScale else scale
    val presented = remember(tl, active, nowIso, tz) { presentTimelineDetail(tl, active, nowIso, tz) }
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface),
    ) {
        // ── Header ───────────────────────────────────────────────────────────
        TlDetailHeader(
            title = if (active == TimelineScale.Hub) (tl.title ?: "Roadmap") else (tl.title ?: "Today"),
            subtitle = if (active == TimelineScale.Hub) "All milestones" else "Today’s schedule",
            onBack = onBack,
            showToggle = both,
            selected = active,
            onSelect = { selectedScale = it },
        )

        // ── Feed ─────────────────────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        ) {
            var flatIdx = 0
            presented.groups.forEachIndexed { groupIdx, group ->
                stickyHeader(key = "grp_$groupIdx") {
                    TlGroupHeader(group.label, isFirst = groupIdx == 0)
                }

                // Hub scale: NOW line just after the current-month group header
                if (active == TimelineScale.Hub && presented.nowIndex == groupIdx) {
                    item(key = "now_hub_$groupIdx") {
                        TlNowLine(presented.nowTimeLabel ?: "Today")
                    }
                }

                group.stops.forEachIndexed { stopIdx, ps ->
                    val fi = flatIdx
                    // Day scale: NOW line before the stop at this flat index
                    if (active == TimelineScale.Day && presented.nowIndex == fi) {
                        item(key = "now_day_$fi") {
                            TlNowLine(presented.nowTimeLabel ?: "")
                        }
                    }
                    val isLast = groupIdx == presented.groups.lastIndex &&
                        stopIdx == group.stops.lastIndex
                    item(key = "stop_${groupIdx}_$stopIdx") {
                        TlEntryRow(ps, isLast, active, presented.derived, onAction)
                    }
                    flatIdx++
                }
            }

            // Day: NOW after every stop (i.e., after all)
            val totalFlat = flatIdx
            if (active == TimelineScale.Day && presented.nowIndex == totalFlat) {
                item(key = "now_day_end") {
                    TlNowLine(presented.nowTimeLabel ?: "")
                }
            }

            // Provenance footnote
            item(key = "provenance") {
                TlProvenanceCard(active, presented.derived)
            }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun TlDetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    showToggle: Boolean,
    selected: TimelineScale,
    onSelect: (TimelineScale) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surfaceContainer)
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp, bottom = 16.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(40.dp)
                .offset(x = (-8).dp),
        ) {
            Icon(
                imageVector = DayfoldIcons.ArrowBack,
                contentDescription = "Back",
                tint = cs.onSurface,
                modifier = Modifier.size(23.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 28.sp,
            color = cs.onSurface,
        )
        Text(
            text = subtitle,
            fontSize = 13.5.sp,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
        // Day↔hub scope toggle — only when both scales are meaningful (spec §5/§6).
        if (showToggle) {
            Spacer(Modifier.height(16.dp))
            val options = listOf(
                TimelineScale.Day to ("This day" to DayfoldIcons.WbSunny),
                TimelineScale.Hub to ("Whole hub" to DayfoldIcons.CalendarMonth),
            )
            // Full-width so each half has room (an intrinsic-width row clipped "Whole hub" against
            // the pill's rounded end); mirrors the ProximitySettings segmented control.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (scale, labelIcon) ->
                    val (label, icon) = labelIcon
                    SegmentedButton(
                        selected = selected == scale,
                        onClick = { onSelect(scale) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    ) {
                        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ── Group header ──────────────────────────────────────────────────────────────

@Composable
private fun TlGroupHeader(label: String, isFirst: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface) // opaque so it covers content when sticky
            .padding(top = if (isFirst) 0.dp else 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.07.sp,
            color = cs.onSurfaceVariant,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = cs.outlineVariant,
        )
    }
}

// ── NOW line ──────────────────────────────────────────────────────────────────

@Composable
private fun TlNowLine(nowTimeLabel: String) {
    val cs = MaterialTheme.colorScheme
    val haloColor = cs.primary.copy(alpha = 0.22f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp, start = 1.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Current time, $nowTimeLabel"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Static settle-state halo dot — no animation (reduced-motion honesty)
        Box(
            modifier = Modifier
                .size(19.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(19.dp)
                    .background(haloColor, CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .background(cs.primary, CircleShape),
            )
        }
        // "NOW · HH:MM" pill
        Box(
            modifier = Modifier
                .background(cs.primary, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = "NOW · $nowTimeLabel",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.06.sp,
                color = cs.onPrimary,
            )
        }
        // Gradient trail
        Box(
            modifier = Modifier
                .weight(1f)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(listOf(cs.primary, Color.Transparent))
                ),
        )
    }
}

// ── Entry row ─────────────────────────────────────────────────────────────────

@Composable
private fun TlEntryRow(
    ps: PresentedStop,
    isLast: Boolean,
    scale: TimelineScale,
    derived: Boolean,
    onAction: (CardAction) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val stop = ps.stop
    val isDone = ps.status == StopStatus.Done
    val isNext = ps.status == StopStatus.Next
    val isMajor = stop.major

    Row(
        // IntrinsicSize.Min bounds the row to its content height so the rail's
        // weight(1f) connector fills that height (continuous line) instead of
        // grabbing the LazyColumn's unbounded max.
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── Rail ─────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier.width(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val dotSize = if (isNext || isMajor) 16.dp else 13.dp
            val tickSize = if (isNext || isMajor) 11.dp else 9.dp

            when {
                isDone -> Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(dotSize)
                        .background(cs.secondary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DayfoldIcons.Check,
                        contentDescription = null,
                        tint = cs.onSecondary,
                        modifier = Modifier.size(tickSize),
                    )
                }

                isNext -> Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(dotSize)
                        .background(cs.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DayfoldIcons.ArrowForward,
                        contentDescription = null,
                        tint = cs.onPrimary,
                        modifier = Modifier.size(tickSize),
                    )
                }

                isMajor -> Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(dotSize)
                        .background(cs.tertiary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DayfoldIcons.Star,
                        contentDescription = null,
                        tint = cs.onTertiary,
                        modifier = Modifier.size(tickSize),
                    )
                }

                else -> Box( // Upcoming: hollow outline
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(dotSize)
                        .background(Color.Transparent, CircleShape)
                        .border(2.dp, cs.outline, CircleShape),
                )
            }

            // Connector: fills the entry's height (the mock's flex:1 rail) so the line
            // is continuous between stops. Safe because the Row is IntrinsicSize.Min,
            // so weight(1f) is bounded by the content height (not the LazyColumn max).
            if (!isLast) {
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(if (isDone) cs.secondary else cs.outlineVariant),
                )
            }
        }

        // ── Content card ─────────────────────────────────────────────────────
        val cardBg = when {
            isMajor -> cs.tertiaryContainer
            isNext -> cs.surfaceContainer
            else -> Color.Transparent
        }
        val showCard = isMajor || isNext
        val cardPadding = if (showCard)
            PaddingValues(horizontal = 15.dp, vertical = 13.dp)
        else
            PaddingValues(horizontal = 2.dp, vertical = 1.dp)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 18.dp)
                .then(
                    if (showCard) {
                        Modifier
                            .background(cardBg, RoundedCornerShape(16.dp))
                            .then(
                                if (isNext) Modifier.border(1.dp, cs.primary, RoundedCornerShape(16.dp))
                                else Modifier
                            )
                    } else Modifier
                )
                .padding(cardPadding),
        ) {
            // Title + time
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                val titleColor = when {
                    isMajor && isDone -> cs.onTertiaryContainer.copy(alpha = 0.7f)
                    isMajor           -> cs.onTertiaryContainer
                    isDone            -> cs.onSurfaceVariant
                    else              -> cs.onSurface
                }
                Text(
                    text = stop.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 18.sp,
                    color = titleColor,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                val timeColor = when {
                    isNext            -> cs.primary
                    isMajor && isDone -> cs.onTertiaryContainer.copy(alpha = 0.7f)
                    isMajor           -> cs.onTertiaryContainer
                    isDone            -> cs.onSurfaceVariant
                    else              -> cs.onSurface
                }
                Text(
                    // Day = tz-aware "h:MM AM/PM"; Hub = "Mon D". Computed in the presenter.
                    text = if (scale == TimelineScale.Hub) ps.dateLabel else (ps.timeLabel ?: ps.dateLabel),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = timeColor,
                    modifier = Modifier.wrapContentWidth(Alignment.End),
                )
            }

            // Sub
            if (!stop.sub.isNullOrEmpty()) {
                Text(
                    text = stop.sub,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Meta: (derived) per-stop source tag + assignee avatar + attachment chips
            val sourceTag = if (derived) tlSourceTag(stop.source) else null
            val hasAssignee = !stop.assignee.isNullOrEmpty()
            val hasAttachments = stop.attachments.isNotEmpty()
            if (sourceTag != null || hasAssignee || hasAttachments) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    if (sourceTag != null) {
                        // Quiet ghost tag naming the source block — distinct from the filled
                        // attachment chips (outline-colored icon + one word, "label" depth).
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = sourceTag.first,
                                contentDescription = null,
                                tint = cs.outline,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = sourceTag.second,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                    if (hasAssignee) {
                        val name = stop.assignee!!
                        val avatarBg = if (isMajor) cs.tertiary else cs.primaryContainer
                        val avatarFg = if (isMajor) cs.onTertiary else cs.onPrimaryContainer
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(avatarBg, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = tlAssigneeInitials(name),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 10.sp,
                                    color = avatarFg,
                                )
                            }
                            Text(
                                text = name,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                    stop.attachments.forEach { att ->
                        val action = att.toCardAction()
                        val (chipBg, chipFg) = tlChipColors(att.kind, cs)
                        AssistChip(
                            onClick = { action?.let(onAction) },
                            label = {
                                Text(
                                    text = att.label,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = tlAttachmentIcon(att.kind),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = chipBg,
                                labelColor = chipFg,
                                leadingIconContentColor = chipFg,
                            ),
                            border = null,
                        )
                    }
                }
            }
        }
    }
}

// ── Provenance footnote ───────────────────────────────────────────────────────

@Composable
private fun TlProvenanceCard(scale: TimelineScale, derived: Boolean) {
    val cs = MaterialTheme.colorScheme
    val ext: DayfoldExtendedColors = LocalDayfoldColors.current
    val isHub = scale == TimelineScale.Hub
    // Derived = a neutral, honest footnote (nothing authored; render-only; no notification).
    val provNote = when {
        derived && isHub ->
            "Nothing was authored here. This roadmap is laid out from dates already in the hub — milestones, checklist due-dates and the hub’s own dates — arranged on your device. It doesn’t notify; it just shows what’s already here, in time order."
        derived ->
            "Nothing was authored here. This day is laid out from dates already in the hub — checklist due-times and a pickup — arranged on your device. It doesn’t notify; it just shows what’s already here, in time order."
        isHub ->
            "These milestones were added to this hub’s plan. The author keeps them current and confirms each one — edits are author-only, like two-way (ADR 0038/0039)."
        else ->
            "These stops were added to this hub’s plan; the author keeps them current. Edits are author-only (ADR 0038/0039)."
    }
    val bg = if (derived) Color.Transparent else ext.providerChip
    val borderColor = if (derived) cs.outlineVariant else ext.providerChipOutline
    val fg = if (derived) cs.onSurfaceVariant else ext.onProviderChip
    val icon = if (derived) DayfoldIcons.Event else DayfoldIcons.AutoAwesome

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (derived) cs.outline else ext.onProviderChip,
            modifier = Modifier
                .size(17.dp)
                .padding(top = 1.dp),
        )
        Text(
            text = provNote,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = fg,
        )
    }
}

/** Per-stop source tag for a derived stop (ADR 0046): icon + one word ("label" depth). */
private fun tlSourceTag(source: String?): Pair<ImageVector, String>? = when (source) {
    "checklist" -> DayfoldIcons.Checklist to "checklist"
    "milestone" -> DayfoldIcons.Star to "milestone"
    "pickup"    -> DayfoldIcons.Location to "pickup"
    "hubdate"   -> DayfoldIcons.Event to "hub date"
    else        -> null
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Derive initials: "Pat + Maya" → "PM"; "Maya" → "MA". */
private fun tlAssigneeInitials(name: String): String {
    val parts = name.split("+").map { it.trim() }
    return if (parts.size >= 2) {
        (parts[0].firstOrNull()?.uppercaseChar()?.toString() ?: "") +
            (parts[1].firstOrNull()?.uppercaseChar()?.toString() ?: "")
    } else {
        name.take(2).uppercase()
    }
}

private fun tlAttachmentIcon(kind: String): ImageVector = when (kind) {
    "call" -> DayfoldIcons.Call
    "nav"  -> DayfoldIcons.Location
    "link" -> DayfoldIcons.Link
    "open" -> DayfoldIcons.ArrowOutward
    else   -> DayfoldIcons.Link
}

private fun tlChipColors(
    kind: String,
    cs: androidx.compose.material3.ColorScheme,
): Pair<Color, Color> = when (kind) {
    "call", "nav" -> cs.secondaryContainer to cs.onSecondaryContainer
    "link"        -> cs.tertiaryContainer to cs.onTertiaryContainer
    else          -> cs.surfaceContainerHigh to cs.onSurfaceVariant
}
