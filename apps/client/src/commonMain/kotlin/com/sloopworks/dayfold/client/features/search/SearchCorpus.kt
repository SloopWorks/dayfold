package com.sloopworks.dayfold.client

/** Build the immutable, permission-safe derived index for one writer-consistent snapshot. */
fun buildSearchCorpus(
  snapshot: SearchContentSnapshot,
  checkpoint: SearchCheckpoint = SearchCheckpoint.NONE,
): SearchCorpus {
  if (snapshot.readiness == SearchReadiness.WAITING_FOR_SYNC) {
    return SearchCorpus(snapshot.revision, snapshot.readiness, emptyList())
  }

  val inputCount = snapshot.cards.size + snapshot.hubs.size + snapshot.sections.size + snapshot.blocks.size
  val documents = ArrayList<SearchDocument>(minOf(inputCount, 1_024))
  var stableOrder = 0
  var visited = 0

  fun visit() {
    if (visited % SearchLimits.DOCUMENT_CANCELLATION_INTERVAL == 0) checkpoint.check()
    visited++
  }

  fun add(document: SearchDocument?) {
    if (document == null) return
    documents += document.copy(stableOrder = stableOrder++)
  }

  snapshot.cards.forEach { card ->
    visit()
    val excluded = card.id in snapshot.hiddenIds || SubjectRef.card(card.id) in snapshot.completedSubjectRefs
    if (!excluded) add(cardDocument(card, checkpoint))
  }

  // Filtering parents first makes a descendant impossible to return when its canonical route is
  // hidden/completed or absent from the permission-filtered cache.
  val hubs = snapshot.hubs.filterNot { hub ->
    hub.id in snapshot.hiddenIds || SubjectRef.node(hub.id) in snapshot.completedSubjectRefs
  }.associateBy { it.id }
  snapshot.hubs.forEach { hub ->
    visit()
    if (hub.id in hubs) {
      add(hubDocument(
        hub,
        includeTimeline = "timeline:${hub.id}" !in snapshot.hiddenIds,
        checkpoint = checkpoint,
      ))
    }
  }

  val sections = LinkedHashMap<String, Pair<HubSection, Hub>>()
  snapshot.sections.forEach { section ->
    visit()
    val hub = section.hubId?.let(hubs::get) ?: return@forEach
    val completed = SubjectRef.node(hub.id, section.id) in snapshot.completedSubjectRefs
    if (section.id !in snapshot.hiddenIds && !completed) {
      sections[section.id] = section to hub
      add(sectionDocument(section, hub, checkpoint))
    }
  }

  snapshot.blocks.forEach { block ->
    visit()
    val (section, hub) = block.sectionId?.let(sections::get) ?: return@forEach
    val completed = SubjectRef.node(hub.id, section.id, block.id) in snapshot.completedSubjectRefs ||
      SubjectRef.node(hub.id, blockId = block.id) in snapshot.completedSubjectRefs
    if (block.id !in snapshot.hiddenIds && !completed) {
      add(blockDocument(block, section, hub, checkpoint))
    }
  }

  val readiness = if (documents.isEmpty()) SearchReadiness.EMPTY else SearchReadiness.READY
  return SearchCorpus(snapshot.revision, readiness, documents.toList())
}

private class SearchDocumentBuilder(
  private val destination: SearchDestination,
  private val archived: Boolean,
  private val checkpoint: SearchCheckpoint,
) {
  private var remainingChars = SearchLimits.MAX_DOCUMENT_CODE_UNITS
  private var remainingTokens = SearchLimits.MAX_DOCUMENT_TOKENS
  private var title: IndexedSearchField? = null
  private var breadcrumb: IndexedSearchField? = null
  private val detail = ArrayList<IndexedSearchField>()

  fun add(kind: SearchFieldKind, raw: String?) {
    if (raw.isNullOrBlank() || remainingChars <= 0 || remainingTokens <= 0) return
    val boundedRaw = safePrefix(raw, remainingChars)
    val cleaned = cleanSearchText(boundedRaw)
    if (cleaned.isBlank()) return
    val display = safePrefix(cleaned, remainingChars)
    val tokens = tokenizeSearchText(display, remainingTokens, checkpoint)
    remainingChars -= display.length
    remainingTokens -= tokens.size
    val field = IndexedSearchField(kind, display, tokens)
    when (kind) {
      SearchFieldKind.TITLE -> if (title == null) title = field
      SearchFieldKind.BREADCRUMB -> if (breadcrumb == null) breadcrumb = field
      SearchFieldKind.BODY, SearchFieldKind.STRUCTURED -> detail += field
    }
  }

  fun build(): SearchDocument? {
    val actualTitle = title ?: return null
    return SearchDocument(destination, actualTitle, breadcrumb, detail.toList(), archived, stableOrder = 0)
  }
}

private fun cardDocument(card: Card, checkpoint: SearchCheckpoint): SearchDocument? {
  val b = SearchDocumentBuilder(SearchDestination.card(card.id), archived = false, checkpoint)
  b.add(SearchFieldKind.TITLE, card.title)
  b.add(SearchFieldKind.BODY, card.bodyMd)
  b.add(SearchFieldKind.STRUCTURED, card.kind)
  b.add(SearchFieldKind.STRUCTURED, card.type)
  b.add(SearchFieldKind.STRUCTURED, card.provenance?.source)
  b.add(SearchFieldKind.STRUCTURED, card.notBefore)
  b.add(SearchFieldKind.STRUCTURED, card.expiresAt)
  card.related.orEmpty().forEach { related ->
    b.add(SearchFieldKind.STRUCTURED, related.title)
    b.add(SearchFieldKind.STRUCTURED, related.sub)
    b.add(SearchFieldKind.STRUCTURED, related.relation)
  }
  addCardPayload(b, card.payload)
  return b.build()
}

private fun addCardPayload(b: SearchDocumentBuilder, payload: Payload?) {
  payload ?: return
  payload.file?.let { p ->
    b.add(SearchFieldKind.STRUCTURED, p.filename); b.add(SearchFieldKind.STRUCTURED, p.mime)
    b.add(SearchFieldKind.STRUCTURED, p.source); b.add(SearchFieldKind.STRUCTURED, p.owner)
    b.add(SearchFieldKind.STRUCTURED, p.modified); p.sharedWith.orEmpty().forEach { b.add(SearchFieldKind.STRUCTURED, it) }
  }
  payload.link?.let { p ->
    b.add(SearchFieldKind.STRUCTURED, p.title); b.add(SearchFieldKind.STRUCTURED, p.ogDesc)
    b.add(SearchFieldKind.STRUCTURED, p.domain); b.add(SearchFieldKind.STRUCTURED, p.url)
    b.add(SearchFieldKind.STRUCTURED, p.kind); b.add(SearchFieldKind.STRUCTURED, p.closesAt)
    b.add(SearchFieldKind.STRUCTURED, p.savedAt)
  }
  payload.invite?.let { p ->
    b.add(SearchFieldKind.STRUCTURED, p.eventName); b.add(SearchFieldKind.STRUCTURED, p.host)
    b.add(SearchFieldKind.STRUCTURED, p.startAt); b.add(SearchFieldKind.STRUCTURED, p.place)
    b.add(SearchFieldKind.STRUCTURED, p.rsvpBy); b.add(SearchFieldKind.STRUCTURED, p.rsvpState)
    b.add(SearchFieldKind.STRUCTURED, p.notes)
  }
  payload.contact?.let { p ->
    b.add(SearchFieldKind.STRUCTURED, p.name); b.add(SearchFieldKind.STRUCTURED, p.company)
    b.add(SearchFieldKind.STRUCTURED, p.role); b.add(SearchFieldKind.STRUCTURED, p.phone)
    b.add(SearchFieldKind.STRUCTURED, p.email); b.add(SearchFieldKind.STRUCTURED, p.address)
    b.add(SearchFieldKind.STRUCTURED, p.hours); b.add(SearchFieldKind.STRUCTURED, p.deliveryWindow)
  }
  payload.geo?.let { p ->
    b.add(SearchFieldKind.STRUCTURED, p.label); b.add(SearchFieldKind.STRUCTURED, p.address)
    b.add(SearchFieldKind.STRUCTURED, p.distance); b.add(SearchFieldKind.STRUCTURED, p.travelMode)
    b.add(SearchFieldKind.STRUCTURED, p.parking); b.add(SearchFieldKind.STRUCTURED, p.leaveBy)
  }
  payload.email?.let { p ->
    b.add(SearchFieldKind.STRUCTURED, p.from); b.add(SearchFieldKind.STRUCTURED, p.fromAddr)
    b.add(SearchFieldKind.STRUCTURED, p.subject); b.add(SearchFieldKind.STRUCTURED, p.date)
    b.add(SearchFieldKind.STRUCTURED, p.bodyExcerpt)
    p.attachments.orEmpty().forEach { a ->
      b.add(SearchFieldKind.STRUCTURED, a.name); b.add(SearchFieldKind.STRUCTURED, a.mime)
    }
    p.labels.orEmpty().forEach { b.add(SearchFieldKind.STRUCTURED, it) }
  }
}

private fun hubDocument(
  hub: Hub,
  includeTimeline: Boolean,
  checkpoint: SearchCheckpoint,
): SearchDocument? {
  val b = SearchDocumentBuilder(SearchDestination.hub(hub.id), hub.status == "archived", checkpoint)
  b.add(SearchFieldKind.TITLE, hub.title)
  b.add(SearchFieldKind.STRUCTURED, hub.type)
  b.add(SearchFieldKind.STRUCTURED, hub.startAt)
  b.add(SearchFieldKind.STRUCTURED, hub.endAt)
  b.add(SearchFieldKind.STRUCTURED, hub.countdownTo)
  hub.timeline?.takeIf { includeTimeline }?.let { timeline ->
    b.add(SearchFieldKind.BODY, timeline.title)
    timeline.stops.forEach { stop ->
      b.add(SearchFieldKind.STRUCTURED, stop.title); b.add(SearchFieldKind.STRUCTURED, stop.sub)
      b.add(SearchFieldKind.STRUCTURED, stop.at); b.add(SearchFieldKind.STRUCTURED, stop.assignee)
      stop.attachments.forEach { attachment ->
        b.add(SearchFieldKind.STRUCTURED, attachment.label)
        b.add(SearchFieldKind.STRUCTURED, attachment.query)
        b.add(SearchFieldKind.STRUCTURED, attachment.url)
        b.add(SearchFieldKind.STRUCTURED, attachment.tel)
      }
    }
  }
  return b.build()
}

private fun sectionDocument(
  section: HubSection,
  hub: Hub,
  checkpoint: SearchCheckpoint,
): SearchDocument? {
  val b = SearchDocumentBuilder(
    SearchDestination.section(hub.id, section.id),
    hub.status == "archived",
    checkpoint,
  )
  b.add(SearchFieldKind.TITLE, section.title)
  b.add(SearchFieldKind.BREADCRUMB, hub.title)
  return b.build()
}

private fun blockDocument(
  block: HubBlock,
  section: HubSection,
  hub: Hub,
  checkpoint: SearchCheckpoint,
): SearchDocument? {
  val b = SearchDocumentBuilder(
    SearchDestination.block(hub.id, section.id, block.id),
    hub.status == "archived",
    checkpoint,
  )
  val payloadTitle = block.payload?.label ?: block.payload?.name ?: block.payload?.items
    ?.firstOrNull()?.let { it.text ?: it.label }
  val bodyTitle = block.bodyMd
    ?.let { safePrefix(it, MAX_BLOCK_TITLE_SOURCE_CODE_UNITS) }
    ?.lineSequence()
    ?.firstOrNull { it.isNotBlank() }
    ?.let(::cleanSearchText)
    ?.takeIf(String::isNotBlank)
  b.add(SearchFieldKind.TITLE, payloadTitle ?: bodyTitle ?: block.type.replaceFirstChar { it.uppercase() })
  b.add(SearchFieldKind.BREADCRUMB, listOfNotNull(hub.title, section.title).joinToString(" · "))
  b.add(SearchFieldKind.BODY, block.bodyMd)
  b.add(SearchFieldKind.STRUCTURED, block.type)
  b.add(SearchFieldKind.STRUCTURED, block.provenance?.source)
  addBlockPayload(b, block.payload)
  block.triggers.orEmpty().forEach { trigger ->
    b.add(SearchFieldKind.STRUCTURED, trigger.geo?.label)
    b.add(SearchFieldKind.STRUCTURED, trigger.whenTrigger?.at)
    b.add(SearchFieldKind.STRUCTURED, trigger.whenTrigger?.relative)
    b.add(SearchFieldKind.STRUCTURED, trigger.whenTrigger?.recurring)
    b.add(SearchFieldKind.STRUCTURED, trigger.activity?.kind)
  }
  return b.build()
}

private const val MAX_BLOCK_TITLE_SOURCE_CODE_UNITS = 240

private fun addBlockPayload(b: SearchDocumentBuilder, payload: BlockPayload?) {
  payload ?: return
  payload.items.orEmpty().forEach { item ->
    b.add(SearchFieldKind.STRUCTURED, item.text); b.add(SearchFieldKind.STRUCTURED, item.label)
    b.add(SearchFieldKind.STRUCTURED, item.due); b.add(SearchFieldKind.STRUCTURED, item.assignee)
    b.add(SearchFieldKind.STRUCTURED, item.amount?.toString())
  }
  b.add(SearchFieldKind.STRUCTURED, payload.label); b.add(SearchFieldKind.STRUCTURED, payload.domain)
  b.add(SearchFieldKind.STRUCTURED, payload.url); b.add(SearchFieldKind.STRUCTURED, payload.name)
  b.add(SearchFieldKind.STRUCTURED, payload.role); b.add(SearchFieldKind.STRUCTURED, payload.phone)
  b.add(SearchFieldKind.STRUCTURED, payload.email); b.add(SearchFieldKind.STRUCTURED, payload.address)
  b.add(SearchFieldKind.STRUCTURED, payload.date); b.add(SearchFieldKind.STRUCTURED, payload.end)
  b.add(SearchFieldKind.STRUCTURED, payload.tz); b.add(SearchFieldKind.STRUCTURED, payload.total?.toString())
  b.add(SearchFieldKind.STRUCTURED, payload.spent?.toString())
}
