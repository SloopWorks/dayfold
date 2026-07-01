package com.sloopworks.dayfold.client

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sloopworks.dayfold.client.theme.LocalDayfoldColors

// ADR 0045 — hub-timeline card composable (Day + Hub/roadmap branches).
// Task 11a: day branch; Task 11b: Hub/roadmap branch.

@Composable
fun TimelineCard(model: TimelineCardModel, onOpen: () -> Unit) {
    when (model.scale) {
        TimelineScale.Day -> TimelineDayCard(model, onOpen)
        TimelineScale.Hub -> TimelineRoadmapCard(model, onOpen)
    }
}

// ── Roadmap (Hub) card ────────────────────────────────────────────────────────

@Composable
private fun TimelineRoadmapCard(model: TimelineCardModel, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        color = cs.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(top = 18.dp, bottom = 15.dp)) {
            val spine = model.spine
            if (!spine.isNullOrEmpty()) {
                RoadmapSpine(spine, model.moreCount)
            }
            if (model.nextCallout != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 13.dp),
                    color = cs.outlineVariant,
                )
                NextMilestoneRow(model.nextCallout)
            }
            RoadmapFooterRow(onOpen)
        }
    }
}

// ── Phase spine ───────────────────────────────────────────────────────────────

@Composable
private fun RoadmapSpine(spine: List<SpineNode>, moreCount: Int) {
    val cs = MaterialTheme.colorScheme
    val totalNodes = spine.size + (if (moreCount > 0) 1 else 0)
    // Index of the Next node; fall back to last Done if none (all-done roadmap).
    val fillEndIdx = spine.indexOfFirst { it.status == StopStatus.Next }
        .let { idx -> if (idx >= 0) idx else spine.indexOfLast { it.status == StopStatus.Done } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .padding(top = 2.dp, bottom = 4.dp),
    ) {
        // Track + fill drawn on a Canvas behind the node Row
        Canvas(modifier = Modifier.fillMaxWidth().height(46.dp)) {
            val trackY = 6.dp.toPx()
            val trackH = 3.dp.toPx()
            val padX = 5.dp.toPx()
            val trackStart = padX
            val trackW = size.width - 2 * padX

            // Full track (outlineVariant)
            drawRoundRect(
                color = cs.outlineVariant,
                topLeft = Offset(trackStart, trackY),
                size = Size(trackW, trackH),
                cornerRadius = CornerRadius(trackH / 2f, trackH / 2f),
            )
            // Fill (secondary) up to fillEndIdx
            if (fillEndIdx >= 0 && totalNodes > 1) {
                val fillFrac = fillEndIdx.toFloat() / (totalNodes - 1)
                drawRoundRect(
                    color = cs.secondary,
                    topLeft = Offset(trackStart, trackY),
                    size = Size(trackW * fillFrac, trackH),
                    cornerRadius = CornerRadius(trackH / 2f, trackH / 2f),
                )
            }
        }

        // Node columns overlaid with SpaceBetween
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            spine.forEach { node ->
                SpineNodeColumn(node)
            }
            if (moreCount > 0) {
                SpineMoreColumn(moreCount)
            }
        }
    }
}

@Composable
private fun SpineNodeColumn(node: SpineNode) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        when (node.status) {
            StopStatus.Done -> Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(11.dp)
                    .background(cs.secondary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DayfoldIcons.Check,
                    contentDescription = null,
                    tint = cs.onSecondary,
                    modifier = Modifier.size(8.dp),
                )
            }
            StopStatus.Next -> Box(
                modifier = Modifier
                    .size(15.dp)
                    .background(cs.primary, CircleShape),
            )
            StopStatus.Upcoming -> Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(11.dp)
                    .background(cs.surfaceContainer, CircleShape)
                    .border(2.dp, cs.outline, CircleShape),
            )
        }
        Text(
            text = node.label.take(3),
            fontSize = 11.sp,
            fontWeight = if (node.status == StopStatus.Next) FontWeight.Bold else FontWeight.W500,
            color = if (node.status == StopStatus.Next) cs.onSurface else cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpineMoreColumn(moreCount: Int) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(11.dp)
                .background(cs.outlineVariant, CircleShape),
        )
        Text(
            text = "+$moreCount",
            fontSize = 9.sp,
            fontWeight = FontWeight.W500,
            color = cs.onSurfaceVariant,
        )
    }
}

// ── Next milestone callout ────────────────────────────────────────────────────

private val MONTHS_3 = arrayOf(
    "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
)

/** Returns (3-letter month ALL-CAPS, day number string) from an ISO date/datetime string. */
private fun calloutMonthDay(at: String): Pair<String, String>? {
    val datePart = at.substringBefore("T").trim()
    val parts = datePart.split("-")
    if (parts.size < 3) return null
    val monthIdx = (parts[1].toIntOrNull() ?: return null) - 1
    val day = parts[2].toIntOrNull() ?: return null
    if (monthIdx !in 0..11) return null
    return MONTHS_3[monthIdx] to day.toString()
}

/** Returns "Mon D" (e.g. "Aug 25") from an ISO date/datetime string. */
private fun calloutDateLabel(at: String): String {
    val (mon, day) = calloutMonthDay(at) ?: return at
    val mon3cap = mon[0] + mon.substring(1).lowercase()  // "AUG" → "Aug"
    return "$mon3cap $day"
}

@Composable
private fun NextMilestoneRow(callout: PresentedStop) {
    val cs = MaterialTheme.colorScheme
    val (mon, day) = calloutMonthDay(callout.stop.at) ?: Pair("", "")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Date tile: primary background, month + big day
        Column(
            modifier = Modifier
                .background(cs.primary, RoundedCornerShape(13.dp))
                .padding(horizontal = 13.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = mon,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.08.sp,
                color = cs.onPrimary,
            )
            Text(
                text = day,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp,
                color = cs.onPrimary,
            )
        }

        // Right side: chip + date label + title
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .background(cs.primary, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "NEXT",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.04.sp,
                        color = cs.onPrimary,
                    )
                }
                Text(
                    text = calloutDateLabel(callout.stop.at),
                    fontSize = 11.5.sp,
                    color = cs.onSurfaceVariant,
                )
            }
            Text(
                text = callout.stop.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.4.sp,
                color = cs.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ── Roadmap footer ────────────────────────────────────────────────────────────

@Composable
private fun RoadmapFooterRow(onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = LocalDayfoldColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "Open roadmap",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.primary,
            )
            Icon(
                imageVector = DayfoldIcons.ArrowOutward,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(16.dp),
            )
        }

        // "Added to this hub" provenance chip (decorative, same as day card)
        Row(
            modifier = Modifier
                .background(ext.providerChip, RoundedCornerShape(8.dp))
                .border(1.dp, ext.providerChipOutline, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = DayfoldIcons.AutoAwesome,
                contentDescription = null,
                tint = ext.onProviderChip,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = "Added to this hub",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = ext.onProviderChip,
            )
        }
    }
}

// ── Day card ──────────────────────────────────────────────────────────────────

@Composable
private fun TimelineDayCard(model: TimelineCardModel, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        color = cs.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(horizontal = 17.dp).padding(top = 15.dp, bottom = 13.dp)) {
            // "N done" collapsed cap
            if (model.doneCount > 0) {
                DoneCapRow(model.doneCount)
            }
            // NOW marker row
            if (model.nowTimeLabel != null) {
                NowRow(model.nowTimeLabel)
            }
            // Windowed upcoming rows
            model.window.forEachIndexed { idx, ps ->
                val isLastRow = idx == model.window.lastIndex && model.tailCount == 0
                StopRow(ps, isLastRow = isLastRow)
            }
            // Tail: "N more"
            if (model.tailCount > 0) {
                TailRow(model.tailCount)
            }
            // Footer: "Open timeline" + "Added to this hub" chip
            FooterRow(onOpen)
        }
    }
}

// ── Done cap ──────────────────────────────────────────────────────────────────

@Composable
private fun DoneCapRow(doneCount: Int) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Secondary dot with filled check glyph
        Box(
            modifier = Modifier
                .size(13.dp)
                .background(cs.secondary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DayfoldIcons.Check,
                contentDescription = null,
                tint = cs.onSecondary,
                modifier = Modifier.size(9.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$doneCount done",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = cs.outlineVariant,
        )
    }
}

// ── NOW marker ────────────────────────────────────────────────────────────────

@Composable
private fun NowRow(nowTimeLabel: String) {
    val cs = MaterialTheme.colorScheme
    val haloColor = cs.primary.copy(alpha = 0.22f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Rail dot column (14dp wide to align with stop rows)
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .drawBehind {
                        // Static settle-state halo: primary ring at 0.22α (~4dp outset)
                        drawCircle(
                            color = haloColor,
                            radius = size.minDimension / 2 + 4.dp.toPx(),
                        )
                    }
                    .background(cs.primary, CircleShape),
            )
        }
        Spacer(Modifier.width(13.dp))
        // "NOW · HH:MM" pill
        Box(
            modifier = Modifier
                .background(cs.primary, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = "NOW · $nowTimeLabel",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.06.sp,
                color = cs.onPrimary,
            )
        }
        Spacer(Modifier.width(13.dp))
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

// ── Windowed stop row ─────────────────────────────────────────────────────────

@Composable
private fun StopRow(ps: PresentedStop, isLastRow: Boolean) {
    val cs = MaterialTheme.colorScheme
    val stop = ps.stop
    Row(Modifier.fillMaxWidth()) {
        // Rail: dot + connector
        Column(
            modifier = Modifier.width(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (ps.status) {
                StopStatus.Done -> {
                    // Filled secondary dot with check
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .size(10.dp)
                            .background(cs.secondary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = DayfoldIcons.Check,
                            contentDescription = null,
                            tint = cs.onSecondary,
                            modifier = Modifier.size(7.dp),
                        )
                    }
                }
                StopStatus.Next -> {
                    // Filled primary dot
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .size(10.dp)
                            .background(cs.primary, CircleShape),
                    )
                }
                StopStatus.Upcoming -> {
                    // Hollow outline dot
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .size(10.dp)
                            .background(Color.Transparent, CircleShape)
                            .border(1.5.dp, cs.outlineVariant, CircleShape),
                    )
                }
            }
            // Vertical connector (hidden on last row).
            // Fixed height — NOT weight(1f) — so the rail Column stays intrinsic
            // and the card wraps content height rather than consuming parent max height.
            if (!isLastRow) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(20.dp)
                        .padding(vertical = 3.dp)
                        .background(cs.outlineVariant),
                )
            }
        }

        Spacer(Modifier.width(13.dp))

        // Stop content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLastRow) 0.dp else 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stop.title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when (ps.status) {
                        StopStatus.Done -> cs.onSurfaceVariant
                        StopStatus.Next -> cs.onSurface
                        StopStatus.Upcoming -> cs.onSurface
                    },
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (ps.status == StopStatus.Next) {
                    Spacer(Modifier.width(7.dp))
                    // "NEXT" pill
                    Box(
                        modifier = Modifier
                            .border(1.dp, cs.primary, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "NEXT",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.04.sp,
                            color = cs.primary,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Time label: parse HH:MM from ISO "...THH:MM:SS±offset", display as h:MM (12-hr, no am/pm)
                val timeLabel = stopTimeLabel(stop.at)
                Text(
                    text = timeLabel,
                    fontSize = 12.5.sp,
                    color = cs.onSurfaceVariant,
                )
                if (stop.attachments.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = DayfoldIcons.Attachment,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stop.attachments.size.toString(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── Tail row ──────────────────────────────────────────────────────────────────

@Composable
private fun TailRow(tailCount: Int) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(cs.outline, CircleShape),
            )
        }
        Spacer(Modifier.width(13.dp))
        Text(
            text = "$tailCount more",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = cs.onSurfaceVariant,
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Parse "HH:MM" from an ISO-8601 [at] string and format as "h:MM" (12-hr, no am/pm),
 * matching the design's clockTime format. Falls back to the raw string on parse failure.
 */
private fun stopTimeLabel(at: String): String {
    val timePart = at.substringAfter("T", "")
        .substringBefore("-")
        .substringBefore("+")
    val parts = timePart.split(":")
    if (parts.size < 2) return at
    val h = parts[0].toIntOrNull() ?: return at
    val m = parts[1].padStart(2, '0')
    val h12 = (h % 12).let { if (it == 0) 12 else it }
    return "$h12:$m"
}

// ── Footer row ────────────────────────────────────────────────────────────────

@Composable
private fun FooterRow(onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = LocalDayfoldColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Open timeline" with arrow
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "Open timeline",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.primary,
            )
            Icon(
                imageVector = DayfoldIcons.ArrowOutward,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(16.dp),
            )
        }

        // "Added to this hub" provenance chip
        Row(
            modifier = Modifier
                .background(ext.providerChip, RoundedCornerShape(8.dp))
                .border(1.dp, ext.providerChipOutline, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = DayfoldIcons.AutoAwesome,
                contentDescription = null,
                tint = ext.onProviderChip,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = "Added to this hub",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = ext.onProviderChip,
            )
        }
    }
}
