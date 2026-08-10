package com.sloopworks.dayfold.client.features.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sloopworks.dayfold.client.AppState
import com.sloopworks.dayfold.client.DayfoldCommandPort
import com.sloopworks.dayfold.client.HubVisibilityChoice
import com.sloopworks.dayfold.client.ImportDestination
import com.sloopworks.dayfold.client.ImportProposalState
import org.reduxkotlin.compose.SelectorStore
import org.reduxkotlin.compose.selectorState

// CAL-10 (ADR 0063 §6, calendar-import-contract-design.md, Import.dc.html §23-27) — wires the
// stateless import-wizard screens in this package to the store + command port (mirrors
// CalendarReviewHost.kt, WI-446). ADR 0063 is still Proposed — a later WI mounts this from the
// real "Add to Hub" entry point (CalendarReviewHost's onAddToHub) once accepted; [existingHubs] is
// supplied by that future caller (CalendarImportEngine.eligibleDestinationHubs()), keeping this
// host free of its own async load-on-open plumbing.
@Composable
fun CalendarImportHost(
  store: SelectorStore<AppState>,
  commands: DayfoldCommandPort,
  onClose: () -> Unit,
  existingHubs: List<ImportDestinationRow> = emptyList(),
  familyMemberNames: String = "Everyone in your family can see it",
  modifier: Modifier = Modifier,
) {
  val state by store.selectorState { it.calendar.importState }
  val viewerId by store.selectorState { it.session.session?.userId }
  when (val s = state) {
    ImportProposalState.None -> Unit // nothing to render — the caller only mounts this while an import is active

    is ImportProposalState.ChoosingDestination -> CalendarImportDestinationScreen(
      existingHubs = existingHubs,
      onChooseNewHub = { commands.chooseImportDestination(ImportDestination.NewHub()) },
      onChooseExistingHub = { row -> commands.chooseImportDestination(ImportDestination.ExistingHub(row.hubId, row.title, row.role)) },
      onClose = { commands.discardCalendarImport(); onClose() },
      modifier = modifier,
    )

    is ImportProposalState.PreviewingFields -> CalendarImportFieldsScreen(
      proposal = s.proposal,
      descriptionAvailable = true, // spec OD-1 default: representable, opt-in, off
      descriptionIncluded = s.proposal.description != null,
      onToggleDescription = { checked -> commands.setImportDescriptionOptIn(if (checked) DESCRIPTION_ADD_ANYWAY_PLACEHOLDER else null) },
      onBack = commands::backImportStep,
      onNext = commands::proceedImportToAudience,
      modifier = modifier,
    )

    is ImportProposalState.ChoosingAudience -> when (val dest = s.proposal.destination) {
      is ImportDestination.NewHub -> CalendarImportAudienceNewHubScreen(
        visibility = dest.visibility, familyMemberNames = familyMemberNames,
        onSelect = { v ->
          val audience = if (v == HubVisibilityChoice.RESTRICTED) listOfNotNull(viewerId) else emptyList()
          commands.setImportAudience(v, audience)
        },
        onBack = commands::backImportStep, onNext = commands::proceedImportToConfirm,
        modifier = modifier,
      )
      is ImportDestination.ExistingHub -> CalendarImportAudienceExistingHubScreen(
        hubTitle = dest.hubTitle, role = dest.participationRole, namedAudience = emptyList(), widerThanSource = true,
        onBack = commands::backImportStep, onNext = commands::proceedImportToConfirm,
        modifier = modifier,
      )
      null -> Unit
    }

    is ImportProposalState.Confirming -> CalendarImportConfirmScreen(
      proposal = s.proposal, audienceLine = s.proposal.destination.audienceLine(),
      onBack = commands::backImportStep, onConfirm = commands::confirmCalendarImport,
      modifier = modifier,
    )

    is ImportProposalState.Applying -> CalendarImportApplyScreen(
      kind = ImportApplyKind.SAVING, title = "Creating your Hub…", meta = "Saving to Dayfold",
      body = "Adding the fields you approved. Your calendar event is untouched.",
      diff = null, footer = "Everything else stays in your calendar.", actions = emptyList(),
      modifier = modifier,
    )

    is ImportProposalState.Saved -> CalendarImportApplyScreen(
      kind = ImportApplyKind.SAVED, title = s.audienceSummary, meta = s.proposal.title,
      body = "The event and your new Hub are linked on this phone.",
      diff = null, footer = "Sharing later names exactly who will see it.",
      actions = listOf(
        ImportApplyAction("Open Hub", primary = true, onClick = onClose),
        ImportApplyAction("Share with family", primary = false, onClick = onClose),
      ),
      modifier = modifier,
    )

    is ImportProposalState.OfflineQueued -> CalendarImportApplyScreen(
      kind = ImportApplyKind.OFFLINE, title = "You're offline — it'll save when you're back", meta = "Queued on this phone",
      body = "Your reviewed import will be applied the next time Dayfold connects. If the event changes first, you'll get one more look.",
      diff = null, footer = "No duplicate will be created.",
      actions = listOf(ImportApplyAction("OK", primary = true, onClick = onClose)),
      modifier = modifier,
    )

    is ImportProposalState.PermissionLost -> CalendarImportApplyScreen(
      kind = ImportApplyKind.PERMISSION_LOST, title = "Calendar access changed", meta = null,
      body = "Calendar access turned off before this was applied, so the import is on hold.",
      diff = null, footer = "Nothing was added or changed anywhere.",
      actions = listOf(
        ImportApplyAction("Allow in phone settings", primary = true, onClick = {}),
        ImportApplyAction("Discard import", primary = false, onClick = { commands.discardCalendarImport(); onClose() }),
      ),
      modifier = modifier,
    )

    is ImportProposalState.RoleDenied -> CalendarImportApplyScreen(
      kind = ImportApplyKind.ROLE_DENIED, title = "You can't add there anymore", meta = null,
      body = "Your role there changed. Your review is kept — pick another destination.",
      diff = null, footer = "Nothing else was affected.",
      actions = listOf(
        ImportApplyAction("Choose a different Hub", primary = true, onClick = commands::backImportStep),
        ImportApplyAction("New private Hub", primary = false, onClick = { commands.chooseImportDestination(ImportDestination.NewHub()) }),
      ),
      modifier = modifier,
    )

    is ImportProposalState.SourceChanged -> CalendarImportApplyScreen(
      kind = ImportApplyKind.SOURCE_CHANGED, title = "The calendar event changed while you reviewed", meta = null,
      body = "Check the updated details, then confirm again.",
      diff = s.diffs.firstOrNull(), footer = "Nothing is applied from a stale copy.",
      actions = listOf(
        ImportApplyAction("Review updated details", primary = true, onClick = commands::reconfirmCalendarImport),
        ImportApplyAction("Discard import", primary = false, onClick = { commands.discardCalendarImport(); onClose() }),
      ),
      modifier = modifier,
    )

    is ImportProposalState.VersionConflict -> CalendarImportApplyScreen(
      kind = ImportApplyKind.VERSION_CONFLICT, title = (s.proposal.destination as? ImportDestination.ExistingHub)?.let { "${it.hubTitle} changed while you reviewed" } ?: "The Hub changed while you reviewed",
      meta = null, body = "Someone edited that Hub since you started. Refresh, then confirm against the current version.",
      diff = null, footer = "Dayfold never merges silently.",
      actions = listOf(
        ImportApplyAction("Refresh and re-confirm", primary = true, onClick = commands::reconfirmCalendarImport),
        ImportApplyAction("Discard import", primary = false, onClick = { commands.discardCalendarImport(); onClose() }),
      ),
      modifier = modifier,
    )
  }
}

// A placeholder body used only to flip the opt-in checkbox on in the demo host — the real member-
// authored description text is a future field-editing surface; this WI wires the state machine and
// screens, not free-text entry (see the completion summary's flagged interpretations).
private const val DESCRIPTION_ADD_ANYWAY_PLACEHOLDER = " "
