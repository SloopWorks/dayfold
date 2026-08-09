package com.sloopworks.dayfold.client

// ADR 0064 — the Kotlin mirror of apps/api/src/content/subject-ref.ts.
//
//   subject_ref := "card:"   <cardId>
//                | "hub:"    <hubId> ["/section:" <sectionId>] ["/block:" <blockId>]
//                | "kind:"   <cardKind>          -- rule rows only
//                | "source:" <provenanceSource>  -- rule rows only
//
// These two files MUST produce byte-identical strings. A client-minted rule key is matched
// server-side by string equality, so a divergence does not error — it silently stops
// suppressing, which is the worst failure shape available here. Change them together.
//
// A node ref may stop at any level: `hub:<id>` alone is what the derived lane keys a hub
// countdown on, so requiring a block would make that subject unmutable.
//
// Pure, no platform types — this is also the dedup key the ranker groups on (ADR 0043),
// which is why it lives in commonMain next to the deriver rather than in the responses
// feature package.
object SubjectRef {
  fun card(cardId: String): String = "card:$cardId"

  /** Any suffix may be omitted; a blockId without a sectionId is legal. */
  fun node(hubId: String, sectionId: String? = null, blockId: String? = null): String =
    buildString {
      append("hub:").append(hubId)
      if (sectionId != null) append("/section:").append(sectionId)
      if (blockId != null) append("/block:").append(blockId)
    }

  fun kind(kind: String): String = "kind:$kind"

  fun source(source: String): String = "source:$source"

  /**
   * The block id inside a node ref, or null when the subject is not a block (a card, a hub
   * countdown, a class rule). Hide (W5) and delete (W4) both act on a BLOCK id, so a caller
   * needs this to know whether those verbs even apply to the subject in hand.
   *
   * Last marker wins, matching the parser: ids may contain '/' and ':'.
   */
  fun blockIdOf(subjectRef: String): String? {
    val marker = "/block:"
    val at = subjectRef.lastIndexOf(marker)
    if (at == -1) return null
    return subjectRef.substring(at + marker.length).ifEmpty { null }
  }

  /** The card id inside a card ref, or null for any other form. */
  fun cardIdOf(subjectRef: String): String? =
    if (subjectRef.startsWith("card:")) subjectRef.removePrefix("card:").ifEmpty { null } else null
}
