package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ADR 0064 — one sheet, same verbs and order on every surface; only the copy changes
// (NOTES.md § Placement). The strings here are the design's, verbatim: this is the file that
// notices if someone paraphrases user-facing copy.
class ResponseSheetTest {

  @Test
  fun everySurfaceKeepsTheSameVerbOrder() {
    assertEquals(
      listOf(Verb.DONE, Verb.HIDE, Verb.MUTE),
      verbRowsFor(ResponseSurface.NOW).map { it.verb },
    )
    assertEquals(
      listOf(Verb.DONE, Verb.HIDE, Verb.MUTE),
      verbRowsFor(ResponseSurface.DETAIL).map { it.verb },
    )
    // The hub adds the delete pairing as a FOURTH row — it does not reorder the first three.
    assertEquals(
      listOf(Verb.DONE, Verb.HIDE, Verb.MUTE, Verb.DELETE_AND_MUTE),
      verbRowsFor(ResponseSurface.HUB).map { it.verb },
    )
  }

  @Test
  fun markDoneIsThePrimaryAndSaysWhatItMeans() {
    val done = verbRowsFor(ResponseSurface.NOW).single { it.verb == Verb.DONE }
    assertEquals("Mark done", done.title)
    assertEquals("Completes it for your family — future runs remember", done.subtitle)
    assertTrue(done.emphasized)
  }

  @Test
  fun hideNamesItsBlastRadiusPerSurface() {
    assertEquals("Your family still sees it", verbRowsFor(ResponseSurface.NOW).single { it.verb == Verb.HIDE }.subtitle)
    assertEquals("Your family still sees it in this hub", verbRowsFor(ResponseSurface.HUB).single { it.verb == Verb.HIDE }.subtitle)
  }

  @Test
  fun hubCopyNamesTheHub() {
    val mute = verbRowsFor(ResponseSurface.HUB).single { it.verb == Verb.MUTE }
    assertEquals("Don't add to this hub again", mute.title)
    assertEquals("This block stays; nothing similar is re-added", mute.subtitle)
  }

  @Test
  fun theDeletePairingIsHubOnlyAndReadsAsADelete() {
    assertTrue(verbRowsFor(ResponseSurface.NOW).none { it.verb == Verb.DELETE_AND_MUTE })
    val row = verbRowsFor(ResponseSurface.HUB).single { it.verb == Verb.DELETE_AND_MUTE }
    assertEquals("Remove, and don't re-add", row.title)
    assertEquals("Removes it for everyone — pairs delete with the rule", row.subtitle)
    assertTrue(row.destructive)   // coral, like every delete
  }

  // The calm constitution bans engagement farming: keeping a card IS the positive signal.
  @Test
  fun thereIsNoPositiveSignalRow() {
    val banned = listOf("more like", "show more", "thumbs", "see fewer", "show fewer")
    ResponseSurface.entries.forEach { surface ->
      verbRowsFor(surface).forEach { row ->
        val text = (row.title + " " + row.subtitle).lowercase()
        banned.forEach { phrase ->
          assertTrue(!text.contains(phrase), "row '${row.title}' on $surface uses banned copy: $phrase")
        }
      }
    }
  }

  // "Why am I seeing this?" is answered by the provenance chip, which is the DOOR to this
  // sheet — never a row inside it.
  @Test
  fun thereIsNoWhyRow() {
    ResponseSurface.entries.forEach { surface ->
      assertTrue(verbRowsFor(surface).none { it.title.contains("Why", ignoreCase = true) })
    }
  }

  @Test
  fun theSubjectLineCarriesProvenanceWhenThereIsSome() {
    val withSource = ResponseSheetState(
      subjectRef = "card:c1", subjectTitle = "Rain at soccer practice", reasonKind = "weather",
      source = "Morning briefing", surface = ResponseSurface.NOW,
    )
    assertEquals("Rain at soccer practice · added by Morning briefing", subjectLineFor(withSource))
    assertEquals("Rain at soccer practice", subjectLineFor(withSource.copy(source = null)))
  }
}
