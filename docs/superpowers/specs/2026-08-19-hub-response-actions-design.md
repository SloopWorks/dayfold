# Hub completion actions — implementation design

**Status:** implemented and verified on Android and iOS; narrows the already-approved ADR 0064 and
`designs/content-feedback/Response-Phone.dc.html` Hub view to the completion
behavior that the current API can represent truthfully.

## Problem

Now cards can choose **Mark done**, while Hub blocks expose only **Hide for me**
and author-only, confirmed **Delete** actions. Hub completion is already part of
ADR 0064, but the client entry point and completed-item presentation are missing.

The broader dormant Hub response vocabulary cannot simply be enabled:

- a kind-scoped mute cannot reliably target one Hub with the current block data;
- delete-and-mute is not an atomic or ordered operation;
- the response write endpoint does not yet verify that a concrete card or block
  is visible to the caller;
- a completed Hub item needs a durable byline, timestamp, and optional note so
  the action does not look like unexplained disappearance.

## Scope and behavior

- A visible Hub block's overflow menu gains **Mark done** above the existing
  **Hide for me** and author-only **Delete** actions.
- **Mark done** opens the shared response overlay directly on its optional-note
  step. **Just done** and **Save note** both complete the item for the family.
- The response subject is the canonical block key:
  `SubjectRef.node(hubId, sectionId, blockId)`.
- The label comes from a sanitized, bounded `blockPreviewText`; the rule kind is
  the block type and source is the block provenance source.
- An optimistic Done response immediately suppresses the live block and adds a
  compact completed row containing **Done by _member_ · _date_** plus the note
  when present. Synced rows use their server creation timestamp.
- The existing local Hide gesture/menu and confirmed author-only Delete flow are
  unchanged. Mute and delete-plus-mute are not exposed by this slice.
- The synthetic Timeline card has no server-backed block subject and does not
  gain a completion action.

## Implementation shape

- Add the response surface and initial step to `CardAction.Respond`, with Now as
  the compatibility default.
- Route Hub overflow through the existing `CardAction` channel. `HubBlockCard`
  remains render-only.
- Mount one root-connected `ResponseOverlay` from `FeedApp`, rather than separate
  copies in Now and Hubs. This also makes the existing Detail response entry
  point visible. Keep one persistent modal host while step content changes.
- An explicit in-sheet Back action returns to the parent step when there is one;
  direct Hub entry has no parent and closes. System Back, scrim taps, and downward
  dismissal gestures always dismiss the modal, matching platform convention.
- Carry `createdAt` through the response wire model, local store, SQL migration,
  and optimistic write. Project Done responses for the open Hub into a completed
  section and suppress matching live blocks before rendering.
- Do not offer Undo for Done: after server tombstoning, deleting the response
  cannot restore the subject. The completion sheet remains an explicit confirm.
- A successful write clears the local pending flag immediately. A terminally
  rejected write removes the optimistic response so a failed completion cannot
  leave a ghost Done card. If another device won the completion race, the API
  returns the already-authorized canonical Done row and the client atomically
  replaces its optimistic row, independent of timestamp cursors.
- During a rolling API deployment, an older server may return the typed conflict
  without the canonical row. The client retains a pending suppressor until retry,
  but erases its losing member attribution and note rather than presenting them
  as the family's completion history. A cached or later-arriving canonical row
  removes the duplicate even if retry already reached its cap; otherwise retry stops at the
  normal cap and leaves one non-pending anonymous completion with no invented winner time.
- Stale-cursor and client-schema full rebuilds replace acknowledged response rows but
  preserve optimistic rows beside the outbox operations those rebuilds already preserve.
- Optimistic response removal stores a device-local rollback snapshot in its DELETE
  outbox row. A terminal rejection restores the exact rule after the sync cursor has
  advanced, rather than silently diverging from server truth. A successful DELETE
  physically removes any response row rehydrated by a racing full sync before its ack.
- UI response commands capture one `FamilySessionContext` and are admitted only to
  that family's replaceable runtime scope. Tenant replacement closes admission and
  cancels/joins the scope before cache cleanup. Mute Undo appends a compensating
  DELETE with an explicit dependency on the original PUT rather than relying on wall-clock
  or operation-id ordering; a 204 PUT replay
  removes an orphan optimistic row, while a DELETE 404 drops private rollback data.
- Done responses suppress stale source content in foreground, background, and
  exact-notification planning. Reconciliation cancels both delivered and already-
  scheduled notifications for completed subjects on Android and iOS, and both
  platform hosts observe response- and authored-card-table changes so cancellation
  or re-planning happens immediately; foreground-visible subjects retract their delivered
  banner without cancelling a future timed reminder. iOS serializes exact replacements with immediate background posts,
  adds before pruning stale requests, migrates legacy raw identifiers, removes only stale or
  completed delivered exact history (a fired-but-unread reminder remains while its source is active),
  applies cap/dedup per trigger's local date, and preserves the prior plan when a replacement add
  fails or UIKit expires any retained pass in the serialized queue. Identity teardown and family replacement clear delivered
  notifications, pending requests, Android exact alarms, and geofences on both platforms;
  iOS performs that geofence teardown synchronously on main and invalidates a pending
  nearest-place location callback. Android boot/package replacement restores both geofences
  and exact alarms, retracting an Android child also removes the now-stale grouped summary,
  and iOS notification entry points follow the foreground-selected cache so simulator fake
  content and notifications cannot diverge.
- Android notification children use `(full subject tag, stable child id)` and exact/tap
  PendingIntents use full encoded subject URIs, so Java hash collisions cannot merge them.
  Geofence and exact-alarm background passes share one process-wide snapshot → post → ledger
  critical section, and exact-alarm reconciliation replaces the tracked set so removed triggers,
  deleted sources, mutes, and disabled notifications cannot leave obsolete wakes armed.
  Geofence replace/remove calls run through one generation-aware queue; teardown invalidates
  late adds and awaits the final removal before cache wipe. Exact-alarm denial falls back to
  `setAndAllowWhileIdle`, opens OS special-access guidance once after opt-in, and re-reconciles
  on the permission-change broadcast. Notification logs record only platform-accepted posts,
  so denial or posting failure cannot exhaust the daily cap. iOS reads fresh Notification
  Center settings for every background pass and reports accepted subjects only from request
  completion callbacks, avoiding a cold-launch race with the permission controller's cache.
  One process-global generation fence is invalidated by Done and identity cleanup, checked before
  every enqueue, and held through ledger recording; stale callbacks retract late accepted requests.
  Region-triggered passes capture that generation before reading the selected cache, retain a UIKit
  background task through asynchronous permission/enqueue/ledger completion, and execute serially so
  concurrent region callbacks cannot plan against the same unspent daily-cap ledger. Expiration and
  family teardown atomically invalidate and release every admitted or queued pass.

## Security and privacy

- Before a concrete card or block response is upserted, the API resolves the
  exact subject within the caller's family and applies the same visibility gate
  used by the read path. Missing, deleted, mismatched, or hidden subjects return
  the same `404` response.
- A block response requires a live parent Hub visible to the caller. Completion
  is a family response action, so it does not require contributor/author status.
- Existing `content:write` and user-id checks remain in force.
- Completion creation and source tombstoning are one transaction, backed by a
  live-subject uniqueness constraint, so racing devices cannot create two Done
  records for one subject. A response id owned by another member cannot be
  overwritten.
- Idempotent replay is bound to the exact recorded response and re-applies the
  current response ACL; an operation key is never a read capability. Subject
  writers use a pinned transaction and transaction-scoped advisory lock, which
  remains valid behind the production transaction-mode pooler. Block writes also
  lock their section and reject if its authorized Hub changed concurrently. Block and
  section moves lock old and new subject identities, so Done cannot race a live node onto
  another path; both moves require write authority on source and destination Hubs.
- Response reads and sync use the same current Hub/card audience rules as content
  reads. Audience changes touch historical response rows so revocation emits a
  tombstone and a later grant can backfill the completion.
- Personal ownership never bypasses a concrete subject ACL. Existing response IDs are
  checked against immutable identity—including tombstoned rows—before any update, card
  audience changes retouch matching responses, and a section move rekeys every child block
  inside the same transaction.
- No new analytics or log field is introduced. Notes retain ADR 0064's existing
  plaintext-at-M0 posture.

## Accessibility and layout

- The new menu item uses the existing 48dp action-row target and explicit Done
  icon/label semantics.
- The note step is vertically scrollable, IME-aware, and its buttons stack when
  width or font scale would make a horizontal row unsafe.
- The direct completion step has an explicit 48dp back/dismiss affordance.
- Completed rows expose one coherent accessibility description containing the
  state, member, date, and note.
- Overflow buttons reserve a dedicated trailing lane and identify their subject;
  receipt messages clear the bottom navigation and announce through a polite live
  region.

## Test plan

1. API authorization matrix: visible/restricted card and block subjects,
   missing/deleted/mismatched subjects, owner/member callers, and idempotent replay.
2. Storage/model migration: response `createdAt` survives network, persistence,
   restart, and optimistic creation.
3. UI action test: Hub overflow emits a Hub response action with exact subject,
   title, type, source, and direct Done-note step.
4. Routing/reducer tests: surface/step are preserved; explicit child-step Back
   navigates up, modal dismissal paths close the sheet, and Done receipts are not
   undoable.
5. Selector/render tests: a Done response suppresses its live block and produces
   the correct byline/date/note without leaking content from another Hub.
6. Notification regressions: a Done response suppresses stale cached source rows,
   cancels a delivered reminder, and retracts a future exact schedule.
7. Convergence regressions: rejected response DELETE restores the synced rule;
   acknowledged response DELETE wins over a racing full-sync row; canonical Done arrival
   removes a pending duplicate; legacy conflict retries cap.
8. Runtime/platform regressions: response commands enter the captured family owner;
   PUT→Undo→DELETE converges before and after acknowledgment; DELETE 404 and PUT 204
   cannot restore private/orphan rows; Java-hash collisions retain distinct Android
   notifications and exact-alarm identities; geofence replacements and family cleanup
   cannot interleave; denied/failed posts do not consume the cap.
9. Compose/device checks at compact width and increased font scale, plus existing
   Now, Detail, Hide, Delete-confirmation, and navigation regressions.
10. Run client/UI/API tests, Android debug assembly, and iOS simulator compile/link;
   then exercise the flow on both emulators.

## Definition of done

- Any member who can see a Hub block can mark it done with or without a note.
- The item immediately moves into a durable, attributable completed presentation.
- A caller cannot write a response for a concrete subject they cannot see.
- Existing confirmed Hide/Delete behavior and Now responses remain green.
- Android and iOS launch and complete the flow without layout, navigation,
  persistence, or accessibility regressions.

## Verification record

- API: full Vitest suite green (495 passed, 3 skipped), including restricted-Hub
  read/sync revocation, forged-id protection, deterministic migration cleanup,
  ACL-bound idempotent replay, durable Done deletion, concurrent same-ID creation,
  and concurrent Done/content authoring/section movement. Subject and stable topology
  identities share one pinned transaction and transaction-scoped lock, including the
  one-connection serverless pool configuration. The response suppression/race suite is
  also green with the production
  `VERCEL=1` one-connection pool setting (75 focused tests, with the deliberately
  two-connection same-ID lock probe skipped there).
- Shared client/UI: full desktop suites green, including conflict-attribution and
  response-aware background/exact-notification regressions, pending-response cache-heal
  preservation, response-DELETE/full-sync convergence, per-day iOS exact caps, and
  identity/family notification cleanup; iOS notification-store routing, post-fence
  invalidation, fired-but-unread exact retention, retained serialized immediate/exact passes,
  atomic queued-pass expiration, exact-lane-only replacement,
  and failure-safe two-phase family teardown
  have native/host coverage, and device/simulator Kotlin compilation is green. macOS Hub
  goldens were re-recorded and reviewed. The prescribed
  7 GB Linux recording container lost its Gradle daemon while compiling, so the
  corresponding Linux goldens remain pending CI-capable x86_64 infrastructure.
- Android: debug build installed on the API-37 foldable emulator; Hub overflow,
  direct note step, completion receipt, and durable **Done by You · Aug 19** card
  exercised against the stateful fake backend. The wider audit also verified
  readable due/milestone dates, portable image fallback labels, external-action
  cards, and compact contact layout. Boot/package replacement re-arms both geofence
  and exact-reminder lanes.
  Eighteen connected Android platform tests additionally verify local delivery, group
  retraction, collision-proof notification/alarm identity, serialized geofence replacement,
  and teardown invalidation on an API-35 emulator.
- iOS: simulator host compiles, links, installs, and launches. The same Hub
  overflow → note → completion receipt → durable Done flow was exercised in the
  simulator against the stateful fake backend.

## Limits

- Hub-scoped muting and atomic delete-plus-mute require a separate data/API
  design and are deliberately not advertised here.
- This slice does not add response actions to the synthetic Hub Timeline card.
