# ADR 0042: Activate the Intents Channel for W3 — Bounded Member Commands to the Key-Holder Loop

## Status

**Accepted** 2026-06-29 (operator accepted ADR 0041 → its blocker is cleared; cost
constants accepted — Sonnet default, batch-per-family-per-cadence, per-family daily
cap, ~$2–6/mo/family dogfood spend. W3 builds **EXPERIMENTAL / flagged**; client
surface gated on the `designs/two-way/Add-Context` mockup; promotion to shipped is a
separate operator decision). Was **Proposed** 2026-06-28 (operator-directed: W3 =
free-form composer, bounded to the
submitter, key-holder-only; **operator-gated** — automation-autonomy boundary +
customer-data write path + E2EE posture + spend). **Fires ADR 0016's revisit
trigger** ("free-text conversational prompts are wanted → constitution amendment +
ADR") and **builds within ADR 0041** (the constitution amendment). This ADR was
**blocked on ADR 0041 acceptance** — if 0041 had been rejected, W3 would stay
structured/template-bounded (ADR 0016 §4) and this ADR would not apply. Composes
ADR 0039 (Channel B / the typed-op spine), ADR 0015/0017 (E2EE / key-holder),
ADR 0029 (scopes), ADR 0030 (visibility), ADR 0025 (abuse/cost limits), ADR 0036
(member-URL allowlist), ADR 0018 (Vercel/Neon). Design + audit trail:
`specs/two-way-engine-and-content-management-design.md` §4-W3 / §3.

## Context

ADR 0016 reserved the **`intents`** channel by name only. ADR 0039 defined its
**structural** place (Channel B: the server enqueues an opaque intent; the
key-holding loop pulls, reasons, authors a result; the family pulls it via `/sync`)
but explicitly **did not build it** and left the free-text-vs-structured + placement
calls to INB-26. The operator has now decided: W3 is a **free-form composer** that
works as a **bounded remote command to the backing AI loop** (ADR 0041 narrows the
constitution to permit it). This ADR concretizes and gates that channel.

The decisive new fact vs. earlier drafts: a free-form command can ask the loop to
**fetch external information** (the operator's example: "research butler university
dorm rooms… create a rich card"). That adds an **outbound-fetch / SSRF** surface the
toggle/delta features never had, and a **per-command cost** that scales with the work
requested — both must be fenced here.

## Decision

Activate the intents channel as a **bounded, key-holder, experimental** surface.

1. **Concrete `intents` table + endpoints** (per ADR 0039 §3 / design §3):
   `intents(family_id, id=op_id, created_by, kind, target_hub_id?, payload[E2E],
   status ∈ {pending,processing,done,failed,cancelled}, result_ref, error, version,
   timestamps)`; member submits via the `/mutations` spine (`type:"submitContext"`,
   `channel:"intent"`); the loop claims pending rows with `SELECT … FOR UPDATE SKIP
   LOCKED` (`intents:resolve`), authors result content via the normal write path, and
   stamps `result_ref` + `source_context_id` on the derived card so the client
   retires the "organizing…" placeholder. A **TTL/give-up** prevents ghost
   placeholders. The intent + view-state streams fold into the **existing `/sync`
   envelope** (one cursor).

2. **Bounded-to-submitter command authority (the safety core — ADR 0041 fence 3).**
   The loop treats submitted text as an **intended instruction, executed strictly
   within the submitter's entitlements**: it may author/update content **only within
   the submitter's own visibility** (`audience ⊆ submitter visibility`), and takes
   **no destructive and no audience-widening side-effects** from a member command (no
   delete, no share-broadening, no privilege escalation). A command can never do what
   the member could not do by hand. This is **server- and loop-enforced**, not loop
   discipline.

3. **Key-holder-only placement** (ADR 0016 §3 / 0015). The loop decrypts and reasons
   in a key-holder: **K1 operator machine** (M0/dogfood default — free, must be
   online) → **K3 dedicated controlled host** (M1-correct for a real second family).
   **K4 hosted relaxed-E2EE is reserved**, ADR-gated, disclosure-bearing (guardrail
   #3), never the default. The honesty chip "Processed by Claude on your device" is
   bound to K1/K3; it must change if K4 is ever used.

4. **Confused-deputy + external-fetch boundary.** Member text is data the loop *acts
   on*, but: (a) the loop's authority is the submitter's (per #2); (b) any
   **member-supplied or research-discovered URL is fetched by the loop, SSRF-isolated**
   (never the device; allowlist/parser reuse per ADR 0036, kept in lock-step across
   server/client/CLI); (c) **no destructive loop side-effects** from a member intent at
   this tier; (d) external research output is still authored as a normal provenance-
   stamped card, subject to the same visibility bound.

5. **Cost constants (spend-class — operator-gated).** **Sonnet default** (Opus only
   for genuinely hard reasoning); **batch all pending intents per family per cadence**
   (amortizes pulled-context tokens — the biggest saver); **per-family daily
   submission cap**; **down-scale images to the thumbnail before any vision call**.
   A free-form *research* command costs more than "file this note" — so a
   **per-command work ceiling** (bounded research depth / token budget per intent)
   is part of the cap. ~$2–6/mo/family at dogfood; un-batched Opus-by-default +
   unbounded research is the cap-buster. (Extends ADR 0025 — the first LLM-cost
   limits; 429-retryable so an offline flush re-enqueues.)

6. **Scopes** (extend ADR 0029): activate **`intents:write`** (member app) /
   **`intents:resolve`** (loop). Member app = `content:read+write+intents:write` (no
   `content:delete`); loop = `content:read+write+delete+intents:resolve`. No separate
   `media:write` (media rides the content payload). Provenance: server-attested
   `created_by` (cleartext) on the intent; the result card carries client-display
   "Added by Claude · from your note" (or "from the family" when sources span members).

7. **EXPERIMENTAL tier (ADR 0041).** W3 ships **behind a flag, badged EXPERIMENTAL,
   for dogfood testing — not committed to the product.** Client surface gated on the
   ADR 0008 mockup (`designs/two-way/Add-Context`, EXPERIMENTAL status). Promotion to
   a shipped feature is a **separate operator decision** after the bounded-command
   model is observed to read as a tool and stay inside the cost/safety envelope.

## Rationale

- **The architecture already supported this** (ADR 0039 Channel B) — only the
  governance gate (0041) and the safety/cost fences moved. No dataflow change: the
  server still enqueues opaque, the key-holder still reasons, the family still pulls a
  card. Free-form vs structured is purely an *input-shape* + *bounding* decision, not
  an architectural one.
- **Bounded-to-submitter is what makes "member command to an acting AI" safe** — it
  collapses the confused-deputy problem to "the member's own reach," so the worst a
  malicious/garbled command does is rearrange the submitter's own visible content.
- **Key-holder-only is the only E2EE-coherent placement** — a hosted loop cannot
  decrypt without breaking the zero-knowledge thesis + guardrail #3.
- **External fetch is the genuinely new risk** the research-command use case
  introduces; isolating it in the loop (not the device) + allowlist reuse contains it.
- **Experimental/flagged** matches the operator's "test, possibly not ship, TBD" — the
  project's primary purpose is learning; this lets W3 be *learned from* before it is
  *bet on*.

**Rejected:** structured-only capture (operator chose free-form — ADR 0041);
unbounded command authority (drops the confused-deputy guard — fence #2 of 0041);
hosted-LLM loop (breaks E2EE); device-side fetch of member/research URLs (SSRF on the
member's device + leaks intent); Opus-by-default + unbatched + unbounded research
(cap-buster); shipping W3 un-flagged as a committed feature (the operator flagged it a
possible overstep — gate it).

## Consequences

**Positive:** Dayfold's first AI-mediated member surface, on the existing Channel B,
with an explicit and enforced safety/cost envelope; a testable research/authoring
command for dogfood; the E2EE + dumb-server theses intact; promotion or kill is an
informed later decision.

**Negative / cost:** a real new subsystem — the `intents` table + endpoints, the
loop's pull/reason/author path, the bounded-authority enforcement, the SSRF-isolated
fetch, the cost-batching + per-command ceiling, and the EXPERIMENTAL flag/UX. W3 is a
genuine multi-week, M1-shaped build with the largest safety surface of the W1–W5 set.
It is **blocked on ADR 0041** and on the ADR 0008 Add-Context mockup, and it carries
recurring LLM spend that scales with families (the one W-feature that does).

## Revisit Trigger

ADR 0041 is rejected (W3 reverts to structured-bounded, ADR 0016 §4); OR the operator
promotes W3 to shipped (record it); OR observed per-family LLM spend breaches the cap
(re-tighten batch/depth/model); OR a hosted loop (K4) is wanted (E2EE re-examination +
guardrail-#3 disclosure ADR); OR member-supplied/research URL fetching surfaces an
SSRF/abuse case (harden the loop allowlist).
