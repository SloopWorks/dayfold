# Invite RSVP — honest affordance (reply-handoff or read-only status)

**Date:** 2026-07-08
**Status:** Design (approved)
**Related:** ADR 0016 (two-way reserved), ADR 0020 (read-only client), ADR 0015/0017 (content-blind)

## Problem

An `invite`-type BriefingCard renders a **Yes / No pill toggle** (`RsvpDisplayRow`)
that looks tappable but does nothing — by design it's a static reflection of the
authored `rsvpState` (no write path; ADR 0020/0016; `CardAction.kt`: "no RSVP/mutate
action exists by design"). Worse, dayfold is content-blind and **not the RSVP system
of record** — an actual reply must be made in the source (email/calendar). With
`rsvpState="none"` the pills read as two dead buttons (seen on the "Morton-Finney
First-Year Retreat" invite). Misleading affordance.

## Goal

Make the invite RSVP honest: **hand off to reply in the source when a reply link
exists; otherwise show a read-only status that names where it came from.** No dayfold
backend write. Remove the fake Yes/No toggle.

## Design

New optional field + one affordance replacing `RsvpDisplayRow`.

### Model
- `InvitePayload.rsvpUrl: String? = null` — author/content-API-set reply target: a web
  RSVP form, a calendar event link, a Gmail thread URL (`https`), or a `mailto:`. The
  "if linking is possible" signal.

### Pure logic (`cards/TypedCardLogic.kt`, unit-tested)
- `inviteReplyAction(rsvpUrl: String?): CardAction?` — `mailto:` → `CardAction.Email`,
  `http(s)://` → `CardAction.OpenUrl`, blank/other → `null` (fail-safe: never a bad scheme).
- `rsvpStatusLabel(rsvpState: String?, host: String?, source: String?): String` —
  status: `yes`→"You're going", `no`→"Declined", else→"Not replied yet"; plus a
  provenance suffix: `host` if present → "· from {host}", else `source=="email"` →
  "· from your email", else no suffix.

### UI (`TypedCards.kt` + `DetailScreen.kt`)
Replace `RsvpDisplayRow(rsvpState)` (feed `InviteCard` and detail `HeroMedia`) with
`RsvpAffordance(invite, source, onAction)`:
- `inviteReplyAction(invite.rsvpUrl) != null` → a single **"Reply / RSVP"** action
  (styled like the existing action pills; leading ↗ affordance) → `onAction(that)`.
  Routes through the vetted `CardAction` handoff (opens the source; no backend write).
- else → a **read-only status chip** (non-interactive `Surface`, `clearAndSetSemantics`
  with `contentDescription = the label`) showing `rsvpStatusLabel(...)`.

Delete `RsvpPill` and `RsvpDisplayRow`. Keep the coral invite card bg + kicker.

## Testing

- **Pure unit:** `inviteReplyAction` (mailto/http/blank/bad-scheme); `rsvpStatusLabel`
  (yes/no/none × host/email/none).
- **Snapshot:** the invite feed + `detail`-invite goldens shift (Yes/No → chip or reply
  button). Update `SampleData`/`SnapshotStates` invite fixtures to cover BOTH branches
  (one invite with `rsvpUrl` → reply button; one without → status chip); re-record both
  OS golden sets.
- **On-device (Pixel):** the Morton-Finney invite shows an honest status chip (no reply
  link authored) — no dead Yes/No; an invite authored with `rsvpUrl` shows "Reply / RSVP"
  that hands off.

## Rollout

No ADR (stays within ADR 0020 read-only + 0016 reserved — a handoff-out, not a backend
write). CHANGELOG (client). Author-side: the content API/curator may now set
`invite.rsvpUrl`; absent it, invites degrade to the honest status chip.
