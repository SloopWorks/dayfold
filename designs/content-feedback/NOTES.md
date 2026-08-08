# Smart-content responses — exploration notes

**Status: APPROVED (operator, 2026-08-08) — spec track.** Five-verb response vocabulary
for machine-added content, with rules **stored in Dayfold** so future server-side
processing (curator, subscription routines — ADR 0061) does not re-create similar content.
Open `Index.dc.html` for the hi-fi gap set; `Feedback-Options.dc.html` is the approved
exploration board (light + dark, interactive sheet demo).

## Gap review (post-approval) — closed by the hi-fi set

1. **Scope + who** — the me/family segment on the mute scope step (decided Q2) → `Response-Phone` view `scope`.
2. **Detail view** — respond affordances in the provenance footer; Mark done primary → view `detail`.
3. **Hub block** — hub copy + the “Remove, and don’t re-add” delete pairing → view `hub`.
4. **Swipe escalation** — one-time “Don’t add again?” snackbar action after a swipe-hide → view `swipe`.
5. **Management surface** — personal / family / device-only rules + Done records + run receipt → view `settings`.
6. **Offline** — P1 optimistic vocabulary; “takes effect next run” honesty → view `offline`.

Still open (not blocking): kid/14+ member rights (personal responses assumed yes,
family-wide is adult-only); post-sync undo semantics beyond the rule list; the deferred
Q5 pause-suggestion.

## The gap

Smart content reaches the user from three machines:

| Lane | Who mints it | Today's only recourse |
|---|---|---|
| Derived (ADR 0043) | on-device projection from hub dates/triggers | dismiss = local surfacing-state |
| Curator-authored | Claude skill via CLI | Hide (W5) — local, one card |
| Routine-authored (ADR 0061) | scheduled provider routine → changeset | Hide, or revoke the whole routine |

Hide (ADR 0039) is deliberately **local-only** — syncing hide-state is a who-saw-what
behavioral leak. But hide can't stop the pipeline: the next run happily mints the same
card again. Whack-a-mole. The missing verb is a **preference** — not "remove this card"
but "stop making these."

## Why syncing a mute is OK when syncing hide-state wasn't

A hide is passive *behavior* (telemetry-shaped). A mute is an intentional, user-authored
**policy statement**: first-class, visible in Settings, attributed, editable, deletable.
Same reasoning that makes places/triggers syncable. The server stays content-blind — a
mute row is keyed by the ADR 0043 `subjectRef` (which thus earns a **third job**: dedup
key → deep-link key → suppression key) plus opaque kind/source labels; validation can
reject a changeset op whose subjectRef is muted **mechanically, by ID**, without reading
content. The routine gateway (K1/K3) reads the decrypted preference list pre-run.

## The verb ladder

| Verb | Scope | Lives | Comes back? |
|---|---|---|---|
| **Not now** (snooze) | this occurrence, me | device surfacing-state | yes, tomorrow |
| **Hide for me** (W5, shipped) | this item, me | device | only via Show hidden |
| **Done** (NEW) | task-shaped cards, whole family | **Dayfold completion state on the subjectRef** | no — resolved, remembered across runs |
| **Don't add this again** (NEW) | subject / kind / source, future runs | **Dayfold preference row** | no — rule, until removed |
| **Fix it** (NEW, test) | wrong hub / outdated | structured correction → next run input + run receipt (ADR 0062) | corrected |
| Delete (W4) | family-wide, author-only | — | no |

## Response-option catalog (verdicts)

- **Not now / snooze** — LEAN YES. Device-only, anti-nag decay already exists in the engine.
- **Hide for me** — SHIPPED (W5). Unchanged; stays in the sheet.
- **Don't add this again (this subject)** — LEAN YES. The core new verb.
- **Mark done** — LEAN YES. Completion, not dismissal — wanted content, now resolved.
  Family-visible ("Dad handled this"), recorded on the subjectRef so the next run sees the
  email-sourced task as handled and never re-extracts it. Example: a verified-emergency-
  contact card pulled from a university email that blocks online-account registration —
  after acting on it, Done retires it and future runs won't re-mint from the same source.
  Requires extraction to produce a **stable subject key** from the same source across runs.
- **Stop this kind** ("no traffic cards") — LEAN YES, but **binary and honest**. Never
  "show fewer" — Dayfold has no fuzzy frequency model and won't pretend to.
- **Pause the source (routine)** — LEAN YES via deep-link; the control already exists in
  Smart Briefings. The sheet's third scope rung points there rather than duplicating it.
- **Wrong hub → move** — TEST. A correction, not a suppression; next run learns.
- **Outdated / not accurate → flag** — TEST. Flag + receipt; no chat, no thread.
- **More like this / thumbs-up** — DROP. Engagement farming; the calm constitution bans
  it. Keeping a card *is* the positive signal.
- **"Why am I seeing this?" as a menu item** — DROP. The why/provenance chip already
  answers it — it becomes the *door* to the sheet, not a row inside it.

## Entry points (standard UI grammar)

- **A · Overflow ⋮ → response sheet** — baseline, keyboard/TalkBack-reachable. LEAN.
- **B · The why-chip is the door** — tap "Morning briefing · 7:02" → same sheet. Teaching
  lives where the explanation lives; provenance is already on every smart card. LEAN.
- **C · Swipe-hide escalation** — after swipe-hiding a smart card, the snackbar offers
  "Don't add this again?" once. TEST — cap at one offer per subject, ever; repeat = nag.
- Long-press combined menus — stay DROPPED (W5 decision).

## Pattern precedents (survey, not recreation)

The industry grammar for feed controls is consistent: quick dismiss verb → optional
precision (reason / scope) → toast receipt with undo → a reviewable controls page.
Discover-style "Not interested in X / Don't show from Y" = our subject/source scopes.
Gmail-nudge-style post-hoc "turn off?" = variant C. OS-assistant "Suggest less" living in
per-app settings = our per-routine controls in Smart Briefings.
**Rejected:** reason surveys after every dismiss (nag); fuzzy "see fewer" (dishonest);
"we'll tune your feed" claims (promise only what the rule enforces).

## Done — visibility, byline & notes (operator feedback, 2026-08-08)

- **Removes for everyone: yes.** A resolved task leaves every member's Now on next sync —
  it shouldn't nag the people who didn't do it. No "Mom completed this" notification;
  the byline is the only signal (two-way remote-change rule).
- **Who completed is always captured** — byline + timestamp on the completion state.
- **Durable record:** hub-linked cards write a completed row into their hub
  ("✓ Verify emergency contact · Done by Mom · Aug 8" + note). Hubless cards land in a
  collapsed Done section under Settings › Smart content, next to Muted rules.
- **Done with note:** optional, never demanded. Two flows — card-face pill = instant done,
  receipt row offers "Add note"; the sheet's Mark done row opens a note step with
  "Just done" always one tap away. The note is context twice: for the family (how it was
  resolved) and for the next run (completion + note read as context, not just suppression).

## Placement — where the sheet lives

One sheet, same verbs and order on every surface smart content touches; only the scope
line changes. Swipe is **hide-only everywhere it exists** (W5) — never a direct mute.

| Surface | ⋮ overflow | Provenance chip | Swipe | Notes |
|---|---|---|---|---|
| Now card (primary) | full sheet | full sheet | hide → one-time "don't add again?" offer | the volume surface |
| Detail view | full sheet (top bar) | Respond affordance in provenance footer | — | where "why is this here?" gets asked |
| Hub block | full sheet, hub copy: "Don't add to this hub again" | full sheet | — (fights scroll/reorder) | removing an existing block stays a delete (W4); sheet can pair "Remove, and don't re-add" |
| Notification (Phase B) | — | — | — | Snooze action only; a stored rule deserves the full sheet in-app |

## Persistence contract

| Tier | What | Where | Synced |
|---|---|---|---|
| 0 | snooze, hide, anti-nag decay | device surfacing-state (ADR 0043) | never |
| 1 | mute rules (subject / kind / source, scope me\|family, author, optional expiry) **+ Done completion states (whole family)** | Dayfold preference rows — opaque payload, subjectRef key | yes — read by routine gateway pre-run (muted = skip, done = resolved); changeset validation rejects muted subjectRefs by ID |
| 2 | corrections (wrong-hub, outdated) | structured feedback in next run's input; outcome in run receipt | yes |

Receipts close the loop: snackbar + Undo at act time; "Muted rules" list in Settings ›
Smart content; the run receipt line "skipped N muted subjects" (ADR 0062 surface).

## Scope: me vs family

Routine cards are family-visible, so a mute needs a scope answer:
- **Personal (default):** rule scoped to you; the routine still mints for others and
  excludes you from `audience[]`. Copy: "Won't be added for you — your family's feed is
  unchanged."
- **Family-wide (offered for family-scoped noise, e.g. weather):** attributed ("Muted by
  Mom"), visible to all in Settings, reversible by any adult. Copy: "Won't be added for
  anyone."

Recommend personal-by-default; family-wide behind an explicit second choice, never the
swipe path.

## Open questions — DECIDED (operator, 2026-08-08)

1. Preference-row shape: **dedicated preference rows — whatever syncs cleanest** (opaque
   payload, subjectRef key), not a typed block. E2EE interaction resolved at ADR time.
2. Family-wide mute rights: **any adult sets, any adult removes** — always attributed.
3. Expiry: **forever + easy remove.** No expiry machinery.
4. Derived lane reads the same rule list: **yes** — same rules, enforced on-device.
5. "After N mutes, suggest pausing the routine?": **deferred to later.**
6. W3 overlap: **the structured sheet is the primary, discoverable path.**
