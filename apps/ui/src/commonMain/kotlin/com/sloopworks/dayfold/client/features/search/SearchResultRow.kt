package com.sloopworks.dayfold.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SearchResultRow(
  result: SearchResult,
  onClick: (SearchResultHandle) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val kind = result.handle.destination.kind
  val kindLabel = kind.accessibleLabel()
  val announcement = buildList {
    add(kindLabel)
    add(result.title)
    result.breadcrumb?.takeIf(String::isNotBlank)?.let(::add)
    result.excerpt?.takeIf(String::isNotBlank)?.let(::add)
    if (result.closeMatch) add("Close match")
    if (result.archived) add("Archived")
  }.joinToString(". ") { it.trim().trimEnd('.', '!', '?') }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 72.dp)
      .clickable(role = Role.Button) { onClick(result.handle) }
      .semantics(mergeDescendants = true) {
        role = Role.Button
        contentDescription = announcement
      }
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    SearchKindGlyph(kind)
    Spacer(Modifier.width(12.dp))
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Text(
        text = highlightedSearchText(result.title, result.titleHighlights),
        style = MaterialTheme.typography.titleMedium,
        color = colors.onSurface,
      )
      if (!result.breadcrumb.isNullOrBlank() || result.closeMatch || result.archived) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          result.breadcrumb?.takeIf(String::isNotBlank)?.let { breadcrumb ->
            Text(
              text = highlightedSearchText(breadcrumb, result.breadcrumbHighlights),
              modifier = Modifier.weight(1f, fill = false),
              style = MaterialTheme.typography.bodySmall,
              color = colors.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (result.closeMatch) SearchResultLabel("Close match")
          if (result.archived) SearchResultLabel("Archived")
        }
      }
      result.excerpt?.takeIf(String::isNotBlank)?.let { excerpt ->
        Text(
          text = highlightedSearchText(excerpt, result.excerptHighlights),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun SearchKindGlyph(kind: SearchDestinationKind) {
  val colors = MaterialTheme.colorScheme
  val isNow = kind == SearchDestinationKind.CARD
  Box(
    modifier = Modifier
      .size(36.dp)
      .background(
        color = if (isNow) colors.primaryContainer else colors.secondaryContainer,
        shape = CircleShape,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = kind.icon(),
      contentDescription = null,
      modifier = Modifier.size(20.dp),
      tint = if (isNow) colors.onPrimaryContainer else colors.onSecondaryContainer,
    )
  }
}

@Composable
private fun SearchResultLabel(label: String) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    shape = RoundedCornerShape(999.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
    )
  }
}

@Composable
internal fun highlightedSearchText(
  text: String,
  highlights: List<SearchHighlight>,
): AnnotatedString {
  val colors = MaterialTheme.colorScheme
  return buildHighlightedSearchText(
    text = text,
    highlights = highlights,
    exactStyle = SpanStyle(
      color = colors.onPrimaryContainer,
      background = colors.primaryContainer,
      textDecoration = TextDecoration.Underline,
    ),
    closeStyle = SpanStyle(
      color = colors.onSecondaryContainer,
      background = colors.secondaryContainer,
      textDecoration = TextDecoration.Underline,
    ),
  )
}

internal fun buildHighlightedSearchText(
  text: String,
  highlights: List<SearchHighlight>,
  exactStyle: SpanStyle,
  closeStyle: SpanStyle,
): AnnotatedString = buildAnnotatedString {
  append(text)
  highlights
    .asSequence()
    .filter { it.range.start < it.range.endExclusive }
    .filter { it.range.start in 0..text.length && it.range.endExclusive <= text.length }
    .forEach { highlight ->
      addStyle(
        style = when (highlight.kind) {
          SearchHighlightKind.EXACT -> exactStyle
          SearchHighlightKind.CLOSE -> closeStyle
        },
        start = highlight.range.start,
        end = highlight.range.endExclusive,
      )
    }
}

private fun SearchDestinationKind.accessibleLabel(): String = when (this) {
  SearchDestinationKind.CARD -> "Card"
  SearchDestinationKind.HUB -> "Hub"
  SearchDestinationKind.SECTION -> "Section"
  SearchDestinationKind.BLOCK -> "Hub item"
}

private fun SearchDestinationKind.icon(): ImageVector = when (this) {
  SearchDestinationKind.CARD -> DayfoldIcons.Today
  SearchDestinationKind.HUB -> DayfoldIcons.Dashboard
  SearchDestinationKind.SECTION -> DayfoldIcons.Tune
  SearchDestinationKind.BLOCK -> DayfoldIcons.Checklist
}
