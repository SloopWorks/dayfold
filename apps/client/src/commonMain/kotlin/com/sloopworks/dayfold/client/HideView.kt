package com.sloopworks.dayfold.client

// Slice 5b (ADR 0038 §W5) — the pure hide VIEW split. Hide is local + personal + reversible
// and NEVER an ACL: a hidden block still lives in the synced tree, so the split keeps EVERY
// block and only partitions them into the visible list and the collapsed "Hidden for you"
// section. "Show hidden" is a pure view toggle over [hidden]; nothing here mutates content.
data class HiddenPartition(val visible: List<HubBlock>, val hidden: List<HubBlock>)

fun partitionHidden(blocks: List<HubBlock>, hiddenIds: Set<String>): HiddenPartition =
  HiddenPartition(
    visible = blocks.filterNot { hiddenIds.contains(it.id) },
    hidden = blocks.filter { hiddenIds.contains(it.id) },
  )
