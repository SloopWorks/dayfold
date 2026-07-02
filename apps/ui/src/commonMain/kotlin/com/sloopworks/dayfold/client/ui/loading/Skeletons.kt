package com.sloopworks.dayfold.client.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A single shimmering placeholder block. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, corner: Int = 12) {
  Spacer(modifier.clip(RoundedCornerShape(corner.dp)).shimmer())
}

// One node announces the region; the rest are hidden so TalkBack doesn't read
// dozens of empty placeholders.
private fun Modifier.loadingRegion(label: String) =
  semantics { liveRegion = LiveRegionMode.Polite; contentDescription = label }

/** Generic list placeholder: N rows of avatar + two text lines. */
@Composable
fun ListSkeleton(rows: Int = 4, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp).loadingRegion("Loading"),
    verticalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    repeat(rows) {
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).padding(13.dp).semantics { hideFromAccessibility() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        SkeletonBox(Modifier.size(38.dp), corner = 50)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          SkeletonBox(Modifier.height(14.dp).width(140.dp))
          SkeletonBox(Modifier.height(12.dp).width(90.dp))
        }
      }
    }
  }
}

/** Feed placeholder: a few tall cards mirroring TypedCardItem metrics. */
@Composable
fun FeedSkeleton(modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxSize().padding(PaddingValues(16.dp)).loadingRegion("Loading your day"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    repeat(3) {
      SkeletonBox(Modifier.fillMaxWidth().height(120.dp).semantics { hideFromAccessibility() }, corner = 18)
    }
  }
}
