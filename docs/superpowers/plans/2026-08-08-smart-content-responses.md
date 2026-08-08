# Responses to Smart Content — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the five-verb response vocabulary for machine-added content — *not now*, *hide for me*, *mark done*, *don't add this again*, *fix it* — so that muting and completion are **stored rules** the authoring pipeline reads before it mints anything, instead of local dismissals the next run overwrites.

**Architecture:** One new synced entity, `content_response`, keyed by a canonical `subject_ref` string. The server stores it and enforces it **mechanically, by ID string equality** against columns it already has (`subject_ref`, `kind`, `provenance->>'source'`) — it never reads content to decide. The client writes responses through the existing ADR 0039 typed-op outbox (optimistic, offline-safe), receives them back through the existing `/sync` merged cursor as a new row `type`, and applies the same rule list on-device to the ADR 0043 derived lane, which the server never sees. The UI is one bottom sheet with the same verbs and order on every surface, reached from `⋮`, from the provenance chip, and once-ever from a swipe-hide snackbar.

**Tech Stack:** TypeScript/Hono + Postgres (`apps/api`), Kotlin Multiplatform + redux-kotlin (`apps/client`), Compose Multiplatform (`apps/ui`), SQLDelight (client DB), Kotlin/JVM (`apps/cli`), vitest (API), kotlin.test (client/ui), `rk snapshot` golden PNGs (UI).

---

## Governance gate — read before Task 1

**This plan is not build-authorized until Task 0 lands.** The set proposes ADR-class behavior under `CLAUDE.md` "Hard guardrails" (customer-data handling) and the ADR-class list (data handling, automation-autonomy boundaries): a new **synced** preference row, family-wide completion state, and a server-side suppression path. Design sign-off (2026-08-08) does not authorize build — ADR 0008 clears the *design* gate only.

Task 0 writes the Proposed ADR. **Do not start Task 1 until the operator accepts it.** If the operator amends the persistence contract, re-run the self-review at the end of this plan against the amended ADR before continuing.

## Scope note — this is three shippable features, not one

Per the writing-plans scope check: Phases A–C ship the **mute** verb end-to-end and are worth merging on their own; Phase D adds **Done**; Phase E adds the derived-lane and pipeline integration. **Fix it** (wrong-hub / outdated corrections) is marked TEST — not LEAN YES — in `NOTES.md`, has no persistence contract beyond "structured feedback in the next run's input", and depends on ADR 0062's run receipt, which is Proposed and unbuilt. **It is deliberately out of scope here** and gets its own plan once the routine gateway exists. Task 18 leaves the seam for it and nothing more.

Each phase ends green and merges independently.

---

## Global Constraints

- **JDK 17** for every Gradle build: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`. Kotlin 2.3.20 · Compose-MP 1.11.1 · AGP 9.2.1 · Gradle 9.4.1 · compileSdk 37 · redux-kotlin `1.0.0-alpha05` · Node 24 + local Postgres.
- **Server stays content-blind.** No server code may branch on the *meaning* of a label, note, title, or body. Every suppression decision is string equality or prefix equality on an opaque identifier column. A reviewer rejecting a task for reading content is correct.
- **Tier 0 never syncs.** Snooze, hide, anti-nag decay, and the once-ever swipe-offer flag stay in device surfacing-state (ADR 0043 §2b, `Content.sq` surfacing tables). They must not appear in `Changes`, `applyDelta`, or the `outbox`.
- **Tier 1 syncs.** Mute rules and Done completion states are `content_response` rows: opaque payload, `subject_ref` key, attributed, removable.
- **Mute default is personal.** `audience_scope` defaults to `personal`. Family-wide is only ever reachable by an explicit second choice, never from the swipe path (`NOTES.md` § Scope: me vs family).
- **Family-wide rights:** any adult sets, any adult removes; always attributed (decided Q2).
- **No expiry machinery** (decided Q3). A rule lives until removed. Do not add an `expires_at` column.
- **Never "show fewer."** Copy is binary and honest. No fuzzy frequency language, no "we'll tune your feed", no thumbs-up / more-like-this affordance anywhere.
- **Migrations are forward-only plain SQL in one txn, no `IF NOT EXISTS`** (ADR 0033). Numbered next in sequence after `0019_device_grant_scopes.sql`.
- **Commit style:** normal prose, not caveman. Branch from latest `main`.
- **Copy strings are fixed by the design and quoted verbatim in the tasks that use them.** Do not paraphrase user-facing text.

### Canonical `subject_ref` grammar (used by server, client, and CLI — define once, never re-derive)

```
subject_ref := "card:"   <cardId>
             | "hub:"    <hubId> [ "/section:" <sectionId> ] [ "/block:" <blockId> ]
             | "kind:"   <cardKind>          -- e.g. kind:weather
             | "source:" <provenanceSource>  -- e.g. source:morning-briefing
```

The first two forms identify **a subject**; the last two identify **a class of subject** and only ever appear on a rule row, never on a content row. This is the ADR 0043 `subjectRef` picking up its third job: dedup key → deep-link key → **suppression key**.

### Match semantics (the whole enforcement contract, in one table)

| `match_scope` | rule `subject_ref` looks like | a content row matches when |
|---|---|---|
| `subject` | `card:c_123` / `hub:h_9/block:b_4` | `row.subject_ref == rule.subject_ref` |
| `kind` | `kind:weather` | `'kind:' || row.kind == rule.subject_ref` |
| `source` | `source:morning-briefing` | `'source:' || (row.provenance->>'source') == rule.subject_ref` |

All three are string equality on columns the server already stores as opaque identifiers. **There is no fourth match scope.** The sheet's third rung ("Everything from Morning briefing") deep-links to the Smart Briefings pause control instead of minting a rule — the control already exists, and duplicating it would create two sources of truth for one behavior.

---

## File Structure

**Server (`apps/api`)**

| File | Responsibility |
|---|---|
| `migrations/0020_content_responses.sql` | NEW — `subject_ref` columns + `content_responses` table |
| `src/content/subject-ref.ts` | NEW — build/parse/validate the grammar above; the only place the format is known |
| `src/content/responses.ts` | NEW — repo + match predicate for `content_response` rows |
| `src/app.ts` | MODIFY — `PUT`/`DELETE /families/:fid/responses/:id`, `/sync` emits `type:"response"`, write paths call the suppression gate |
| `src/repo.ts` | MODIFY — `syncContent` unions the responses table |
| `src/content/write-guard.ts` | MODIFY — `suppressedBy()` gate shared by card + block write paths |

**Client logic (`apps/client`)**

| File | Responsibility |
|---|---|
| `.../db/Content.sq` | MODIFY — `content_response` table + queries; `subject_ref` on `card`/`hub_block` |
| `.../client/SubjectRef.kt` | NEW — the Kotlin mirror of the grammar (pure) |
| `.../client/features/responses/ResponseModel.kt` | NEW — `ContentResponse`, enums, `ResponseState` |
| `.../client/features/responses/ResponseActions.kt` | NEW — action types |
| `.../client/features/responses/ResponseReducer.kt` | NEW — pure reducer |
| `.../client/features/responses/ResponseRules.kt` | NEW — pure match + suppression over `NowItem`/blocks |
| `.../client/features/responses/ResponseSelectors.kt` | NEW — Settings list + sheet-model projections |
| `.../client/ResponseEngine.kt` | NEW — runtime-owned effects (ADR 0058): enqueue op, optimistic DB write, undo |
| `.../client/Model.kt` | MODIFY — `responses: ResponseState` on `AppState` |
| `.../client/Reducer.kt` | MODIFY — delegate the slice |
| `.../client/ContentStore.kt` | MODIFY — response DB reads/writes + delta apply |
| `.../client/SyncClient.kt` | MODIFY — `putResponse` / `deleteResponse` |
| `.../client/SyncEngine.kt` | MODIFY — drain dispatch for `targetKind == "response"` |
| `.../client/NowRank.kt` | MODIFY — apply suppression before the calm budget |

**UI (`apps/ui`)**

| File | Responsibility |
|---|---|
| `.../client/features/responses/ResponseSheet.kt` | NEW — the one sheet, all surfaces |
| `.../client/features/responses/ResponseScopeStep.kt` | NEW — scope rows + who-it-applies-to segment |
| `.../client/features/responses/DoneNoteStep.kt` | NEW — "Just done" / add-note step |
| `.../client/features/responses/SmartContentScreen.kt` | NEW — Settings › Smart content |
| `.../client/NowFeedScreen.kt` | MODIFY — `⋮` + why-chip entry points, swipe snackbar action |
| `.../client/HubScreens.kt` | MODIFY — hub-block sheet with hub copy |
| `.../client/cards/…` detail | MODIFY — provenance-footer Mark done / Respond |
| `.../client/AccountScreen.kt` | MODIFY — Smart content row |
| `.../snapshot/SnapshotScenes.kt` | MODIFY — six scenes matching the six design views |

**CLI (`apps/cli`)**

| File | Responsibility |
|---|---|
| `src/main/kotlin/Responses.kt` | NEW — `dayfold responses list` + pre-flight filter |
| `src/main/kotlin/RoutineContract.kt` | MODIFY — changeset ops carry `subject_ref`; muted ops rejected in the diff |
| `src/main/kotlin/Help.kt`, `Main.kt` | MODIFY — command wiring |

---

## Task 0: The ADR (governance gate — blocks everything)

**Files:**
- Create: `adr/0064-smart-content-responses.md`
- Modify: `adr/decisions-index.md` (append one row)
- Modify: `backlog/operator-inbox.md` (one INB entry requesting acceptance)

**Interfaces:**
- Produces: the accepted persistence contract every later task cites. If the operator changes `match_scope`, the audience model, or the enforcement point, Tasks 4–7 change with it.

- [ ] **Step 1: Write the ADR**

Use `adr/0000-template.md`. The Decision section must state, at minimum:

1. **Two tiers, one new synced entity.** Tier 0 (snooze / hide / anti-nag / the once-ever swipe flag) stays device-local and never syncs — unchanged from ADR 0043 §2b and ADR 0039's hide-state leak argument. Tier 1 introduces `content_response`, one row type with `kind ∈ {mute, done}`.
2. **Why syncing a mute is consistent with refusing to sync a hide.** A hide is passive behavior (telemetry-shaped, a who-saw-what leak). A mute is an intentional, user-authored policy statement — first-class, visible in Settings, attributed, editable, deletable. Same reasoning that makes places and triggers syncable.
3. **The server stays content-blind.** Quote the match-semantics table above verbatim. Enforcement is string equality on `subject_ref` / `kind` / `provenance->>'source'`. `label`, `sublabel`, and `note` are opaque display strings carried for the client — plaintext at M0 exactly as block payloads are today, ciphertext under the same flip if ADR 0015/0017 activate. The server never branches on them.
4. **Audience model.** `personal` is the default; a personal mute strips the muting user from the written card's `audience[]` rather than rejecting the write (the routine still mints for everyone else). `family` rejects the write outright. Any adult sets, any adult removes; always attributed (decided Q2).
5. **Done is completion, not dismissal.** Family-wide, byline + timestamp captured, optional note. Creating a Done row tombstones the subject's card so it leaves every member's Now on next sync, and suppresses future writes to that `subject_ref`. No notification is emitted — the byline is the only signal (two-way remote-change rule).
6. **No expiry** (decided Q3). **Derived lane reads the same rule list, enforced on-device** (decided Q4).
7. **What is NOT decided here:** the fix-it/corrections channel (Tier 2) — deferred to its own ADR once ADR 0062's run receipt exists; kid/14+ member rights (personal responses assumed yes, family-wide adult-only) — flagged open; the deferred Q5 pause-suggestion.

Consequences must name the real costs: a third job for `subject_ref` makes it load-bearing (a subject key that is unstable across runs silently breaks Done); a synced rule list is a new resync/tombstone surface on the ADR 0040 cursor; personal-scope audience-stripping means one write can partially succeed.

- [ ] **Step 2: Add the index row**

```markdown
| 0064 | Smart-Content Responses — Synced Mute Rules and Family Done State on a Content-Blind subjectRef | Proposed 2026-08-08 (operator-gated) | Tier-1 `content_response` rows (mute + done) keyed by subjectRef; server suppresses by ID string equality only; derived lane enforces the same list on-device. |
```

- [ ] **Step 3: Add the inbox entry** requesting acceptance, listing the three decisions the operator must confirm: the audience-stripping behavior for personal mutes, Done tombstoning family-wide, and plaintext labels at M0.

- [ ] **Step 4: Commit**

```bash
git add adr/0064-smart-content-responses.md adr/decisions-index.md backlog/operator-inbox.md
git commit -m "Propose ADR 0064 — smart-content responses persistence contract"
```

- [ ] **Step 5: STOP. Wait for operator acceptance before Task 1.**

---

# Phase A — `subject_ref` becomes a real, persisted key

Today `subjectKey` exists only as a field on the in-memory `NowItem` (`NowDerive.kt:52`). Nothing persists it, so there is nothing for a rule to key against. Phase A fixes that. It ships no user-visible behavior and is safe to merge alone.

## Task 1: The subject-ref grammar, server side

**Files:**
- Create: `apps/api/src/content/subject-ref.ts`
- Test: `apps/api/test/subject-ref.test.ts`

**Interfaces:**
- Produces: `buildCardSubjectRef(cardId: string): string`, `buildBlockSubjectRef(hubId: string, sectionId: string | null, blockId: string): string`, `parseSubjectRef(ref: string): SubjectRef | null`, `isRuleRef(ref: string): boolean`, `type SubjectRef = { form: "card"|"node"|"kind"|"source"; cardId?: string; hubId?: string; sectionId?: string; blockId?: string; value?: string }`.

- [ ] **Step 1: Write the failing test**

```ts
// apps/api/test/subject-ref.test.ts
import { describe, it, expect } from "vitest";
import { buildCardSubjectRef, buildBlockSubjectRef, parseSubjectRef, isRuleRef } from "../src/content/subject-ref.ts";

describe("subject-ref grammar", () => {
  it("builds a card ref", () => {
    expect(buildCardSubjectRef("c_123")).toBe("card:c_123");
  });

  it("builds a node ref with and without a section", () => {
    expect(buildBlockSubjectRef("h_9", "s_2", "b_4")).toBe("hub:h_9/section:s_2/block:b_4");
    expect(buildBlockSubjectRef("h_9", null, "b_4")).toBe("hub:h_9/block:b_4");
  });

  it("round-trips a node ref", () => {
    expect(parseSubjectRef("hub:h_9/section:s_2/block:b_4")).toEqual({
      form: "node", hubId: "h_9", sectionId: "s_2", blockId: "b_4",
    });
  });

  it("classifies class refs as rule-only", () => {
    expect(isRuleRef("kind:weather")).toBe(true);
    expect(isRuleRef("source:morning-briefing")).toBe(true);
    expect(isRuleRef("card:c_123")).toBe(false);
  });

  // ids are free text and MAY contain ':' — the same hazard scope.ts:22 documents.
  it("does not split on ':' inside an id", () => {
    expect(parseSubjectRef("card:weird:id:here")).toEqual({ form: "card", cardId: "weird:id:here" });
  });

  it("rejects garbage", () => {
    expect(parseSubjectRef("")).toBeNull();
    expect(parseSubjectRef("nope:x")).toBeNull();
    expect(parseSubjectRef("hub:")).toBeNull();
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd apps/api && npx vitest run test/subject-ref.test.ts
```
Expected: FAIL — `Cannot find module '../src/content/subject-ref.ts'`.

- [ ] **Step 3: Implement**

```ts
// apps/api/src/content/subject-ref.ts
// ADR 0064 — the canonical subject-ref grammar. This file is the ONLY place the
// format is known; every producer and matcher goes through it. Ids are free text and
// may contain ':' (same hazard as ADR 0029 scope strings), so parsing is prefix-based
// and never `split(':')`.

export type SubjectRef =
  | { form: "card"; cardId: string }
  | { form: "node"; hubId: string; sectionId?: string; blockId: string }
  | { form: "kind"; value: string }
  | { form: "source"; value: string };

export function buildCardSubjectRef(cardId: string): string {
  return `card:${cardId}`;
}

export function buildBlockSubjectRef(hubId: string, sectionId: string | null, blockId: string): string {
  return sectionId
    ? `hub:${hubId}/section:${sectionId}/block:${blockId}`
    : `hub:${hubId}/block:${blockId}`;
}

export function buildKindRef(kind: string): string { return `kind:${kind}`; }
export function buildSourceRef(source: string): string { return `source:${source}`; }

export function isRuleRef(ref: string): boolean {
  return ref.startsWith("kind:") || ref.startsWith("source:");
}

export function parseSubjectRef(ref: string): SubjectRef | null {
  if (!ref) return null;
  if (ref.startsWith("card:")) {
    const id = ref.slice("card:".length);
    return id ? { form: "card", cardId: id } : null;
  }
  if (ref.startsWith("kind:")) {
    const v = ref.slice("kind:".length);
    return v ? { form: "kind", value: v } : null;
  }
  if (ref.startsWith("source:")) {
    const v = ref.slice("source:".length);
    return v ? { form: "source", value: v } : null;
  }
  if (ref.startsWith("hub:")) {
    const rest = ref.slice("hub:".length);
    // Split on the FIRST "/section:" or "/block:" marker only; ids may contain '/' and ':'.
    const blockAt = rest.lastIndexOf("/block:");
    if (blockAt <= 0) return null;
    const blockId = rest.slice(blockAt + "/block:".length);
    if (!blockId) return null;
    const head = rest.slice(0, blockAt);
    const sectionAt = head.indexOf("/section:");
    if (sectionAt === -1) {
      return head ? { form: "node", hubId: head, blockId } : null;
    }
    const hubId = head.slice(0, sectionAt);
    const sectionId = head.slice(sectionAt + "/section:".length);
    if (!hubId || !sectionId) return null;
    return { form: "node", hubId, sectionId, blockId };
  }
  return null;
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
cd apps/api && npx vitest run test/subject-ref.test.ts
```
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/content/subject-ref.ts apps/api/test/subject-ref.test.ts
git commit -m "Add the canonical subject-ref grammar (ADR 0064)"
```

## Task 2: Persist `subject_ref` on cards and blocks

**Files:**
- Create: `apps/api/migrations/0020_content_responses.sql`
- Modify: `apps/api/src/app.ts` (the `PUT /families/:fid/cards/:id` handler at ~line 465; the `PUT /families/:fid/blocks/:id` handler at ~line 795)
- Test: `apps/api/test/subject-ref-persist.test.ts`

**Interfaces:**
- Consumes: `buildCardSubjectRef` / `buildBlockSubjectRef` from Task 1.
- Produces: every live `briefing_cards` and `blocks` row has a non-null `subject_ref`. Task 6 matches against it.

This migration also creates the `content_responses` table (Task 4 fills in the routes) so the whole feature is one forward-only migration, per ADR 0033's one-txn rule.

- [ ] **Step 1: Write the failing test**

```ts
// apps/api/test/subject-ref-persist.test.ts
import { describe, it, expect, beforeAll } from "vitest";
import { applyMigrations } from "./_migrations.ts";
import { q } from "../src/db.ts";
import { app } from "../src/app.ts";
import { seedFamilyAndToken } from "./_migrations.ts"; // existing helper pattern; mirror api.test.ts

describe("subject_ref is stamped on write", () => {
  let fid: string, token: string;
  beforeAll(async () => { await applyMigrations(); ({ fid, token } = await seedFamilyAndToken()); });

  it("stamps a card subject_ref the author did not send", async () => {
    const res = await app.request(`/families/${fid}/cards/c_sr1`, {
      method: "PUT",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ kind: "info", title: "Rain at soccer", body_md: "70% at 5 PM" }),
    });
    expect(res.status).toBe(200);
    const row = await q(`SELECT subject_ref FROM briefing_cards WHERE family_id=$1 AND id=$2`, [fid, "c_sr1"]);
    expect(row.rows[0].subject_ref).toBe("card:c_sr1");
  });

  it("stamps a block subject_ref from its hub/section/block path", async () => {
    // seed a hub + section first, mirroring hub-api.test.ts's setup
    const res = await app.request(`/families/${fid}/blocks/b_sr1`, {
      method: "PUT",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ section_id: "s_sr1", type: "text", body_md: "hello" }),
    });
    expect(res.status).toBe(200);
    const row = await q(`SELECT subject_ref FROM blocks WHERE family_id=$1 AND id=$2`, [fid, "b_sr1"]);
    expect(row.rows[0].subject_ref).toBe("hub:h_sr1/section:s_sr1/block:b_sr1");
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd apps/api && export DATABASE_URL=postgres:///fad_test && npx vitest run test/subject-ref-persist.test.ts
```
Expected: FAIL — `column "subject_ref" does not exist`.

- [ ] **Step 3: Write the migration**

```sql
-- apps/api/migrations/0020_content_responses.sql
-- ADR 0064 — smart-content responses. Two things, one txn:
--   1) subject_ref as a persisted, indexed key on authored content (the third job of the
--      ADR 0043 subjectRef: dedup key -> deep-link key -> SUPPRESSION key).
--   2) content_responses — the Tier-1 synced rows (mute + done). The server matches these
--      by ID string equality ONLY; label/sublabel/note are opaque display strings it never
--      reasons over (same posture as block payloads today).
-- Forward-only plain SQL, one txn (ADR 0033). No IF NOT EXISTS.

BEGIN;

ALTER TABLE briefing_cards ADD COLUMN subject_ref text;
ALTER TABLE blocks         ADD COLUMN subject_ref text;

-- Backfill: every existing live row gets its canonical ref. Blocks join through sections
-- to recover the hub id. Tombstoned rows are backfilled too so a resurrect-by-PUT keeps
-- the same key.
UPDATE briefing_cards SET subject_ref = 'card:' || id WHERE subject_ref IS NULL;
UPDATE blocks b
   SET subject_ref = 'hub:' || s.hub_id || '/section:' || b.section_id || '/block:' || b.id
  FROM sections s
 WHERE s.family_id = b.family_id AND s.id = b.section_id AND b.subject_ref IS NULL;

CREATE INDEX briefing_cards_subject_ref_idx ON briefing_cards (family_id, subject_ref);
CREATE INDEX blocks_subject_ref_idx         ON blocks (family_id, subject_ref);

-- The Tier-1 rows. `kind` distinguishes a suppression rule from a completion record;
-- they share a table because they share a key, a lifecycle, a sync lane, and a Settings
-- surface, and because a done row IS a suppression for future runs.
CREATE TABLE content_responses (
  id             text NOT NULL,
  family_id      text NOT NULL REFERENCES families(id) ON DELETE CASCADE,
  kind           text NOT NULL CHECK (kind IN ('mute','done')),
  subject_ref    text NOT NULL,
  match_scope    text NOT NULL CHECK (match_scope IN ('subject','kind','source')),
  audience_scope text NOT NULL CHECK (audience_scope IN ('personal','family')),
  -- The member a personal rule belongs to. NULL iff audience_scope='family'.
  user_id        text REFERENCES users(id),
  -- Always attributed (decided Q2) — who set it, for the Settings byline.
  created_by     text NOT NULL REFERENCES users(id),
  -- Opaque display strings for the client. The server NEVER branches on these.
  label          text NOT NULL,
  sublabel       text,
  note           text,
  version        bigint NOT NULL DEFAULT 1,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at     timestamptz,
  PRIMARY KEY (family_id, id),
  -- A personal rule needs an owner; a family rule must not have one.
  CONSTRAINT content_responses_audience_owner CHECK (
    (audience_scope = 'personal' AND user_id IS NOT NULL) OR
    (audience_scope = 'family'   AND user_id IS NULL)
  ),
  -- Done is always family-wide and always on a concrete subject (never a class).
  CONSTRAINT content_responses_done_shape CHECK (
    kind <> 'done' OR (audience_scope = 'family' AND match_scope = 'subject')
  )
);

-- The hot path: "is this subject_ref suppressed for this family?" on every content write.
CREATE INDEX content_responses_lookup_idx ON content_responses (family_id, subject_ref) WHERE deleted_at IS NULL;
-- The /sync merged keyset scan (ADR 0040) orders by updated_at, id.
CREATE INDEX content_responses_sync_idx ON content_responses (family_id, updated_at, id);

COMMIT;
```

- [ ] **Step 4: Stamp `subject_ref` on the card write path**

In `apps/api/src/app.ts`, inside the `PUT /families/:fid/cards/:id` handler, immediately before the upsert:

```ts
import { buildCardSubjectRef, buildBlockSubjectRef } from "./content/subject-ref.ts";

// ADR 0064 — the server owns the key; an author-supplied subject_ref is ignored so the
// suppression key can never be spoofed to dodge a mute.
const subjectRef = buildCardSubjectRef(id);
```

Add `subject_ref` to the INSERT column list and to the `ON CONFLICT … DO UPDATE SET` list with `EXCLUDED.subject_ref`.

- [ ] **Step 5: Stamp `subject_ref` on the block write path**

In the `PUT /families/:fid/blocks/:id` handler, after the section's hub is resolved (the handler already loads it for the visibility check):

```ts
const subjectRef = buildBlockSubjectRef(hubId, sectionId, id);
```

Same INSERT/ON CONFLICT treatment.

- [ ] **Step 6: Run the tests and watch them pass**

```bash
cd apps/api && psql -d fad_test -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" \
  && for f in migrations/*.sql; do psql -d fad_test -f "$f" >/dev/null; done \
  && npx vitest run test/subject-ref-persist.test.ts test/migrations.test.ts
```
Expected: PASS. `migrations.test.ts` must stay green — it asserts the migration ledger is complete and re-runnable.

- [ ] **Step 7: Run the whole API suite** — the new NOT-NULL-free columns must not disturb any existing insert.

```bash
cd apps/api && npx vitest run
```
Expected: PASS, no regressions.

- [ ] **Step 8: Commit**

```bash
git add apps/api/migrations/0020_content_responses.sql apps/api/src/app.ts apps/api/test/subject-ref-persist.test.ts
git commit -m "Persist subject_ref on cards and blocks; add the content_responses table"
```

## Task 3: `subject_ref` reaches the client and the NowItem uses it

**Files:**
- Modify: `apps/client/src/commonMain/sqldelight/com/sloopworks/dayfold/client/db/Content.sq`
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SubjectRef.kt`
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowDerive.kt`
- Test: `apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/SubjectRefTest.kt`

**Interfaces:**
- Produces: `SubjectRef.card(cardId)`, `SubjectRef.node(hubId, sectionId, blockId)`, `SubjectRef.kind(k)`, `SubjectRef.source(s)` — all `String`-returning, byte-identical to the TypeScript builders. `NowItem.subjectKey` now holds a value in this grammar.

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/SubjectRefTest.kt
package com.sloopworks.dayfold.client

import kotlin.test.Test
import kotlin.test.assertEquals

class SubjectRefTest {
  @Test fun cardRefMatchesTheServerGrammar() {
    assertEquals("card:c_123", SubjectRef.card("c_123"))
  }

  @Test fun nodeRefIncludesTheSectionWhenPresent() {
    assertEquals("hub:h_9/section:s_2/block:b_4", SubjectRef.node("h_9", "s_2", "b_4"))
    assertEquals("hub:h_9/block:b_4", SubjectRef.node("h_9", null, "b_4"))
  }

  @Test fun classRefs() {
    assertEquals("kind:weather", SubjectRef.kind("weather"))
    assertEquals("source:morning-briefing", SubjectRef.source("morning-briefing"))
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest --tests "*SubjectRefTest*"
```
Expected: FAIL — unresolved reference `SubjectRef`.

- [ ] **Step 3: Implement the Kotlin mirror**

```kotlin
// apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SubjectRef.kt
package com.sloopworks.dayfold.client

// ADR 0064 — the Kotlin mirror of apps/api/src/content/subject-ref.ts. These two files MUST
// produce byte-identical strings: a client-minted rule key is matched server-side by string
// equality, so any divergence silently stops suppressing. Pure, no platform types.
object SubjectRef {
  fun card(cardId: String): String = "card:$cardId"

  fun node(hubId: String, sectionId: String?, blockId: String): String =
    if (sectionId != null) "hub:$hubId/section:$sectionId/block:$blockId"
    else "hub:$hubId/block:$blockId"

  fun kind(kind: String): String = "kind:$kind"
  fun source(source: String): String = "source:$source"
}
```

- [ ] **Step 4: Add the columns to the client DB**

In `Content.sq`, add `subject_ref TEXT` to the `card` and `hub_block` table definitions, add it to `upsertCard` / the block upsert statements, and add it to the projection selects the store reads. Follow the migration note at `Content.sq:25` — the device DB has no `.sqm` migration infra for the `card` table, so bump `CLIENT_SCHEMA_VERSION` (the same heal mechanism the debug-seed fix used) rather than assuming an in-place `ALTER`.

- [ ] **Step 5: Make `deriveNow` emit grammar-conformant subject keys**

`NowDerive.kt` currently builds `subjectKey` ad hoc. Replace every construction site with `SubjectRef.node(...)` for derived items and `SubjectRef.card(...)` for the authored lane, so both lanes key into the same namespace the server uses. The existing dedup tests must stay green — the values change shape, but prefix-merge still works because the hierarchy is preserved.

- [ ] **Step 6: Run the client suite**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest
```
Expected: PASS (440+ tests, plus the 3 new ones). If a dedup test fails on a hard-coded old-shape key, update the expectation — the shape is what changed, not the behavior.

- [ ] **Step 7: Commit**

```bash
git add apps/client/src/commonMain apps/client/src/commonTest
git commit -m "Mirror the subject-ref grammar client-side and key NowItem off it"
```

---

# Phase B — the server stores and enforces rules

## Task 4: Response repo + the match predicate

**Files:**
- Create: `apps/api/src/content/responses.ts`
- Test: `apps/api/test/responses-match.test.ts`

**Interfaces:**
- Consumes: `parseSubjectRef`, `isRuleRef` (Task 1).
- Produces:
  - `type ContentResponseRow = { id, kind: "mute"|"done", subject_ref, match_scope: "subject"|"kind"|"source", audience_scope: "personal"|"family", user_id: string|null, created_by, label, sublabel: string|null, note: string|null, version: number }`
  - `matchesRule(row: { subjectRef: string; kind: string | null; source: string | null }, rule: ContentResponseRow): boolean`
  - `listActive(familyId: string): Promise<ContentResponseRow[]>`
  - `upsertResponse(familyId, id, input): Promise<ContentResponseRow>`
  - `softDeleteResponse(familyId, id): Promise<boolean>`

- [ ] **Step 1: Write the failing test**

```ts
// apps/api/test/responses-match.test.ts
import { describe, it, expect } from "vitest";
import { matchesRule } from "../src/content/responses.ts";

const base = {
  id: "r1", kind: "mute" as const, audience_scope: "family" as const, user_id: null,
  created_by: "u1", label: "x", sublabel: null, note: null, version: 1,
};

describe("matchesRule — string equality only, never content", () => {
  it("subject scope matches an exact subject_ref", () => {
    const rule = { ...base, subject_ref: "card:c_1", match_scope: "subject" as const };
    expect(matchesRule({ subjectRef: "card:c_1", kind: "weather", source: "mb" }, rule)).toBe(true);
    expect(matchesRule({ subjectRef: "card:c_2", kind: "weather", source: "mb" }, rule)).toBe(false);
  });

  it("kind scope matches the card kind column", () => {
    const rule = { ...base, subject_ref: "kind:weather", match_scope: "kind" as const };
    expect(matchesRule({ subjectRef: "card:c_1", kind: "weather", source: "mb" }, rule)).toBe(true);
    expect(matchesRule({ subjectRef: "card:c_1", kind: "action", source: "mb" }, rule)).toBe(false);
  });

  it("source scope matches provenance.source", () => {
    const rule = { ...base, subject_ref: "source:morning-briefing", match_scope: "source" as const };
    expect(matchesRule({ subjectRef: "card:c_1", kind: "info", source: "morning-briefing" }, rule)).toBe(true);
    expect(matchesRule({ subjectRef: "card:c_1", kind: "info", source: "other" }, rule)).toBe(false);
  });

  it("a null kind or source never matches a class rule", () => {
    const k = { ...base, subject_ref: "kind:weather", match_scope: "kind" as const };
    const s = { ...base, subject_ref: "source:mb", match_scope: "source" as const };
    expect(matchesRule({ subjectRef: "card:c_1", kind: null, source: null }, k)).toBe(false);
    expect(matchesRule({ subjectRef: "card:c_1", kind: null, source: null }, s)).toBe(false);
  });

  it("a done row suppresses its subject like a mute does", () => {
    const rule = { ...base, kind: "done" as const, subject_ref: "card:c_1", match_scope: "subject" as const };
    expect(matchesRule({ subjectRef: "card:c_1", kind: "action", source: "mb" }, rule)).toBe(true);
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd apps/api && npx vitest run test/responses-match.test.ts
```
Expected: FAIL — module not found.

- [ ] **Step 3: Implement**

```ts
// apps/api/src/content/responses.ts
// ADR 0064 — Tier-1 response rows. The match predicate is the ONE place suppression is
// decided, and it is deliberately trivial: three string equalities against columns the
// server already treats as opaque identifiers. It reads no title, body, label, or note.
// If this function ever needs to look at content, the design is wrong.
import { q } from "../db.ts";

export type ResponseKind = "mute" | "done";
export type MatchScope = "subject" | "kind" | "source";
export type AudienceScope = "personal" | "family";

export type ContentResponseRow = {
  id: string;
  kind: ResponseKind;
  subject_ref: string;
  match_scope: MatchScope;
  audience_scope: AudienceScope;
  user_id: string | null;
  created_by: string;
  label: string;
  sublabel: string | null;
  note: string | null;
  version: number;
};

export type WriteSubject = { subjectRef: string; kind: string | null; source: string | null };

export function matchesRule(row: WriteSubject, rule: ContentResponseRow): boolean {
  switch (rule.match_scope) {
    case "subject": return row.subjectRef === rule.subject_ref;
    case "kind":    return row.kind != null && `kind:${row.kind}` === rule.subject_ref;
    case "source":  return row.source != null && `source:${row.source}` === rule.subject_ref;
  }
}

const COLS = `id, kind, subject_ref, match_scope, audience_scope, user_id, created_by,
              label, sublabel, note, version`;

export async function listActive(familyId: string): Promise<ContentResponseRow[]> {
  const r = await q(
    `SELECT ${COLS} FROM content_responses WHERE family_id=$1 AND deleted_at IS NULL`,
    [familyId],
  );
  return r.rows as ContentResponseRow[];
}

export type ResponseInput = {
  kind: ResponseKind;
  subjectRef: string;
  matchScope: MatchScope;
  audienceScope: AudienceScope;
  userId: string | null;
  createdBy: string;
  label: string;
  sublabel: string | null;
  note: string | null;
};

export async function upsertResponse(
  familyId: string, id: string, input: ResponseInput,
): Promise<ContentResponseRow> {
  const r = await q(
    `INSERT INTO content_responses
       (id, family_id, kind, subject_ref, match_scope, audience_scope, user_id, created_by,
        label, sublabel, note)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
     ON CONFLICT (family_id, id) DO UPDATE SET
       label = EXCLUDED.label, sublabel = EXCLUDED.sublabel, note = EXCLUDED.note,
       deleted_at = NULL,
       version = content_responses.version + 1,
       updated_at = now()
     RETURNING ${COLS}`,
    [id, familyId, input.kind, input.subjectRef, input.matchScope, input.audienceScope,
     input.userId, input.createdBy, input.label, input.sublabel, input.note],
  );
  return r.rows[0] as ContentResponseRow;
}

// Soft delete so /sync can emit the tombstone (ADR 0040). Returns false if absent/already gone.
export async function softDeleteResponse(familyId: string, id: string): Promise<boolean> {
  const r = await q(
    `UPDATE content_responses SET deleted_at = now(), version = version + 1, updated_at = now()
      WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`,
    [familyId, id],
  );
  return (r.rowCount ?? 0) > 0;
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
cd apps/api && npx vitest run test/responses-match.test.ts
```
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/content/responses.ts apps/api/test/responses-match.test.ts
git commit -m "Add the content-response repo and the ID-only match predicate"
```

## Task 5: The response endpoints

**Files:**
- Modify: `apps/api/src/app.ts` (add two routes near the block routes, ~line 861)
- Modify: `apps/api/src/auth/scope.ts` (no code change — confirm `content:write` covers it; document the decision in a comment)
- Test: `apps/api/test/responses-api.test.ts`

**Interfaces:**
- Consumes: `upsertResponse`, `softDeleteResponse`, `listActive` (Task 4); `findOp`/`recordOp` from `content/oplog.ts`.
- Produces: `PUT /families/:fid/responses/:id` → `200 {id, version, …}`; `DELETE /families/:fid/responses/:id` → `204`.

- [ ] **Step 1: Write the failing test**

```ts
// apps/api/test/responses-api.test.ts
import { describe, it, expect, beforeAll } from "vitest";
import { app } from "../src/app.ts";
import { applyMigrations, seedFamilyAndToken } from "./_migrations.ts";

describe("response endpoints", () => {
  let fid: string, token: string, uid: string;
  beforeAll(async () => { await applyMigrations(); ({ fid, token, uid } = await seedFamilyAndToken()); });

  const put = (id: string, body: unknown, opId?: string) =>
    app.request(`/families/${fid}/responses/${id}`, {
      method: "PUT",
      headers: {
        authorization: `Bearer ${token}`, "content-type": "application/json",
        ...(opId ? { "idempotency-key": opId } : {}),
      },
      body: JSON.stringify(body),
    });

  it("creates a personal mute owned by the caller", async () => {
    const res = await put("r_1", {
      kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "personal", label: "Weather cards", sublabel: "From Morning briefing",
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.audience_scope).toBe("personal");
    expect(body.user_id).toBe(uid);      // server assigns the owner; the client never sends it
    expect(body.created_by).toBe(uid);
  });

  it("rejects a client-supplied user_id (no acting-as)", async () => {
    const res = await put("r_2", {
      kind: "mute", subject_ref: "kind:traffic", match_scope: "kind",
      audience_scope: "personal", user_id: "someone_else", label: "Traffic cards",
    });
    const body = await res.json();
    expect(body.user_id).toBe(uid);
  });

  it("is idempotent under a replayed op id", async () => {
    const a = await put("r_3", { kind: "mute", subject_ref: "card:c_9", match_scope: "subject",
                                 audience_scope: "family", label: "This card" }, "op_abc");
    const b = await put("r_3", { kind: "mute", subject_ref: "card:c_9", match_scope: "subject",
                                 audience_scope: "family", label: "This card" }, "op_abc");
    expect((await a.json()).version).toBe((await b.json()).version);
  });

  it("rejects a done row that is not family/subject shaped", async () => {
    const res = await put("r_4", { kind: "done", subject_ref: "kind:weather", match_scope: "kind",
                                   audience_scope: "personal", label: "nope" });
    expect(res.status).toBe(422);
  });

  it("soft-deletes, and a re-delete is idempotent", async () => {
    const one = await app.request(`/families/${fid}/responses/r_1`, {
      method: "DELETE", headers: { authorization: `Bearer ${token}` },
    });
    expect(one.status).toBe(204);
    const two = await app.request(`/families/${fid}/responses/r_1`, {
      method: "DELETE", headers: { authorization: `Bearer ${token}` },
    });
    expect(two.status).toBe(204);
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd apps/api && npx vitest run test/responses-api.test.ts
```
Expected: FAIL — 404 on every route.

- [ ] **Step 3: Implement the routes**

```ts
// apps/api/src/app.ts — near the block routes
import * as responses from "./content/responses.ts";
import { isRuleRef } from "./content/subject-ref.ts";

// ADR 0064 — write a mute rule or a done record. Scope: `content:write` (a response is a
// content-adjacent write by the acting member; it needs no new grant vocabulary — the CLI
// authoring credential already holds it, and the app credential holds it for member writes).
app.put("/families/:fid/responses/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, "content", "write"))) return c.json({ type: "forbidden" }, 403);
  const caller = callerFrom(a);
  // A response is authored BY a member. A null-user credential authors nothing — the same
  // "NULL → god-mode" hole guarded in write-guard.ts:29.
  if (!caller.userId) return problem(c, 403, "member-required");

  const opId = c.req.header("idempotency-key");
  if (opId) {
    const prior = await findOp(fid, opId);
    if (prior) return c.json({ id, version: prior.result_version }, 200);
  }

  const b = await c.req.json().catch(() => null);
  if (!b || typeof b.subject_ref !== "string" || typeof b.label !== "string") {
    return problem(c, 422, "bad-response");
  }
  const kind = b.kind === "done" ? "done" : "mute";
  const matchScope = ["subject", "kind", "source"].includes(b.match_scope) ? b.match_scope : null;
  if (!matchScope) return problem(c, 422, "bad-match-scope");
  // A class ref may only carry a class match scope, and vice versa.
  if (isRuleRef(b.subject_ref) !== (matchScope !== "subject")) return problem(c, 422, "ref-scope-mismatch");
  const audienceScope: "personal" | "family" = b.audience_scope === "family" ? "family" : "personal";
  // Done is always family-wide and always on a concrete subject (mirrors the CHECK constraint,
  // so the client gets a 422 instead of a 500 from the DB).
  if (kind === "done" && (audienceScope !== "family" || matchScope !== "subject")) {
    return problem(c, 422, "bad-done-shape");
  }

  const row = await responses.upsertResponse(fid, id, {
    kind, subjectRef: b.subject_ref, matchScope, audienceScope,
    // The server assigns ownership from the token — a client-sent user_id is ignored.
    userId: audienceScope === "personal" ? caller.userId : null,
    createdBy: caller.userId,
    label: b.label,
    sublabel: typeof b.sublabel === "string" ? b.sublabel : null,
    note: typeof b.note === "string" ? b.note : null,
  });

  // A done row resolves the subject for everyone: tombstone the card so it leaves every
  // member's Now on the next sync (NOTES.md § Done — "Removes for everyone: yes").
  if (kind === "done") await tombstoneSubject(fid, b.subject_ref);

  if (opId) await recordOp(fid, opId, "response", id, Number(row.version));
  return c.json(row, 200);
});

// Remove a rule. Any adult may remove a family-wide rule (decided Q2); a personal rule is
// removable by its owner. Soft delete so /sync emits the tombstone (ADR 0040).
app.delete("/families/:fid/responses/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, "content", "write"))) return c.json({ type: "forbidden" }, 403);
  const caller = callerFrom(a);
  if (!caller.userId) return problem(c, 403, "member-required");

  const opId = c.req.header("idempotency-key");
  if (opId && (await findOp(fid, opId))) return c.body(null, 204);

  const { rows } = await q(
    `SELECT audience_scope, user_id FROM content_responses
      WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [fid, id]);
  const row = rows[0];
  if (row && row.audience_scope === "personal" && row.user_id !== caller.userId) {
    return problem(c, 403, "not-your-rule");
  }
  await responses.softDeleteResponse(fid, id);           // absent/already-gone → still 204
  if (opId) await recordOp(fid, opId, "response", id, null);
  return c.body(null, 204);
});
```

Add the `tombstoneSubject` helper next to the routes:

```ts
// Resolve a subject_ref to its content row and soft-delete it. By ID only — the server does
// not look at what the card says, just which row carries that key.
async function tombstoneSubject(fid: string, subjectRef: string): Promise<void> {
  await q(`UPDATE briefing_cards SET deleted_at = now(), version = version + 1, updated_at = now()
            WHERE family_id=$1 AND subject_ref=$2 AND deleted_at IS NULL`, [fid, subjectRef]);
  await q(`UPDATE blocks SET deleted_at = now(), version = version + 1, updated_at = now()
            WHERE family_id=$1 AND subject_ref=$2 AND deleted_at IS NULL`, [fid, subjectRef]);
}
```

- [ ] **Step 4: Run the tests and watch them pass**

```bash
cd apps/api && npx vitest run test/responses-api.test.ts
```
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/app.ts apps/api/test/responses-api.test.ts
git commit -m "Add PUT/DELETE response endpoints with op-id idempotency"
```

## Task 6: Enforce suppression on the content write paths

**Files:**
- Modify: `apps/api/src/content/write-guard.ts`
- Modify: `apps/api/src/app.ts` (card PUT ~465, block PUT ~795)
- Test: `apps/api/test/responses-suppress.test.ts`

**Interfaces:**
- Consumes: `listActive`, `matchesRule` (Task 4).
- Produces: `suppressedBy(rules, subject): { blocked: boolean; excludeUserIds: string[] }` — `blocked` when a family rule matches, `excludeUserIds` when personal rules match.

The **personal** case is the subtle one: the routine should still mint for everyone else, so a personal mute does not reject the write — it strips those members from the card's `audience[]` (ADR 0030's flat author-stamped audience). If stripping empties the audience, there is no one left to write for, and the write is rejected.

- [ ] **Step 1: Write the failing test**

```ts
// apps/api/test/responses-suppress.test.ts
import { describe, it, expect } from "vitest";
import { suppressedBy } from "../src/content/write-guard.ts";

const rule = (over: Partial<any>) => ({
  id: "r", kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
  audience_scope: "family", user_id: null, created_by: "u1",
  label: "l", sublabel: null, note: null, version: 1, ...over,
});

describe("suppressedBy", () => {
  const subject = { subjectRef: "card:c_1", kind: "weather", source: "mb" };

  it("blocks on a matching family mute", () => {
    expect(suppressedBy([rule({})], subject)).toEqual({ blocked: true, excludeUserIds: [] });
  });

  it("excludes the owner on a matching personal mute, and does not block", () => {
    const r = rule({ audience_scope: "personal", user_id: "u_mom" });
    expect(suppressedBy([r], subject)).toEqual({ blocked: false, excludeUserIds: ["u_mom"] });
  });

  it("collects every matching personal owner", () => {
    const rs = [rule({ audience_scope: "personal", user_id: "u_mom" }),
                rule({ audience_scope: "personal", user_id: "u_dad", subject_ref: "source:mb", match_scope: "source" })];
    expect(suppressedBy(rs, subject).excludeUserIds.sort()).toEqual(["u_dad", "u_mom"]);
  });

  it("blocks on a done row regardless of audience shape", () => {
    const r = rule({ kind: "done", subject_ref: "card:c_1", match_scope: "subject" });
    expect(suppressedBy([r], subject).blocked).toBe(true);
  });

  it("ignores non-matching rules", () => {
    expect(suppressedBy([rule({ subject_ref: "kind:traffic" })], subject))
      .toEqual({ blocked: false, excludeUserIds: [] });
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd apps/api && npx vitest run test/responses-suppress.test.ts
```
Expected: FAIL — `suppressedBy` is not exported.

- [ ] **Step 3: Implement the gate**

```ts
// apps/api/src/content/write-guard.ts — append
import { matchesRule, type ContentResponseRow, type WriteSubject } from "./responses.ts";

// ADR 0064 — the suppression gate. Family-scoped rules and every done row BLOCK the write;
// personal rules do not block, they remove their owner from the card's audience so the
// routine still mints for the rest of the family ("your family's feed is unchanged").
export function suppressedBy(
  rules: ContentResponseRow[], subject: WriteSubject,
): { blocked: boolean; excludeUserIds: string[] } {
  let blocked = false;
  const exclude = new Set<string>();
  for (const r of rules) {
    if (!matchesRule(subject, r)) continue;
    if (r.kind === "done" || r.audience_scope === "family") blocked = true;
    else if (r.user_id) exclude.add(r.user_id);
  }
  return { blocked, excludeUserIds: [...exclude] };
}
```

- [ ] **Step 4: Wire it into the card write path**

In `PUT /families/:fid/cards/:id`, after `subjectRef` is computed (Task 2) and before the upsert:

```ts
// ADR 0064 — mechanical, by ID. A blocked write is 409 so the author (routine/CLI) can log
// "skipped N muted subjects" on its run receipt rather than treating it as an error.
const activeRules = await responses.listActive(fid);
const gate = suppressedBy(activeRules, {
  subjectRef, kind: body.kind ?? null, source: body.provenance?.source ?? null,
});
if (gate.blocked) return problem(c, 409, "subject-muted");
let audience: string[] | null = body.audience ?? null;
if (gate.excludeUserIds.length > 0 && audience) {
  audience = audience.filter((u: string) => !gate.excludeUserIds.includes(u));
  if (audience.length === 0) return problem(c, 409, "subject-muted");   // nobody left to write for
}
```

Use `audience` (not `body.audience`) in the upsert.

- [ ] **Step 5: Wire it into the block write path**

Same shape in `PUT /families/:fid/blocks/:id`. Blocks have no `kind`/`provenance.source` columns of the card's shape, so pass `kind: null, source: null` — only `match_scope: "subject"` rules can match a block, which is exactly the "Don't add to this hub again" case.

- [ ] **Step 6: Add the end-to-end suppression test**

```ts
// append to apps/api/test/responses-suppress.test.ts
describe("suppression end-to-end", () => {
  it("a family mute makes the next authored write 409", async () => {
    await app.request(`/families/${fid}/responses/r_mute`, {
      method: "PUT", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
                             audience_scope: "family", label: "Weather cards" }),
    });
    const res = await app.request(`/families/${fid}/cards/c_rain`, {
      method: "PUT", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ kind: "weather", title: "Rain at soccer" }),
    });
    expect(res.status).toBe(409);
  });

  it("a personal mute strips only that member from audience[]", async () => { /* mirror the above,
     assert 200 and that briefing_cards.audience no longer contains the muting user */ });
});
```

- [ ] **Step 7: Run the full API suite**

```bash
cd apps/api && npx vitest run
```
Expected: PASS. Watch `write-guard-unit.test.ts` and `card-visibility.test.ts` especially — both exercise the paths just modified.

- [ ] **Step 8: Commit**

```bash
git add apps/api/src/content/write-guard.ts apps/api/src/app.ts apps/api/test/responses-suppress.test.ts
git commit -m "Enforce mute and done suppression on the content write paths"
```

## Task 7: Responses ride the `/sync` cursor

**Files:**
- Modify: `apps/api/src/repo.ts` (`syncContent`)
- Modify: `apps/api/src/app.ts` (`/sync` handler ~1148 — the cursor type vocabulary at ~1178 and the row projection)
- Test: `apps/api/test/responses-sync.test.ts`

**Interfaces:**
- Produces: `/sync` emits rows of `type: "response"` with the same `{type, id, deleted, version, payload}` envelope the other types use, and the 3-part cursor accepts `response` as a valid type token.

**Visibility rule:** a personal rule belongs to one member. Emit a family rule to everyone; emit a personal rule **only to its owner**, as a tombstone to everyone else — exactly the pattern the handler already uses for restricted hubs (a row not visible to the caller is emitted as a tombstone so the cache drops it, never omitted, so the cursor never stalls).

- [ ] **Step 1: Write the failing test**

```ts
// apps/api/test/responses-sync.test.ts
import { describe, it, expect, beforeAll } from "vitest";
import { app } from "../src/app.ts";
import { applyMigrations, seedFamilyAndToken, seedSecondMember } from "./_migrations.ts";

describe("/sync emits response rows", () => {
  let fid: string, token: string, uid: string, otherToken: string;
  beforeAll(async () => {
    await applyMigrations();
    ({ fid, token, uid } = await seedFamilyAndToken());
    ({ token: otherToken } = await seedSecondMember(fid));
  });

  const sync = (t: string) =>
    app.request(`/families/${fid}/sync`, { headers: { authorization: `Bearer ${t}` } }).then((r) => r.json());

  it("emits a family rule to every member", async () => {
    await app.request(`/families/${fid}/responses/r_fam`, {
      method: "PUT", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
                             audience_scope: "family", label: "Weather in Now" }),
    });
    const mine = await sync(token), theirs = await sync(otherToken);
    expect(mine.rows.find((r: any) => r.id === "r_fam" && r.type === "response")?.deleted).toBeFalsy();
    expect(theirs.rows.find((r: any) => r.id === "r_fam" && r.type === "response")?.deleted).toBeFalsy();
  });

  it("emits a personal rule to its owner and a tombstone to everyone else", async () => {
    await app.request(`/families/${fid}/responses/r_me`, {
      method: "PUT", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ kind: "mute", subject_ref: "kind:traffic", match_scope: "kind",
                             audience_scope: "personal", label: "Traffic cards" }),
    });
    const mine = await sync(token), theirs = await sync(otherToken);
    expect(mine.rows.find((r: any) => r.id === "r_me")?.deleted).toBeFalsy();
    expect(theirs.rows.find((r: any) => r.id === "r_me")?.deleted).toBe(true);
  });

  it("accepts a 3-part cursor whose type token is `response`", async () => {
    const cursor = Buffer.from(`${new Date().toISOString()}|response|r_me`).toString("base64");
    const res = await app.request(`/families/${fid}/sync?since=${encodeURIComponent(cursor)}`,
      { headers: { authorization: `Bearer ${token}` } });
    expect(res.status).toBe(200);       // not 400 bad-cursor
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd apps/api && npx vitest run test/responses-sync.test.ts
```
Expected: FAIL — no `response` rows; the cursor test 400s.

- [ ] **Step 3: Union the table into `syncContent`**

In `repo.ts`, the merged keyset query already unions `briefing_cards`, `hubs`, `sections`, `blocks`. Add a fifth arm:

```sql
UNION ALL
SELECT 'response' AS type, id, updated_at, deleted_at, version,
       jsonb_build_object(
         'kind', kind, 'subject_ref', subject_ref, 'match_scope', match_scope,
         'audience_scope', audience_scope, 'user_id', user_id, 'created_by', created_by,
         'label', label, 'sublabel', sublabel, 'note', note
       ) AS payload,
       NULL AS hub_id, NULL AS hub_visibility
  FROM content_responses
 WHERE family_id = $1
```

Keep the existing `(updated_at, type, id) > (su, st, si)` keyset predicate and `ORDER BY updated_at, type, id` untouched — the new arm inherits both.

- [ ] **Step 4: Add `response` to the cursor type vocabulary**

`app.ts:1178` — change `["card", "hub", "section", "block"]` to `["card", "hub", "section", "block", "response"]`.

- [ ] **Step 5: Apply the personal-rule visibility rule**

In the row projection, alongside the existing restricted-hub tombstoning:

```ts
// ADR 0064 — a personal rule is visible only to its owner. Everyone else gets a tombstone
// (never an omission) so the cursor advances and a rule that flipped owner leaves the cache.
if (r.type === "response" && r.payload?.audience_scope === "personal"
    && r.payload?.user_id !== caller.userId) {
  return { type: "response", id: r.id, deleted: true, version: r.version };
}
```

- [ ] **Step 6: Run the tests and watch them pass**

```bash
cd apps/api && npx vitest run test/responses-sync.test.ts test/hub-sync.test.ts
```
Expected: PASS. `hub-sync.test.ts` must stay green — it pins the merged-cursor contract.

- [ ] **Step 7: Add the tombstone-sweep arm**

`content_responses` is a new tombstone surface on the ADR 0040 cursor. `src/auth/sweep.ts` already purges content tombstones past `CONTENT_TOMBSTONE_RETENTION_DAYS`; without a matching arm, response tombstones accumulate forever while the stale-cursor full-resync directive assumes they are GC'd on the same floor.

```ts
// apps/api/src/auth/sweep.ts — add beside the existing content-tombstone arm
await q(
  `DELETE FROM content_responses
    WHERE deleted_at IS NOT NULL
      AND deleted_at < now() - ($1 || ' days')::interval`,
  [CONTENT_TOMBSTONE_RETENTION_DAYS],
);
```

Extend `test/sweep.test.ts` with a case asserting a response tombstone older than the floor is purged and a fresher one survives.

- [ ] **Step 8: Run the full suite and commit**

```bash
cd apps/api && npx vitest run
git add apps/api/src/repo.ts apps/api/src/app.ts apps/api/src/auth/sweep.ts \
        apps/api/test/responses-sync.test.ts apps/api/test/sweep.test.ts
git commit -m "Emit content responses on the /sync merged cursor and sweep their tombstones"
```

**Phase B ships here.** The server stores rules, enforces them by ID, and syncs them. Merge before starting Phase C.

---

# Phase C — the client stores, applies, and writes rules

## Task 8: Client model, reducer, and the pure rule engine

**Files:**
- Create: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/responses/ResponseModel.kt`
- Create: `.../features/responses/ResponseActions.kt`
- Create: `.../features/responses/ResponseReducer.kt`
- Create: `.../features/responses/ResponseRules.kt`
- Modify: `.../client/Model.kt` (add `responses` to `AppState`)
- Modify: `.../client/Reducer.kt` (delegate the slice)
- Test: `apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/ResponseRulesTest.kt`
- Test: `apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/ResponseReducerTest.kt`

**Interfaces:**
- Produces:
  - `enum class ResponseKind { MUTE, DONE }`, `enum class MatchScope { SUBJECT, KIND, SOURCE }`, `enum class AudienceScope { PERSONAL, FAMILY }`
  - `data class ContentResponse(id, kind, subjectRef, matchScope, audienceScope, userId: String?, createdBy: String, label: String, sublabel: String?, note: String?, version: Long, pending: Boolean = false)`
  - `data class ResponseState(val rules: List<ContentResponse> = emptyList(), val sheet: ResponseSheetState? = null, val lastReceipt: ResponseReceipt? = null)`
  - `ResponseRules.matches(rule, subjectRef, kind, source): Boolean`
  - `ResponseRules.suppress(items: List<NowItem>, rules: List<ContentResponse>, viewerUserId: String?): List<NowItem>`
  - `data class ResponsesLoaded(val rules: List<ContentResponse>) : Action` — the sole DB→store bridge

- [ ] **Step 1: Write the failing rule test**

```kotlin
// apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/ResponseRulesTest.kt
package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.features.responses.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ResponseRulesTest {
  private fun rule(
    subjectRef: String, matchScope: MatchScope,
    audience: AudienceScope = AudienceScope.FAMILY, userId: String? = null,
    kind: ResponseKind = ResponseKind.MUTE,
  ) = ContentResponse(
    id = "r", kind = kind, subjectRef = subjectRef, matchScope = matchScope,
    audienceScope = audience, userId = userId, createdBy = "u_mom",
    label = "l", sublabel = null, note = null, version = 1,
  )

  private fun item(id: String, subjectKey: String, reasonKind: String, source: String? = null) =
    NowItem(id = id, origin = Origin.DERIVED, reasonKind = reasonKind, title = "t", why = "w",
            subjectKey = subjectKey, target = null, authoredSource = source)

  @Test fun subjectScopeMatchesExactly() {
    val r = rule("hub:h/block:b", MatchScope.SUBJECT)
    assertTrue(ResponseRules.matches(r, "hub:h/block:b", "countdown", null))
    assertFalse(ResponseRules.matches(r, "hub:h/block:c", "countdown", null))
  }

  @Test fun kindScopeMatchesTheReasonKind() {
    val r = rule("kind:weather", MatchScope.KIND)
    assertTrue(ResponseRules.matches(r, "card:c1", "weather", null))
    assertFalse(ResponseRules.matches(r, "card:c1", "countdown", null))
  }

  @Test fun sourceScopeMatchesTheAuthoredSource() {
    val r = rule("source:morning-briefing", MatchScope.SOURCE)
    assertTrue(ResponseRules.matches(r, "card:c1", "email", "morning-briefing"))
    assertFalse(ResponseRules.matches(r, "card:c1", "email", "other"))
  }

  @Test fun familyMuteSuppressesForEveryone() {
    val items = listOf(item("i1", "card:c1", "weather"), item("i2", "card:c2", "countdown"))
    val out = ResponseRules.suppress(items, listOf(rule("kind:weather", MatchScope.KIND)), "u_dad")
    assertEquals(listOf("i2"), out.map { it.id })
  }

  @Test fun personalMuteSuppressesOnlyForItsOwner() {
    val r = rule("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, userId = "u_mom")
    val items = listOf(item("i1", "card:c1", "weather"))
    assertEquals(emptyList(), ResponseRules.suppress(items, listOf(r), "u_mom").map { it.id })
    assertEquals(listOf("i1"), ResponseRules.suppress(items, listOf(r), "u_dad").map { it.id })
  }

  @Test fun doneSuppressesForEveryone() {
    val r = rule("card:c1", MatchScope.SUBJECT, kind = ResponseKind.DONE)
    assertEquals(emptyList(), ResponseRules.suppress(listOf(item("i1", "card:c1", "checklist")), listOf(r), "u_dad").map { it.id })
  }

  // The derived lane keys on hub nodes; a mute on a hub must not suppress a sibling hub.
  @Test fun subjectMatchIsNotAPrefixMatch() {
    val r = rule("hub:h1", MatchScope.SUBJECT)
    assertEquals(listOf("i1"), ResponseRules.suppress(listOf(item("i1", "hub:h10/block:b", "countdown")), listOf(r), "u").map { it.id })
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest --tests "*ResponseRulesTest*"
```
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement the model**

```kotlin
// apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/responses/ResponseModel.kt
package com.sloopworks.dayfold.client.features.responses

// ADR 0064 — Tier-1 synced response rows. Mirrors apps/api content_responses exactly; the
// wire names are snake_case, the Kotlin names are camelCase, and the enums serialize to the
// same lowercase tokens the CHECK constraints allow.
enum class ResponseKind { MUTE, DONE;
  val wire: String get() = name.lowercase()
  companion object { fun of(s: String) = if (s == "done") DONE else MUTE }
}

enum class MatchScope { SUBJECT, KIND, SOURCE;
  val wire: String get() = name.lowercase()
  companion object { fun of(s: String) = entries.firstOrNull { it.wire == s } ?: SUBJECT }
}

enum class AudienceScope { PERSONAL, FAMILY;
  val wire: String get() = name.lowercase()
  companion object { fun of(s: String) = if (s == "family") FAMILY else PERSONAL }
}

data class ContentResponse(
  val id: String,
  val kind: ResponseKind,
  val subjectRef: String,
  val matchScope: MatchScope,
  val audienceScope: AudienceScope,
  /** Owner of a personal rule; null for family rules. */
  val userId: String?,
  /** Always attributed (decided Q2) — drives "muted by Mom". */
  val createdBy: String,
  val label: String,
  val sublabel: String?,
  val note: String?,
  val version: Long,
  /** Optimistic local write not yet acked (offline states, P1 vocabulary). */
  val pending: Boolean = false,
)

/** A device-only derived-lane rule (decided Q4) — same shape, never synced, never in the outbox. */
data class ResponseSheetState(
  val subjectRef: String,
  val subjectTitle: String,
  val reasonKind: String,
  val source: String?,
  val surface: ResponseSurface,
  val step: ResponseStep = ResponseStep.VERBS,
  val pendingMatchScope: MatchScope = MatchScope.KIND,
  val pendingAudience: AudienceScope = AudienceScope.PERSONAL,
)

enum class ResponseSurface { NOW, DETAIL, HUB }
enum class ResponseStep { VERBS, SCOPE, DONE_NOTE }

/** The snackbar receipt shown after an act (snackbar + Undo at act time). */
data class ResponseReceipt(val responseId: String, val message: String, val undoable: Boolean, val offline: Boolean)

data class ResponseState(
  val rules: List<ContentResponse> = emptyList(),
  val sheet: ResponseSheetState? = null,
  val lastReceipt: ResponseReceipt? = null,
)
```

- [ ] **Step 4: Implement the pure rule engine**

```kotlin
// apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/responses/ResponseRules.kt
package com.sloopworks.dayfold.client.features.responses

import com.sloopworks.dayfold.client.NowItem

// ADR 0064 decided-Q4 — the derived lane reads the SAME rule list, enforced on-device (the
// server never sees derived items, so it cannot enforce them). This mirrors the server's
// matchesRule() exactly: three string equalities, no content inspection, no prefix matching.
object ResponseRules {

  fun matches(rule: ContentResponse, subjectRef: String, reasonKind: String, source: String?): Boolean =
    when (rule.matchScope) {
      MatchScope.SUBJECT -> subjectRef == rule.subjectRef
      MatchScope.KIND    -> "kind:$reasonKind" == rule.subjectRef
      MatchScope.SOURCE  -> source != null && "source:$source" == rule.subjectRef
    }

  /** True when [rule] applies to the member viewing the feed. */
  private fun appliesTo(rule: ContentResponse, viewerUserId: String?): Boolean =
    rule.kind == ResponseKind.DONE ||
      rule.audienceScope == AudienceScope.FAMILY ||
      (viewerUserId != null && rule.userId == viewerUserId)

  /** Drop every item a rule suppresses for this viewer. Pure; order-preserving. */
  fun suppress(
    items: List<NowItem>, rules: List<ContentResponse>, viewerUserId: String?,
  ): List<NowItem> {
    if (rules.isEmpty()) return items
    val live = rules.filter { appliesTo(it, viewerUserId) }
    if (live.isEmpty()) return items
    return items.filterNot { item ->
      live.any { matches(it, item.subjectKey, item.reasonKind, item.authoredSource) }
    }
  }
}
```

- [ ] **Step 5: Implement the actions and reducer**

```kotlin
// apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/responses/ResponseActions.kt
package com.sloopworks.dayfold.client.features.responses

import org.reduxkotlin.Action   // match the import the other feature action files use

/** DB→store bridge — the SOLE writer of state.responses.rules (mirrors HiddenLoaded/SurfacingLoaded). */
data class ResponsesLoaded(val rules: List<ContentResponse>) : Action

data class OpenResponseSheet(
  val subjectRef: String, val subjectTitle: String, val reasonKind: String,
  val source: String?, val surface: ResponseSurface,
) : Action
data object CloseResponseSheet : Action
data object ResponseStepScope : Action
data object ResponseStepDoneNote : Action
data class SetResponseMatchScope(val scope: MatchScope) : Action
data class SetResponseAudience(val audience: AudienceScope) : Action
data class ResponseReceiptShown(val receipt: ResponseReceipt) : Action
data object ResponseReceiptDismissed : Action
```

```kotlin
// apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/responses/ResponseReducer.kt
package com.sloopworks.dayfold.client.features.responses

import org.reduxkotlin.Action

// Pure (ADR 0013/0058). Effects — enqueueing the op, the optimistic DB write, undo — live in
// ResponseEngine; nothing here touches the network or the DB.
fun responseReducer(state: ResponseState, action: Action): ResponseState = when (action) {
  is ResponsesLoaded -> state.copy(rules = action.rules)
  is OpenResponseSheet -> state.copy(
    sheet = ResponseSheetState(
      subjectRef = action.subjectRef, subjectTitle = action.subjectTitle,
      reasonKind = action.reasonKind, source = action.source, surface = action.surface,
    ),
  )
  is CloseResponseSheet -> state.copy(sheet = null)
  is ResponseStepScope -> state.copy(sheet = state.sheet?.copy(step = ResponseStep.SCOPE))
  is ResponseStepDoneNote -> state.copy(sheet = state.sheet?.copy(step = ResponseStep.DONE_NOTE))
  is SetResponseMatchScope -> state.copy(sheet = state.sheet?.copy(pendingMatchScope = action.scope))
  is SetResponseAudience -> state.copy(sheet = state.sheet?.copy(pendingAudience = action.audience))
  is ResponseReceiptShown -> state.copy(lastReceipt = action.receipt)
  is ResponseReceiptDismissed -> state.copy(lastReceipt = null)
  else -> state
}
```

- [ ] **Step 6: Wire the slice into `AppState` and the root reducer**

`Model.kt`: add `val responses: ResponseState = ResponseState(),` to `AppState`.
`Reducer.kt`: add `responses = responseReducer(state.responses, action),` to the root delegation.

- [ ] **Step 7: Write and run the reducer test**

```kotlin
// apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/ResponseReducerTest.kt
package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.features.responses.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponseReducerTest {
  @Test fun openingTheSheetStartsOnTheVerbStep() {
    val s = responseReducer(ResponseState(), OpenResponseSheet(
      "card:c1", "Rain at soccer practice", "weather", "morning-briefing", ResponseSurface.NOW))
    assertEquals(ResponseStep.VERBS, s.sheet?.step)
    assertEquals(AudienceScope.PERSONAL, s.sheet?.pendingAudience)   // personal is the default
  }

  @Test fun scopeStepAndAudienceAreIndependent() {
    var s = responseReducer(ResponseState(), OpenResponseSheet(
      "card:c1", "t", "weather", null, ResponseSurface.NOW))
    s = responseReducer(s, ResponseStepScope)
    s = responseReducer(s, SetResponseAudience(AudienceScope.FAMILY))
    assertEquals(ResponseStep.SCOPE, s.sheet?.step)
    assertEquals(AudienceScope.FAMILY, s.sheet?.pendingAudience)
  }

  @Test fun closingClearsTheSheetButNotTheRules() {
    val rules = listOf(ContentResponse("r", ResponseKind.MUTE, "kind:weather", MatchScope.KIND,
      AudienceScope.FAMILY, null, "u", "Weather cards", null, null, 1))
    var s = responseReducer(ResponseState(rules = rules), OpenResponseSheet(
      "card:c1", "t", "weather", null, ResponseSurface.NOW))
    s = responseReducer(s, CloseResponseSheet)
    assertNull(s.sheet)
    assertEquals(rules, s.rules)
  }
}
```

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add apps/client/src/commonMain apps/client/src/commonTest
git commit -m "Add the client response model, pure rule engine, and reducer"
```

## Task 9: DB persistence, delta apply, and the outbox lane

**Files:**
- Modify: `.../db/Content.sq`
- Modify: `.../client/ContentStore.kt`
- Modify: `.../client/SyncClient.kt`
- Modify: `.../client/SyncEngine.kt` (the drain loop at ~line 236)
- Test: `apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/ResponseSyncTest.kt`

**Interfaces:**
- Consumes: `ContentResponse` (Task 8), `OutboxSender.classify` (existing).
- Produces: `ContentStore.upsertResponse(r: ContentResponse)`, `ContentStore.deleteResponse(id: String)`, `ContentStore.loadResponses(): List<ContentResponse>`, `ContentStore.enqueueResponseOp(opId, id, type: String, payload: String)`; `SyncClient.putResponse(familyId, accessToken, id, payload, opId): PutResult`, `SyncClient.deleteResponse(familyId, accessToken, id, opId): PutResult`.

- [ ] **Step 1: Add the table and queries to `Content.sq`**

```sql
-- ADR 0064 — Tier-1 synced response rows (mute + done). SYNCED, unlike the hide/surfacing
-- tables above: a rule is an intentional policy statement, not passive behavior. Applied by
-- applyDelta from the /sync `response` rows; written optimistically by ResponseEngine and
-- drained through the shared outbox.
CREATE TABLE content_response (
  id             TEXT NOT NULL PRIMARY KEY,
  kind           TEXT NOT NULL,
  subject_ref    TEXT NOT NULL,
  match_scope    TEXT NOT NULL,
  audience_scope TEXT NOT NULL,
  user_id        TEXT,
  created_by     TEXT NOT NULL,
  label          TEXT NOT NULL,
  sublabel       TEXT,
  note           TEXT,
  version        INTEGER NOT NULL DEFAULT 1,
  pending        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX content_response_subject_idx ON content_response(subject_ref);

upsertResponse:
INSERT OR REPLACE INTO content_response
  (id, kind, subject_ref, match_scope, audience_scope, user_id, created_by, label, sublabel, note, version, pending)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?);

deleteResponse:
DELETE FROM content_response WHERE id = ?;

selectResponses:
SELECT * FROM content_response;

clearResponses:
DELETE FROM content_response;
```

Add `clearResponses` to both `wipe()` and `wipeSyncedContent()` in `ContentStore` — a rule is synced tenant content, so tenancy revocation and a content-schema resync must both drop it (unlike the membership cache, which `wipeSyncedContent` deliberately preserves).

- [ ] **Step 2: Write the failing test**

```kotlin
// apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/ResponseSyncTest.kt
package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.features.responses.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseSyncTest {
  @Test fun applyDeltaUpsertsAResponseRow() {
    val store = testContentStore()
    store.applyDelta(listOf(responseRow(id = "r1", label = "Weather cards", deleted = false)))
    assertEquals(listOf("r1"), store.loadResponses().map { it.id })
  }

  @Test fun applyDeltaTombstoneRemovesIt() {
    val store = testContentStore()
    store.applyDelta(listOf(responseRow(id = "r1", label = "x", deleted = false)))
    store.applyDelta(listOf(responseRow(id = "r1", label = "x", deleted = true)))
    assertTrue(store.loadResponses().isEmpty())
  }

  @Test fun anOptimisticWriteIsPendingUntilTheEchoClearsIt() {
    val store = testContentStore()
    store.upsertResponse(sampleResponse(id = "r1").copy(pending = true))
    assertTrue(store.loadResponses().single().pending)
    store.applyDelta(listOf(responseRow(id = "r1", label = "x", deleted = false, version = 2)))
    assertEquals(false, store.loadResponses().single().pending)
  }

  @Test fun wipeSyncedContentDropsRules() {
    val store = testContentStore()
    store.upsertResponse(sampleResponse(id = "r1"))
    store.wipeSyncedContent()
    assertTrue(store.loadResponses().isEmpty())
  }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest --tests "*ResponseSyncTest*"
```
Expected: FAIL — `loadResponses` unresolved.

- [ ] **Step 4: Implement the store methods and the `applyDelta` arm**

In `ContentStore.applyDelta`, add a `"response"` case beside the existing `"card"`/`"hub"`/`"section"`/`"block"` cases: on `deleted` → `deleteResponse(id)`; otherwise decode the payload and `upsertResponse(..., pending = 0)` — the echo is what clears `pending`, exactly as `clearBlockPending` does for blocks.

- [ ] **Step 5: Add the HTTP methods**

```kotlin
// apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/SyncClient.kt — append
suspend fun putResponse(
  familyId: String, accessToken: String, responseId: String, payload: String, opId: String,
): PutResult {
  val resp = http.put("$api/families/$familyId/responses/$responseId") {
    header("authorization", "Bearer $accessToken")
    header("idempotency-key", opId)
    contentType(ContentType.Application.Json)
    setBody(payload)
  }
  return PutResult(resp.status.value, versionFrom(resp))
}

suspend fun deleteResponse(
  familyId: String, accessToken: String, responseId: String, opId: String,
): PutResult {
  val resp = http.delete("$api/families/$familyId/responses/$responseId") {
    header("authorization", "Bearer $accessToken")
    header("idempotency-key", opId)
  }
  return PutResult(resp.status.value, null)
}
```

- [ ] **Step 6: Dispatch response ops in the drain loop**

`SyncEngine.kt` ~line 236 currently branches only on `op.type == "delete"`. Make it branch on `targetKind` first:

```kotlin
val sent = when {
  op.targetKind == "response" && op.type == "delete" ->
    syncClient.deleteResponse(familyId, accessToken, op.targetId, op.opId)
  op.targetKind == "response" ->
    syncClient.putResponse(familyId, accessToken, op.targetId, op.payload, op.opId)
  op.type == "delete" ->
    syncClient.deleteBlock(familyId, accessToken, op.targetId, op.opId)
  else ->
    syncClient.putBlock(familyId, accessToken, op.targetId, op.payload, op.baseVersion, op.opId)
}
```

`OutboxSender.classify` needs no change: 200/204 → Acked, 409 (suppressed by a rule that raced us) falls into the `400..499` arm → Drop, which is right — a rule that already suppresses the subject makes our write moot.

- [ ] **Step 7: Run the client suite and commit**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest
git add apps/client/src
git commit -m "Persist, sync, and drain content responses on the client"
```

## Task 10: `ResponseEngine` — optimistic writes, receipts, undo

**Files:**
- Create: `.../client/ResponseEngine.kt`
- Modify: `.../client/DayfoldCommands.kt` + `DayfoldCommandPort.kt` (expose the commands to Compose)
- Modify: `.../client/DayfoldRuntime.kt` (own the engine)
- Test: `apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/ResponseEngineTest.kt`

**Interfaces:**
- Consumes: `ContentStore.upsertResponse`/`enqueueResponseOp`, `SubjectRef` (Task 3), `ResponseState` (Task 8).
- Produces on `DayfoldCommandPort`: `mute(subjectRef, matchScope, audience, label, sublabel)`, `markDone(subjectRef, label, note: String?)`, `removeResponse(id)`, `undoLastResponse()`.

Effects live here, not in the reducer (ADR 0058 runtime-owned effects). Each command: mint a ULID op id → write the row locally with `pending = true` → enqueue the outbox op → dispatch `ResponseReceiptShown`.

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/ResponseEngineTest.kt
package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.features.responses.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseEngineTest {
  @Test fun muteWritesOptimisticallyAndEnqueuesOneOp() = runEngineTest { engine, store, state ->
    engine.mute("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, "Weather cards", "From Morning briefing")
    val row = store.loadResponses().single()
    assertEquals(ResponseKind.MUTE, row.kind)
    assertTrue(row.pending)
    assertEquals(1, store.pendingOpCount("response"))
  }

  @Test fun offlineMuteStillAppliesLocallyAndSaysSo() = runEngineTest(online = false) { engine, store, state ->
    engine.mute("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, "Weather cards", null)
    assertEquals(1, store.loadResponses().size)
    assertEquals(true, state().responses.lastReceipt?.offline)
    assertEquals("Muted — will sync when you're online", state().responses.lastReceipt?.message)
  }

  @Test fun undoDropsTheQueuedWriteAndTheLocalRow() = runEngineTest(online = false) { engine, store, state ->
    engine.mute("kind:weather", MatchScope.KIND, AudienceScope.PERSONAL, "Weather cards", null)
    engine.undoLastResponse()
    assertTrue(store.loadResponses().isEmpty())
    assertEquals(0, store.pendingOpCount("response"))
  }

  @Test fun markDoneCarriesTheNoteAndIsAlwaysFamilyScoped() = runEngineTest { engine, store, _ ->
    engine.markDone("card:c1", "Verify emergency contact", "Confirmed — used Grandma's new number.")
    val row = store.loadResponses().single()
    assertEquals(ResponseKind.DONE, row.kind)
    assertEquals(AudienceScope.FAMILY, row.audienceScope)
    assertEquals(MatchScope.SUBJECT, row.matchScope)
    assertEquals("Confirmed — used Grandma's new number.", row.note)
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest --tests "*ResponseEngineTest*"
```
Expected: FAIL.

- [ ] **Step 3: Implement the engine**

```kotlin
// apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/ResponseEngine.kt
package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.features.responses.*

// ADR 0064 — runtime-owned effects (ADR 0058). Every response is an OPTIMISTIC write (States
// P1 vocabulary): the row lands locally at once, the op queues, and the receipt is honest
// about timing — a rule cannot stop a run that already happened, it takes effect next run.
class ResponseEngine(
  private val store: DayfoldStore,
  private val contentStore: ContentStore,
  private val opIds: () -> String,          // injected ULID minter — pure tests, no randomness
  private val nowIso: () -> String,
  private val isOnline: () -> Boolean,
  private val viewerUserId: () -> String?,
) {
  fun mute(
    subjectRef: String, matchScope: MatchScope, audience: AudienceScope,
    label: String, sublabel: String?,
  ) = write(
    ContentResponse(
      id = opIds(), kind = ResponseKind.MUTE, subjectRef = subjectRef, matchScope = matchScope,
      audienceScope = audience,
      userId = if (audience == AudienceScope.PERSONAL) viewerUserId() else null,
      createdBy = viewerUserId().orEmpty(), label = label, sublabel = sublabel, note = null,
      version = 1, pending = true,
    ),
    onlineMessage = "Muted",
    offlineMessage = "Muted — will sync when you're online",
  )

  // Done is always family-wide on a concrete subject — mirrors the server's 422 shape check.
  fun markDone(subjectRef: String, label: String, note: String?) = write(
    ContentResponse(
      id = opIds(), kind = ResponseKind.DONE, subjectRef = subjectRef,
      matchScope = MatchScope.SUBJECT, audienceScope = AudienceScope.FAMILY, userId = null,
      createdBy = viewerUserId().orEmpty(), label = label, sublabel = null, note = note,
      version = 1, pending = true,
    ),
    onlineMessage = "Marked done",
    offlineMessage = "Marked done — will sync when you're online",
  )

  private fun write(row: ContentResponse, onlineMessage: String, offlineMessage: String) {
    contentStore.upsertResponse(row)
    contentStore.enqueueResponseOp(opId = row.id, id = row.id, type = "upsert", payload = row.toWireJson())
    contentStore.reloadResponsesInto(store)
    store.dispatch(ResponseReceiptShown(ResponseReceipt(
      responseId = row.id,
      message = if (isOnline()) onlineMessage else offlineMessage,
      undoable = true,
      offline = !isOnline(),
    )))
  }

  fun removeResponse(id: String) {
    contentStore.deleteResponse(id)
    contentStore.enqueueResponseOp(opId = opIds(), id = id, type = "delete", payload = "")
    contentStore.reloadResponsesInto(store)
  }

  // Undo works offline — the queued write is simply dropped (design GAP 6, third point).
  fun undoLastResponse() {
    val id = store.state.responses.lastReceipt?.responseId ?: return
    contentStore.dropPendingOpsFor(id)
    contentStore.deleteResponse(id)
    contentStore.reloadResponsesInto(store)
    store.dispatch(ResponseReceiptDismissed)
  }
}
```

`toWireJson()` is a small `kotlinx.serialization` mapping to the snake_case body the endpoint parses — add it beside `ContentResponse`.

- [ ] **Step 4: Expose the commands**

Add `mute`, `markDone`, `removeResponse`, `undoLastResponse` to `DayfoldCommandPort` (method-only, per the redux-kotlin note in `agent-dev-loop.md`) and implement them in `DayfoldCommands` by delegating to the engine. Construct the engine in `DayfoldRuntime`.

- [ ] **Step 5: Run and commit**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest
git add apps/client/src
git commit -m "Add ResponseEngine — optimistic response writes, receipts, and undo"
```

---

# Phase D — the UI

Six views, matching the six design gaps 1:1. `designs/content-feedback/Response-Phone.dc.html` is the reference for every layout and every string; open it in a browser next to the implementation.

## Task 11: The response sheet and its verb rows

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/responses/ResponseSheet.kt`
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/ResponseSheetTest.kt`

**Interfaces:**
- Consumes: `ResponseSheetState`, `ResponseSurface` (Task 8); `DayfoldCommandPort` (Task 10).
- Produces: `@Composable fun ResponseSheet(state: ResponseSheetState, store: SelectorStore, port: DayfoldCommandPort)`; `fun verbRowsFor(surface: ResponseSurface): List<VerbRow>` — pure, testable without Compose.

**The verb rows, in this order, on every surface** (only the copy changes by surface — `NOTES.md` § Placement):

| Verb | Now / Detail sub-line | Hub sub-line |
|---|---|---|
| Mark done | "Completes it for your family — future runs remember" | same |
| Hide for me | "Your family still sees it" | "Your family still sees it in this hub" |
| Don't add this again | "Nothing similar is added in future" | "Don't add to this hub again" / "This block stays; nothing similar is re-added" |
| Remove, and don't re-add | *(hub only)* | "Removes it for everyone — pairs delete with the rule" |

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/ResponseSheetTest.kt
package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.features.responses.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseSheetTest {
  @Test fun everySurfaceKeepsTheSameVerbOrder() {
    val now = verbRowsFor(ResponseSurface.NOW).map { it.verb }
    val hub = verbRowsFor(ResponseSurface.HUB).map { it.verb }
    assertEquals(listOf(Verb.DONE, Verb.HIDE, Verb.MUTE), now)
    assertEquals(listOf(Verb.DONE, Verb.HIDE, Verb.MUTE, Verb.DELETE_AND_MUTE), hub)
  }

  @Test fun hubCopyNamesTheHub() {
    val mute = verbRowsFor(ResponseSurface.HUB).single { it.verb == Verb.MUTE }
    assertEquals("Don't add to this hub again", mute.title)
    assertEquals("This block stays; nothing similar is re-added", mute.subtitle)
  }

  @Test fun theDeletePairingIsHubOnlyAndReadsAsADelete() {
    val row = verbRowsFor(ResponseSurface.HUB).single { it.verb == Verb.DELETE_AND_MUTE }
    assertEquals("Remove, and don't re-add", row.title)
    assertEquals("Removes it for everyone — pairs delete with the rule", row.subtitle)
    assertTrue(row.destructive)      // coral, like every delete
  }

  // The calm constitution bans engagement farming — no positive-signal row, ever.
  @Test fun thereIsNoThumbsUpRow() {
    ResponseSurface.entries.forEach { s ->
      assertTrue(verbRowsFor(s).none { it.title.contains("more like", ignoreCase = true) })
    }
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :ui:desktopTest --tests "*ResponseSheetTest*"
```
Expected: FAIL.

- [ ] **Step 3: Implement `verbRowsFor` and the composable**

Pure model first (that is what the test pins):

```kotlin
enum class Verb { DONE, HIDE, MUTE, DELETE_AND_MUTE }

data class VerbRow(
  val verb: Verb, val icon: String, val title: String, val subtitle: String,
  val destructive: Boolean = false, val emphasized: Boolean = false,
)

fun verbRowsFor(surface: ResponseSurface): List<VerbRow> = buildList {
  add(VerbRow(Verb.DONE, "check_circle", "Mark done",
      "Completes it for your family — future runs remember", emphasized = true))
  add(VerbRow(Verb.HIDE, "visibility_off", "Hide for me",
      if (surface == ResponseSurface.HUB) "Your family still sees it in this hub"
      else "Your family still sees it"))
  add(VerbRow(Verb.MUTE, "do_not_disturb_on",
      if (surface == ResponseSurface.HUB) "Don't add to this hub again" else "Don't add this again",
      if (surface == ResponseSurface.HUB) "This block stays; nothing similar is re-added"
      else "Nothing similar is added in future"))
  if (surface == ResponseSurface.HUB) {
    add(VerbRow(Verb.DELETE_AND_MUTE, "delete", "Remove, and don't re-add",
        "Removes it for everyone — pairs delete with the rule", destructive = true))
  }
}
```

Then the `ModalBottomSheet` rendering them at 48dp minimum row height, tapping MUTE → `ResponseStepScope`, DONE → `ResponseStepDoneNote`, HIDE → the existing W5 hide command, DELETE_AND_MUTE → delete then `mute(matchScope = SUBJECT)`.

- [ ] **Step 4: Run and commit**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :ui:desktopTest
git add apps/ui/src
git commit -m "Add the response sheet with one verb vocabulary per surface"
```

## Task 12: The scope step (design GAP 1)

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/responses/ResponseScopeStep.kt`
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/ResponseScopeStepTest.kt`

**Interfaces:**
- Consumes: `ResponseSheetState.pendingMatchScope` / `.pendingAudience`, `SetResponseMatchScope`, `SetResponseAudience`.
- Produces: `fun scopeRowsFor(state: ResponseSheetState): List<ScopeRow>`; `fun commitLabel(state: ResponseSheetState, subjectNoun: String): String`.

Three rungs, from `Response-Phone` view `scope`: "Just this card" / "Any {noun} cards" / "Everything from {source}". The third rung **deep-links to Smart Briefings** — it does not mint a rule. The who-segment defaults to "For you", and the commit button names the final action.

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/ResponseScopeStepTest.kt
package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.features.responses.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseScopeStepTest {
  private val sheet = ResponseSheetState(
    subjectRef = "card:c1", subjectTitle = "Rain at soccer practice",
    reasonKind = "weather", source = "Morning briefing", surface = ResponseSurface.NOW,
    step = ResponseStep.SCOPE,
  )

  @Test fun theKindRungIsPreselected() {
    assertEquals(MatchScope.KIND, sheet.pendingMatchScope)
    assertTrue(scopeRowsFor(sheet).single { it.scope == MatchScope.KIND }.selected)
  }

  @Test fun theSourceRungDeepLinksInsteadOfMinting() {
    val row = scopeRowsFor(sheet).single { it.scope == MatchScope.SOURCE }
    assertEquals("Everything from Morning briefing", row.title)
    assertEquals("Pauses the routine — manage in Smart Briefings", row.subtitle)
    assertTrue(row.deepLinksToRoutines)
  }

  @Test fun theCommitButtonNamesTheFinalAction() {
    assertEquals("Mute weather cards for you", commitLabel(sheet, "weather"))
    assertEquals("Mute weather cards for everyone",
                 commitLabel(sheet.copy(pendingAudience = AudienceScope.FAMILY), "weather"))
  }

  @Test fun familyWideSpeaksTheConsequenceBeforeTheCommit() {
    assertEquals(
      "Family-wide mutes are visible to everyone in Settings and removable by any adult — always attributed.",
      audienceFootnote(),
    )
  }
}
```

- [ ] **Step 2: Run, watch it fail, implement, run again**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :ui:desktopTest --tests "*ResponseScopeStepTest*"
```

Implementation notes: the two audience chips are a single-select segmented control, "For you" first and preselected; the footnote text above is fixed; the commit button calls `port.mute(...)` with `subjectRef` computed from the selected rung — `SubjectRef.card(...)` for SUBJECT, `SubjectRef.kind(reasonKind)` for KIND — and closes the sheet.

- [ ] **Step 3: Commit**

```bash
git add apps/ui/src
git commit -m "Add the mute scope step with the personal-default audience segment"
```

## Task 13: Entry points — ⋮, the why-chip, the detail footer, the swipe escalation (GAPs 2 and 4)

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowFeedScreen.kt`
- Modify: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/HubScreens.kt`
- Modify: the card-detail composable under `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/cards/`
- Modify: `.../db/Content.sq` (Tier-0 `response_offer` table)
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/ResponseEntryPointsTest.kt`

**Interfaces:**
- Consumes: `OpenResponseSheet` (Task 8).
- Produces: `fun swipeSnackbarFor(subjectRef, isSmartContent, alreadyOffered): SnackbarSpec` — the once-ever escalation decision, pure.

The once-ever flag is **Tier 0** — device state, behavioral, never synced:

```sql
-- ADR 0064 GAP 4 — the once-ever swipe-escalation offer. Tier 0: behavioral, device-local,
-- NEVER synced (not in Changes/applyDelta/outbox), same boundary as hide + surfacing above.
CREATE TABLE response_offer (subject_ref TEXT NOT NULL PRIMARY KEY, offered_at TEXT NOT NULL);
```

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/ResponseEntryPointsTest.kt
package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.features.responses.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponseEntryPointsTest {
  @Test fun smartContentGetsTheOfferExactlyOnce() {
    val first = swipeSnackbarFor("card:c1", isSmartContent = true, alreadyOffered = false)
    assertEquals("Hidden for you", first.message)
    assertEquals("Don't add again?", first.actionLabel)

    val second = swipeSnackbarFor("card:c1", isSmartContent = true, alreadyOffered = true)
    assertEquals("Hidden for you", second.message)
    assertNull(second.actionLabel)          // a repeat offer is a nag
  }

  @Test fun memberAuthoredContentNeverGetsTheOffer() {
    assertNull(swipeSnackbarFor("card:c1", isSmartContent = false, alreadyOffered = false).actionLabel)
  }

  // Standard single-action snackbar — no two-button invention (GAP 4, first point).
  @Test fun thereIsOnlyEverOneAction() {
    val spec = swipeSnackbarFor("card:c1", isSmartContent = true, alreadyOffered = false)
    assertEquals(1, listOfNotNull(spec.actionLabel).size)
  }
}
```

- [ ] **Step 2: Run, watch it fail, implement**

```kotlin
data class SnackbarSpec(val message: String, val actionLabel: String?)

// GAP 4 — swipe stays hide-only (W5). The escalation is the snackbar's single action slot,
// offered once per subject EVER; a second hide of the same subject gets a plain snackbar.
// Undo moves to the hidden section's "Show" — declining costs nothing, the card is hidden
// either way.
fun swipeSnackbarFor(subjectRef: String, isSmartContent: Boolean, alreadyOffered: Boolean) =
  SnackbarSpec(
    message = "Hidden for you",
    actionLabel = if (isSmartContent && !alreadyOffered) "Don't add again?" else null,
  )
```

- [ ] **Step 3: Wire the four entry points**

1. **Now card `⋮`** → `OpenResponseSheet(surface = NOW)`.
2. **Why-chip tap** → the same action. (The chip is the door; "Why am I seeing this?" is never a row *inside* the sheet.)
3. **Detail provenance footer** → "Mark done" primary + "Respond" tonal, both in the footer; the top-bar `⋮` opens the same sheet as the a11y door. Same verbs, same order — no second vocabulary.
4. **Swipe-hide** → existing hide command, then `swipeSnackbarFor(...)`; tapping the action records `response_offer` and opens the sheet **at the scope step**.

- [ ] **Step 4: Run the UI suite and commit**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :ui:desktopTest
git add apps/ui/src apps/client/src
git commit -m "Wire the four response entry points and the once-ever swipe escalation"
```

## Task 14: Done with note, and the hub delete pairing (GAPs 2 and 3)

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/responses/DoneNoteStep.kt`
- Modify: `apps/ui/.../HubScreens.kt`
- Test: `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DoneNoteStepTest.kt`

**Interfaces:**
- Consumes: `port.markDone(subjectRef, label, note)` (Task 10).
- Produces: `@Composable fun DoneNoteStep(...)`; the card-face instant-done pill path.

Two flows, both from `NOTES.md` § Done: the card-face pill is **instant done**, and its receipt row offers "Add note"; the sheet's Mark done row opens the note step with **"Just done" always one tap away**. The note is never demanded.

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/DoneNoteStepTest.kt
package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.features.responses.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoneNoteStepTest {
  @Test fun justDoneIsAlwaysOneTapAway() {
    val actions = doneNoteActions(noteDraft = "")
    assertEquals(listOf("Just done", "Save note"), actions.map { it.label })
    assertTrue(actions.first().enabled)          // never gated on typing a note
  }

  @Test fun theInstantPathOffersAddNoteOnTheReceipt() {
    assertEquals("Add note", instantDoneReceipt().actionLabel)
    assertEquals("Marked done", instantDoneReceipt().message)
  }

  @Test fun theHubPairingDeletesAndMutesTheSameSubject() {
    val plan = deleteAndMutePlan("hub:h9/section:s2/block:b4")
    assertEquals(listOf("delete", "mute"), plan.map { it.kind })
    assertEquals("hub:h9/section:s2/block:b4", plan.last().subjectRef)
    assertEquals(MatchScope.SUBJECT, plan.last().matchScope)
  }
}
```

- [ ] **Step 2: Run, watch it fail, implement, run again.** The "Remove, and don't re-add" row is one action with two effects — the sub-line says so, and it is coral like every delete.

- [ ] **Step 3: Commit**

```bash
git add apps/ui/src
git commit -m "Add done-with-note and the hub remove-and-mute pairing"
```

## Task 15: Settings › Smart content, and the offline vocabulary (GAPs 5 and 6)

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/com/sloopworks/dayfold/client/features/responses/SmartContentScreen.kt`
- Create: `.../client/features/responses/ResponseSelectors.kt` (in `:client`)
- Modify: `apps/ui/.../AccountScreen.kt` (a "Smart content" row)
- Modify: `apps/client/.../features/navigation/NavigationReducer.kt` + `RouteHost.kt` (the route)
- Test: `apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/ResponseSelectorsTest.kt`

**Interfaces:**
- Produces: `smartContentSections(state, viewerUserId, memberNames): SmartContentModel` with `mutedRules: List<RuleRow>`, `doneRecords: List<DoneRow>`, `lastRun: RunReceiptRow?`.

Three rule provenances, **distinguished by sub-line copy, never by colour alone** (GAP 5, first point):

| Provenance | Sub-line |
|---|---|
| personal | "From {source} · just you" |
| family | "Whole family · muted by {name} · any adult can remove" |
| device-only derived | "On this device · derived lane · never synced" |

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/ResponseSelectorsTest.kt
package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.features.responses.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseSelectorsTest {
  private val names = mapOf("u_mom" to "Mom", "u_dad" to "Dad")

  @Test fun eachProvenanceHasItsOwnSubline() {
    val rules = listOf(
      ContentResponse("r1", ResponseKind.MUTE, "kind:traffic", MatchScope.KIND,
        AudienceScope.PERSONAL, "u_dad", "u_dad", "Traffic cards", "From Morning briefing", null, 1),
      ContentResponse("r2", ResponseKind.MUTE, "kind:weather", MatchScope.KIND,
        AudienceScope.FAMILY, null, "u_mom", "Weather in Now", null, null, 1),
    )
    val m = smartContentSections(rules, doneRecords = emptyList(), viewerUserId = "u_dad", memberNames = names)
    assertEquals("From Morning briefing · just you", m.mutedRules[0].subline)
    assertEquals("Whole family · muted by Mom · any adult can remove", m.mutedRules[1].subline)
  }

  @Test fun doneRowsKeepTheBylineAndTheQuotedNote() {
    val done = listOf(ContentResponse("d1", ResponseKind.DONE, "card:c1", MatchScope.SUBJECT,
      AudienceScope.FAMILY, null, "u_mom", "Verify emergency contact", null,
      "Confirmed — used Grandma's new number.", 1))
    val m = smartContentSections(emptyList(), done, "u_dad", names)
    assertEquals("Verify emergency contact", m.doneRecords[0].title)
    assertEquals("\"Confirmed — used Grandma's new number.\" · Mom", m.doneRecords[0].subline)
  }

  @Test fun aDoneRowWithNoNoteIsJustAByline() {
    val done = listOf(ContentResponse("d2", ResponseKind.DONE, "card:c2", MatchScope.SUBJECT,
      AudienceScope.FAMILY, null, "u_dad", "RSVP scout campout", null, null, 1))
    assertEquals("Dad", smartContentSections(emptyList(), done, "u_mom", names).doneRecords[0].subline)
  }

  // Offline honesty (GAP 6): a rule cannot stop a run that already happened.
  @Test fun aPendingRuleSaysItTakesEffectNextRun() {
    val rules = listOf(ContentResponse("r1", ResponseKind.MUTE, "kind:weather", MatchScope.KIND,
      AudienceScope.PERSONAL, "u_dad", "u_dad", "Weather cards", "From Morning briefing", null, 1,
      pending = true))
    val row = smartContentSections(rules, emptyList(), "u_dad", names).mutedRules[0]
    assertTrue(row.offline)
    assertEquals("Saved on this phone — syncs & takes effect next run", row.pendingSubline)
  }
}
```

- [ ] **Step 2: Run, watch it fail, implement the selector, run again.**

- [ ] **Step 3: Build the screen**

Sections in order: MUTED RULES (each with a "Remove" pill) → DONE (line-through titles, byline + quoted note) → the content-blindness reassurance chip, verbatim: *"Your routine reads these before it adds anything. The server checks by ID — it never reads your content."* → LAST RUN, whose sub-line reads *"Added 3 · skipped 2 muted · saw 1 marked done"*.

The LAST RUN row is a **read-only projection of what the routine reports** — the "saw N marked done" figure comes from the ADR 0062 run receipt, which is unbuilt. Render the row only when a receipt exists; do not fabricate counts. Task 18 wires the real numbers.

- [ ] **Step 4: Offline states**

The offline banner is the standard top-bar sub-row (`OfflineBanner.kt`), never a modal. A pending rule row wears `cloud_off` + the pending sub-line. The receipt snackbar reads "Muted — will sync when you're online" with an Undo action that works offline.

- [ ] **Step 5: Add the six snapshot scenes**

Register scenes in `apps/ui/src/desktopTest/kotlin/com/sloopworks/dayfold/client/snapshot/SnapshotScenes.kt` named `response-scope`, `response-detail`, `response-hub`, `response-swipe`, `response-settings`, `response-offline` — one per design view, so the golden set and the mockups stay comparable 1:1.

```bash
cd apps && ./gradlew :ui:snapshotUi -PsnapshotArgs="--scene response-scope --out /tmp/scope.png"
```

Read each PNG against the matching view in `Response-Phone.dc.html` before recording goldens.

- [ ] **Step 6: Record goldens per-OS and run the suite**

```bash
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :ui:desktopTest --tests "*GoldenSnapshotTest" -Dsnapshot.record=true
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest :ui:desktopTest
```

**Record the linux goldens in the Docker recipe too** — mac↔linux wrap-flip drift runs as high as 22%, so a mac-only golden set fails CI.

- [ ] **Step 7: Commit**

```bash
git add apps/ui/src apps/client/src
git commit -m "Add Settings › Smart content and the offline response vocabulary"
```

---

# Phase E — the pipeline reads the rules

## Task 16: The derived lane enforces the same list on-device

**Files:**
- Modify: `apps/client/src/commonMain/kotlin/com/sloopworks/dayfold/client/NowRank.kt`
- Test: `apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/NowRankSuppressionTest.kt`

**Interfaces:**
- Consumes: `ResponseRules.suppress` (Task 8).

The server never sees derived items, so decided-Q4 ("derived lane reads the same rule list") can only be honored on-device. Suppression runs **after** candidate generation and **before** the calm budget, so a muted item does not consume a budget slot.

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/client/src/commonTest/kotlin/com/sloopworks/dayfold/client/NowRankSuppressionTest.kt
package com.sloopworks.dayfold.client

import com.sloopworks.dayfold.client.features.responses.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NowRankSuppressionTest {
  private val weatherMute = ContentResponse("r1", ResponseKind.MUTE, "kind:weather", MatchScope.KIND,
    AudienceScope.FAMILY, null, "u_mom", "Weather in Now", null, null, 1)

  @Test fun aMutedDerivedItemNeverReachesTheFeed() {
    val items = rankNow(candidates = listOf(weatherItem(), countdownItem()),
                        rules = listOf(weatherMute), viewerUserId = "u_dad")
    assertEquals(listOf("countdown"), items.map { it.reasonKind })
  }

  @Test fun suppressionRunsBeforeTheCalmBudgetSoItDoesNotEatASlot() {
    // budget of 2: without pre-budget suppression the muted item would occupy a slot and
    // the third candidate would be collapsed away.
    val items = rankNow(candidates = listOf(weatherItem(), countdownItem(), checklistItem()),
                        rules = listOf(weatherMute), viewerUserId = "u_dad", budget = 2)
    assertEquals(2, items.size)
    assertTrue(items.none { it.reasonKind == "weather" })
  }

  @Test fun aPersonalMuteDoesNotSuppressForOtherMembers() {
    val personal = weatherMute.copy(audienceScope = AudienceScope.PERSONAL, userId = "u_mom")
    assertTrue(rankNow(listOf(weatherItem()), listOf(personal), viewerUserId = "u_dad").isNotEmpty())
    assertTrue(rankNow(listOf(weatherItem()), listOf(personal), viewerUserId = "u_mom").isEmpty())
  }
}
```

- [ ] **Step 2: Run it, watch it fail, thread the rules through `rankNow`, run again.**

- [ ] **Step 3: Commit**

```bash
git add apps/client/src
git commit -m "Enforce response rules on the derived Now lane, before the calm budget"
```

## Task 17: The CLI reads the rules before it authors

**Files:**
- Create: `apps/cli/src/main/kotlin/Responses.kt`
- Modify: `apps/cli/src/main/kotlin/RoutineContract.kt`, `RoutineDiff.kt`, `Help.kt`, `Main.kt`
- Test: `apps/cli/src/test/kotlin/com/sloopworks/dayfold/cli/ResponsesTest.kt`

**Interfaces:**
- Produces: `dayfold responses list [--json]`; `fun filterMutedOps(ops: List<ChangesetOp>, rules: List<ContentResponse>): FilterResult` with `FilterResult(kept, skipped: List<SkippedOp>)`.

This is where "the routine reads these before it adds anything" becomes true for the authoring path that actually exists today. The gateway of ADR 0061 is unbuilt; the curator skill authoring through the CLI is not.

- [ ] **Step 1: Write the failing test**

```kotlin
// apps/cli/src/test/kotlin/com/sloopworks/dayfold/cli/ResponsesTest.kt
package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class ResponsesTest {
  @Test fun mutedOpsAreSkippedNotFailed() {
    val ops = listOf(op(id = "c1", subjectRef = "card:c1", kind = "weather"),
                     op(id = "c2", subjectRef = "card:c2", kind = "action"))
    val rules = listOf(rule(subjectRef = "kind:weather", matchScope = "kind"))
    val r = filterMutedOps(ops, rules)
    assertEquals(listOf("c2"), r.kept.map { it.id })
    assertEquals(listOf("c1"), r.skipped.map { it.id })
  }

  @Test fun theSkipReasonIsReportableOnARunReceipt() {
    val r = filterMutedOps(listOf(op("c1", "card:c1", "weather")),
                           listOf(rule("kind:weather", "kind")))
    assertEquals("muted", r.skipped.single().reason)
  }

  @Test fun aDoneSubjectIsSkippedToo() {
    val r = filterMutedOps(listOf(op("c1", "card:c1", "action")),
                           listOf(rule("card:c1", "subject", kind = "done")))
    assertEquals(1, r.skipped.size)
  }

  @Test fun noRulesMeansNoFiltering() {
    val ops = listOf(op("c1", "card:c1", "weather"))
    assertEquals(ops, filterMutedOps(ops, emptyList()).kept)
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd apps/cli && ./gradlew test --tests "*ResponsesTest*"
```

- [ ] **Step 3: Implement** — `GET /families/:fid/sync` already carries response rows, so the CLI reads them from the same place the client does. `filterMutedOps` reuses the identical three-equality predicate. Add the command to `Help.kt` and route it in `Main.kt`. `RoutineDiff` prints skipped ops in its plan output so a propose-confirm round shows the operator what a rule suppressed.

- [ ] **Step 4: Run and commit**

```bash
cd apps/cli && ./gradlew test
git add apps/cli
git commit -m "Read response rules in the CLI and skip muted changeset ops"
```

## Task 18: Documentation, changelog, and the deferred fix-it seam

**Files:**
- Modify: `docs/architecture.md`, `CHANGELOG.md`, `backlog/now.md`, `context/open-questions.md`
- Modify: `.agents/skills/dayfold-curator/SKILL.md` (edit ONLY the `.agents/` copy — `.claude/` is a symlink)

- [ ] **Step 1: Document the data flow** in `docs/architecture.md`: the response row's path (sheet → outbox → `PUT /responses` → `content_responses` → `/sync` → client DB → rule engine → derived lane + CLI pre-flight), and the sentence that matters — the server matches by ID and never reads the label, note, or title.

- [ ] **Step 2: Teach the curator skill the pre-flight.** Before authoring, list active rules; skip muted subjects; report the skip count. Mirror the CLI's `filterMutedOps` semantics so the skill and the CLI cannot disagree.

- [ ] **Step 3: Add the dated CHANGELOG entry** — this is user-visible product behavior and a new API surface, so `CLAUDE.md`'s end-of-session rule 6 applies.

- [ ] **Step 4: Record what stayed open** in `context/open-questions.md`: the fix-it/corrections channel (Tier 2, blocked on ADR 0062's run receipt); kid/14+ member rights on responses; post-sync undo semantics beyond the rule list; the deferred Q5 pause-suggestion. Do not build a stub for any of them — leaving the seam means not inventing the shape early.

- [ ] **Step 5: Update `backlog/now.md`** — flip the design-gate line from "imported, NOT build-authorized" to shipped, citing ADR 0064.

- [ ] **Step 6: Full verification before the PR**

```bash
cd apps/api && npx vitest run
cd apps/cli && ./gradlew test
cd apps && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:desktopTest :ui:desktopTest :swip-wiring:desktopTest
```

Every suite green, with the output pasted into the PR description. Do not claim completion from a partial run.

- [ ] **Step 7: Commit**

```bash
git add docs CHANGELOG.md backlog context .agents
git commit -m "Document smart-content responses and record what stayed open"
```

---

## Testing Strategy

**Per layer, and why each one exists:**

| Layer | Tool | What it must catch |
|---|---|---|
| Grammar | vitest + kotlin.test, both sides | The client and server building **different strings** for the same subject — the single failure mode that silently disables every rule. Tasks 1 and 3 assert identical literals on purpose. |
| Match predicate | vitest, kotlin.test | Over-matching (a prefix match suppressing a sibling hub) and under-matching (a null kind matching a class rule). |
| Endpoints | vitest vs live Postgres | Ownership assignment, op-id idempotency, the 422 shape checks, and that a client-sent `user_id` is ignored. |
| Suppression | vitest | Family blocks, personal strips, empty-audience rejects. |
| Sync | vitest | Personal rules tombstoned to non-owners; the cursor accepting the new type token; no regression in `hub-sync.test.ts`. |
| Client store | kotlin.test | Delta apply, pending→echo clearing, wipe boundaries. |
| Reducer / rules | kotlin.test | Purity, order preservation, viewer-scoped suppression. |
| Engine | kotlin.test with injected clock/ULID/online-ness | Optimistic apply, offline copy, undo dropping the queued write. |
| Compose | `:ui` desktopTest | Verb order and copy per surface, the once-ever offer, the three provenance sub-lines. |
| Visual | `rk snapshot` golden PNGs, **per-OS** | Layout drift; six scenes matching the six design views 1:1. |

**Cross-cutting invariants worth a dedicated test each:**

1. **Tier 0 never leaks.** Assert that `response_offer` and the surfacing tables appear in neither `Changes`, `applyDelta`, nor the outbox. A grep-style structural test is legitimate here.
2. **The server reads no content.** `responses.ts` and the `write-guard` gate must not reference `label`, `sublabel`, `note`, `title`, or `body_md` in any conditional.
3. **Personal-default.** No code path reaches `AudienceScope.FAMILY` without an explicit user choice — in particular the swipe path, which must never mint a family-wide rule.
4. **No engagement affordance.** No positive-signal / "more like this" / "see fewer" string anywhere in the UI module (Task 11 pins this).

**Manual verification (operator-driven — I build and install, the operator drives the taps):** on a physical device, mute a weather card personally, confirm it disappears from your Now and stays in the other member's; mark a task done and confirm it leaves both members' Now on next sync with the byline intact; go airplane-mode, mute, confirm the local apply + the "takes effect next run" copy, then undo and confirm the queued write is gone.

---

## Self-Review

**Spec coverage.** Every section of `NOTES.md` and every design view maps to a task: verb ladder → Tasks 4/8/11; persistence contract Tier 0/1/2 → Tasks 0/2/4/13 (Tier 2 explicitly deferred); scope me-vs-family → Tasks 5/6/12; entry points A/B/C → Task 13; placement matrix → Task 11's `verbRowsFor`; Done visibility/byline/notes → Tasks 5/10/14/15; receipts → Tasks 10/15; the six operator-decided questions → Task 0's ADR body. GAP 1→Task 12, GAP 2→Tasks 13/14, GAP 3→Task 14, GAP 4→Task 13, GAP 5→Task 15, GAP 6→Tasks 10/15.

**One gap I am naming rather than papering over.** The run-receipt line "saw 1 marked done" (GAP 5's third point) depends on ADR 0062's durable run records, which are Proposed and unbuilt. Task 15 renders that row only when a receipt exists and Task 18 leaves it to the routine work; **nothing in this plan fabricates a count**. If the operator wants that line live at ship, ADR 0062 has to land first — that is a separate plan, not a step I can hide inside this one.

**Type consistency.** `subject_ref`/`subjectRef`, `match_scope`/`matchScope`, `audience_scope`/`audienceScope` are the wire↔Kotlin pairs throughout. `matchesRule` (TS) and `ResponseRules.matches` (Kotlin) take the same three inputs in the same order and are asserted against identical literals. `ContentResponse` carries the same eleven fields in both `ResponseModel.kt` and `ContentResponseRow`.

**Risks a reviewer should push on:**
1. **`subject_ref` stability is load-bearing.** If extraction mints a different key for the same source email across runs, Done silently stops working and the card returns. Task 0's ADR must say so; the curator skill's stable-key requirement (Task 18) is the only enforcement, and it is a convention, not a constraint.
2. **Personal mutes partially succeed.** One write can be accepted for four members and stripped for one. The 200 response tells the author nothing about the strip. Consider returning the stripped count in the response body if the operator wants author-side visibility.
3. **`content_responses` is a new tombstone-retention surface** on the ADR 0040 cursor — covered by Task 7 Step 7. Worth a reviewer's eye anyway: if the retention floor is ever shortened, a device offline longer than the floor resurrects removed rules rather than dropping them, and the full-resync directive is the only thing standing between that and a rule the user thought they deleted.

---

**Plan complete and saved to `docs/superpowers/plans/2026-08-08-smart-content-responses.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?** — and note that Task 0 (the ADR) gates everything, so either way the first stop is operator acceptance.
