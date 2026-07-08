# Checklist `doneBy` → member name — design

**Status:** approved (brainstorm 2026-07-07). Next: implementation plan.

## Problem

When a family member ticks a checklist item, the client stamps `doneBy = the
actor's userId` into the block payload (`ContentStore.enqueueBlockToggle`), and the
done-item byline renders it verbatim: `"✓ usr_9c200a3ccfefa0752f"`. The raw
authenticated ID is both ugly and an accidental internal-ID leak. It should read
`"✓ Patrick"` (or `"✓ You"` for the current user).

By contrast the active-item `assignee` ("Patrick"/"Lillian") already shows a name —
because it is *author-written free text*, a different mechanism (see Non-goals).

## Constraints

- **Content-blind server (ADR 0015/0017).** `doneBy` lives inside the block payload,
  which is opaque to the server (and encrypted at M1). The server cannot resolve it.
- **Identity is a separate, server-known plaintext layer** (auth/family roster). Names
  are NOT content and NOT E2EE — they are account identity.
- The `userId → display_name` roster exists (`GET /families/{fid}/members`) but is
  loaded **only when the Members screen opens** — not where checklists render.
- Client `FamilyMember` carries `uid` + `display_name` only (no email).

## Decision (Approach A — render-time resolution from the roster)

Keep `doneBy` as the userId in content; resolve it to a name **at render time** from
the identity-layer roster. Identity never enters content; a rename self-heals every
byline; the boundary survives the E2EE transition (payload decrypts client-side, the
roster is plaintext client-side, mapping still works).

Rejected — **B, stamp `doneByName` into the payload**: bakes identity into (encrypted)
content, muddies the content/identity separation, and shows stale names after a
rename. Rejected — **Hybrid**: extra moving parts for a fallback the calm text handles.

### 1. Roster available app-wide (eager-load — simplified 2026-07-07)
`state.members` (the `List<FamilyMember>` roster with `display_name`) **already exists
in the store** — it's just only *triggered* on the Members screen today. Make it
available app-wide by **eager-loading** it once memberships resolve
(`AuthEngine.loadMemberships` tail, alongside the existing device/invite resumes), so
`state.members` is populated after sign-in/restore and stays for the process lifetime.
No new DB table or flow. (Rejected the heavier DB-persist path: its only gain is
instant/offline names on a cold start before the first `GET /members`; for a byline the
~1s "a family member" → resolves fallback is fine — YAGNI.)

### 2. Pure resolver (all the logic, unit-testable)
```
displayNameFor(userId: String?, roster: Map<String,String>, selfId: String?): String?
  userId == null          -> null
  userId == selfId        -> "You"
  roster[userId] != null  -> firstNameOf(roster[userId])   // "Patrick Jackson" -> "Patrick"
  else                    -> null                          // former member / not yet synced
```
`firstNameOf(name)` = the first whitespace-delimited token, trimmed.

### 3. Render
The done byline becomes:
```
"✓ " + (displayNameFor(item.doneBy, roster, selfId) ?: "a family member")
```
A `resolveDoneBy: (String?) -> String?` (built from `state.members` + `state.session
.userId`) is threaded to the checklist byline (`ChecklistRow` in `HubScreens.kt`) — one
call site.

## Data flow
`GET /members` → `member` table (DB) → `membersFlow()` → `state.members` → build
`Map<uid, display_name>` + `selfId = session.userId` → `resolveDoneBy` → byline text.
`doneBy` (userId, in the content payload) is the input; the name is never persisted to
content.

## Non-goals
- **`assignee` is untouched.** It is an author-set free-text label (may be a student's
  name, not a family account), not a member reference.
- **No email.** First name from the profile display name only (least PII; the roster
  already has it). Exposing member email is a separate, higher-PII decision — skipped.
- No change to how `doneBy` is stamped (still the actor's userId).

## Fallbacks / edge cases
- **Departed / not-yet-synced member** (`doneBy` not in the roster): `"✓ a family
  member"` (calm, no raw ID). Never render a `usr_…`.
- **Self:** `"✓ You"`.
- **Roster empty on cold start** (before first `GET /members`): falls back to "a family
  member" briefly, then resolves reactively when the roster loads.

## Testing
- Pure-unit: `displayNameFor` / `firstNameOf` truth table (self, known, unknown, null,
  multi-word name, single-word name).
- UI: a done checklist item with a known `doneBy` renders the first name; an unknown
  `doneBy` renders "a family member" and never a `usr_` string.
- Roster persistence: `GET /members` → `member` table → `membersFlow()` emits the map.

## Privacy / security note
Intra-family name display is expected (accountability) and is *less* leaky than the
current raw-userId byline. No cross-tenant exposure (all resolution is within the
caller's own family roster). The content-blind + E2EE posture is unchanged — names are
resolved from the identity layer, never written into content.
