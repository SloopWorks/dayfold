package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** spec §3.5 — the full state-machine transition table: every forward step, Back at every step,
 *  Discard from anywhere, and each of the 7 apply-state entries/exits. A stale action arriving in
 *  an unreachable state (e.g. ChooseImportDestination while Applying) must be a strict no-op — the
 *  machine never corrupts under an out-of-order dispatch. */
class CalendarImportReducerTest {
  private fun proposal(destination: ImportDestination? = null) = CalendarImportProposal(
    proposalId = "prop-1", title = "Grandma's 80th lunch",
    start = EventInstant.Timed("2026-06-28T12:00:00-07:00"), end = null,
    timezone = "America/Los_Angeles", location = null, destination = destination,
  )

  private fun ids() = ImportOpIds("hub-1", "sec-1", listOf("blk-1"))

  @Test fun `StartCalendarImport always resets to ChoosingDestination, even mid-flow`() {
    val mid = ImportProposalState.Applying(proposal(), ids())
    val next = reduceCalendarImport(mid, StartCalendarImport(proposal()))
    assertIs<ImportProposalState.ChoosingDestination>(next)
  }

  @Test fun `the forward path walks destination to fields to audience to confirm`() {
    var s: ImportProposalState = ImportProposalState.ChoosingDestination(proposal())
    val dest = ImportDestination.NewHub(HubVisibilityChoice.RESTRICTED, listOf("me"))

    s = reduceCalendarImport(s, ChooseImportDestination(dest))
    assertIs<ImportProposalState.PreviewingFields>(s)
    assertEquals(dest, (s as ImportProposalState.PreviewingFields).proposal.destination)

    s = reduceCalendarImport(s, SetImportDescriptionOptIn("Bring a dessert"))
    assertEquals("Bring a dessert", (s as ImportProposalState.PreviewingFields).proposal.description)

    s = reduceCalendarImport(s, ProceedImportToAudience)
    assertIs<ImportProposalState.ChoosingAudience>(s)

    s = reduceCalendarImport(s, SetImportAudience(HubVisibilityChoice.FAMILY, emptyList()))
    val updatedDest = (s as ImportProposalState.ChoosingAudience).proposal.destination as ImportDestination.NewHub
    assertEquals(HubVisibilityChoice.FAMILY, updatedDest.visibility)

    s = reduceCalendarImport(s, ProceedImportToConfirm)
    assertIs<ImportProposalState.Confirming>(s)
  }

  @Test fun `SetImportAudience is a no-op for an ExistingHub destination (no editable audience there)`() {
    val dest = ImportDestination.ExistingHub("hub-existing", "Beach Week", "contributor")
    val s = ImportProposalState.ChoosingAudience(proposal(dest))
    val next = reduceCalendarImport(s, SetImportAudience(HubVisibilityChoice.FAMILY, listOf("x")))
    assertSame(s, next)
  }

  @Test fun `Back walks the wizard steps in reverse, then discards from ChoosingDestination`() {
    val dest = ImportDestination.NewHub()
    var s: ImportProposalState = ImportProposalState.Confirming(proposal(dest))
    s = reduceCalendarImport(s, BackImportStep); assertIs<ImportProposalState.ChoosingAudience>(s)
    s = reduceCalendarImport(s, BackImportStep); assertIs<ImportProposalState.PreviewingFields>(s)
    s = reduceCalendarImport(s, BackImportStep); assertIs<ImportProposalState.ChoosingDestination>(s)
    s = reduceCalendarImport(s, BackImportStep); assertEquals(ImportProposalState.None, s)
  }

  @Test fun `Back from RoleDenied returns to ChoosingDestination with the review intact`() {
    val s = ImportProposalState.RoleDenied(proposal(null))
    val next = reduceCalendarImport(s, BackImportStep)
    assertIs<ImportProposalState.ChoosingDestination>(next)
    assertEquals("Grandma's 80th lunch", next.proposal.title)
  }

  @Test fun `Back is a no-op while Applying (in-flight, no going back)`() {
    val s = ImportProposalState.Applying(proposal(ImportDestination.NewHub()), ids())
    assertSame(s, reduceCalendarImport(s, BackImportStep))
  }

  @Test fun `Discard returns to None from any state`() {
    for (s in listOf(
      ImportProposalState.ChoosingDestination(proposal()),
      ImportProposalState.Applying(proposal(ImportDestination.NewHub()), ids()),
      ImportProposalState.Saved(proposal(), "Hub created — only you can see it"),
    )) {
      assertEquals(ImportProposalState.None, reduceCalendarImport(s, DiscardCalendarImport))
    }
  }

  @Test fun `ImportApplyStarted moves Confirming to Applying and is a no-op elsewhere`() {
    val confirming = ImportProposalState.Confirming(proposal(ImportDestination.NewHub()))
    val applying = reduceCalendarImport(confirming, ImportApplyStarted(ids()))
    assertEquals(ImportProposalState.Applying(confirming.proposal, ids()), applying)

    val stale = reduceCalendarImport(ImportProposalState.None, ImportApplyStarted(ids()))
    assertEquals(ImportProposalState.None, stale)
  }

  @Test fun `ImportSaved moves Applying to Saved carrying the actual resulting audience`() {
    val applying = ImportProposalState.Applying(proposal(ImportDestination.NewHub()), ids())
    val saved = reduceCalendarImport(applying, ImportSaved("Hub created — only you can see it"))
    assertEquals(ImportProposalState.Saved(applying.proposal, "Hub created — only you can see it"), saved)
  }

  @Test fun `ImportRoleDenied clears the destination but keeps every other reviewed field`() {
    val dest = ImportDestination.ExistingHub("hub-x", "Beach Week", "contributor")
    val confirming = ImportProposalState.Confirming(proposal(dest).copy(description = "kept"))
    val denied = reduceCalendarImport(confirming, ImportRoleDenied)
    assertIs<ImportProposalState.RoleDenied>(denied)
    assertNull(denied.proposal.destination)
    assertEquals("kept", denied.proposal.description)
  }

  @Test fun `ImportOfflineQueued and ImportPermissionLost preserve the proposal from any carrying state`() {
    val confirming = ImportProposalState.Confirming(proposal(ImportDestination.NewHub()))
    val offline = reduceCalendarImport(confirming, ImportOfflineQueued(null))
    assertEquals(ImportProposalState.OfflineQueued(confirming.proposal, null), offline)

    val lost = reduceCalendarImport(confirming, ImportPermissionLost)
    assertEquals(ImportProposalState.PermissionLost(confirming.proposal), lost)
  }

  @Test fun `ImportSourceChanged and ImportVersionConflict carry ids and diagnostic payload`() {
    val confirming = ImportProposalState.Confirming(proposal(ImportDestination.NewHub()))
    val diffs = listOf(ImportFieldDiff("start", "12:00 PM", "12:30 PM"))
    val refreshed = confirming.proposal.copy(title = "Updated title")
    val changed = reduceCalendarImport(confirming, ImportSourceChanged(ids(), diffs, refreshed))
    assertEquals(ImportProposalState.SourceChanged(refreshed, ids(), diffs), changed)

    val conflict = reduceCalendarImport(confirming, ImportVersionConflict(ids(), listOf("pat", "maya")))
    assertEquals(ImportProposalState.VersionConflict(confirming.proposal, ids(), listOf("pat", "maya")), conflict)
  }

  @Test fun `ReconfirmCalendarImport re-enters Confirming from SourceChanged or VersionConflict, retaining ids implicitly via a fresh confirm`() {
    val p = proposal(ImportDestination.NewHub())
    val sourceChanged = ImportProposalState.SourceChanged(p, ids(), emptyList())
    assertEquals(ImportProposalState.Confirming(p), reduceCalendarImport(sourceChanged, ReconfirmCalendarImport))

    val conflict = ImportProposalState.VersionConflict(p, ids(), listOf("pat"))
    assertEquals(ImportProposalState.Confirming(p), reduceCalendarImport(conflict, ReconfirmCalendarImport))

    // no-op elsewhere
    assertEquals(ImportProposalState.None, reduceCalendarImport(ImportProposalState.None, ReconfirmCalendarImport))
  }

  @Test fun `a stale ChooseImportDestination while Applying is a no-op — the machine never corrupts`() {
    val applying = ImportProposalState.Applying(proposal(ImportDestination.NewHub()), ids())
    assertSame(applying, reduceCalendarImport(applying, ChooseImportDestination(ImportDestination.NewHub())))
  }

  @Test fun `family switch resets importState alongside the calendar check slice`() {
    val state = AppState(
      session = SessionState(session = Session("sec", "refresh"), activeFamilyId = "fam1"),
      calendar = CalendarState(importState = ImportProposalState.Applying(proposal(ImportDestination.NewHub()), ids())),
    )
    val next = rootReducer(state, MembershipsLoaded(listOf(FamilyMembership("fam2", "Fam 2", "adult", "active"))))
    assertEquals(ImportProposalState.None, next.calendar.importState)
  }

  @Test fun `sign-out resets importState`() {
    val state = AppState(calendar = CalendarState(importState = ImportProposalState.Applying(proposal(ImportDestination.NewHub()), ids())))
    val next = rootReducer(state, SignedOut)
    assertEquals(ImportProposalState.None, next.calendar.importState)
  }
}
