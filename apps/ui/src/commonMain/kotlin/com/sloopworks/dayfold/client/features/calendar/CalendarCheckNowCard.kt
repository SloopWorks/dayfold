package com.sloopworks.dayfold.client.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sloopworks.dayfold.client.CalendarCheckFooter
import com.sloopworks.dayfold.client.CalendarCheckPreview
import com.sloopworks.dayfold.client.CalendarGapKind
import com.sloopworks.dayfold.client.DayfoldIcons
import com.sloopworks.dayfold.client.NowItem

// WI-446 (ADR 0063 §5, designs/calendar-reconciliation/Calendar-Check-Phone.dc.html §12-15) — the
// ONE aggregate Now unit for Calendar Check: a provenance row, the "N things to review" title, up
// to 3 preview rows (+ a quiet "+ N more" line), and a "Review"/"Review N" pill. Stateless: [item]
// is already the fully-computed calendarCheckDisplay().item (:client, ADR 0058) — this file only
// renders it. Production Feed wiring supplies the Calendar Review route callback.
@Composable
fun CalendarCheckNowCard(item: NowItem, onReview: () -> Unit, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  val count = item.calendarCheckCount ?: item.calendarCheckPreviews.size
  val previews = item.calendarCheckPreviews
  val moreCount = count - previews.size
  ElevatedCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
    Column(Modifier.padding(start = 19.dp, top = 17.dp, end = 19.dp, bottom = 15.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(Icons.Filled.Difference, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
          "CALENDAR CHECK · ON THIS PHONE", style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant,
        )
      }
      Spacer(Modifier.height(8.dp))
      Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
      Column(Modifier.padding(top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        previews.forEach { CalendarCheckPreviewRow(it) }
        if (moreCount > 0) {
          Text(
            if (moreCount == 1) "+ 1 more" else "+ $moreCount more",
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
          )
        }
      }
      val reviewLabel = if (moreCount > 0) "Review $count" else "Review"
      Surface(
        shape = RoundedCornerShape(999.dp), color = cs.secondaryContainer,
        modifier = Modifier.clickable(onClick = onReview).semantics { contentDescription = "$reviewLabel calendar check items" },
      ) {
        Row(
          Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(reviewLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = cs.onSecondaryContainer)
          Icon(DayfoldIcons.ArrowForward, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(17.dp))
        }
      }
    }
  }
}

@Composable
private fun CalendarCheckPreviewRow(preview: CalendarCheckPreview) {
  val cs = MaterialTheme.colorScheme
  val (icon, tint) = if (preview.gapKind == CalendarGapKind.DAYFOLD_ONLY) {
    DayfoldIcons.Event to cs.primary
  } else {
    DayfoldIcons.CalendarMonth to cs.onSurfaceVariant
  }
  Row(
    Modifier.fillMaxWidth().background(cs.surface, RoundedCornerShape(13.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
    Text(
      "${preview.title} · ${gapKindSuffix(preview.gapKind)}", style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun gapKindSuffix(gapKind: String): String = when (gapKind) {
  CalendarGapKind.DAYFOLD_ONLY -> "not on your calendar"
  CalendarGapKind.CALENDAR_ONLY -> "calendar only"
  CalendarGapKind.SUGGESTED -> "possible match"
  CalendarGapKind.AMBIGUOUS -> "needs a pick"
  CalendarGapKind.DIFFERS -> "details differ"
  CalendarGapKind.RECURRING -> "repeats"
  else -> gapKind
}

// The quiet all-clear/stale line (ADR 0063 §5) — replaces the aggregate card when there is nothing
// to review. Never a card, never colored for urgency; a single centered row at the end of the feed.
@Composable
fun CalendarCheckFooterLine(footer: CalendarCheckFooter, modifier: Modifier = Modifier) {
  val cs = MaterialTheme.colorScheme
  val whenLabel = com.sloopworks.dayfold.client.formatMetaWhen(footer.lastSuccessfulCheckAtIso)
  val text = if (footer.allClear) {
    "Calendar check: all clear" + (whenLabel?.let { " · compared $it on this phone" } ?: "")
  } else {
    "Calendar check: last compared" + (whenLabel?.let { " $it" } ?: "") + " — can't check right now"
  }
  val icon = if (footer.allClear) DayfoldIcons.Event else DayfoldIcons.Today
  Column(
    modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp, start = 24.dp, end = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
  }
}
