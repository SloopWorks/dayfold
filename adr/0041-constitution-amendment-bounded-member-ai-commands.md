# ADR 0041: Constitution Amendment — Bounded Member-Authored AI Commands

## Status

**Accepted** 2026-06-29 (operator explicitly accepted the constitution amendment —
HARD GUARDRAIL tier — in-session: "Accept — W3 builds (flagged)". The amended
constitution text below has been applied to `context/business-constitution.md`. W3
ships **EXPERIMENTAL / flagged / testable, not committed**; promotion to shipped is a
separate operator decision per the Revisit Trigger). Was **Proposed** 2026-06-28
(operator-directed in-session: "use free form composer —
this will essentially work as a remote command for the backing AI"; **operator-gated
— HARD GUARDRAIL: scope + business-constitution change**, the highest review tier).
Amends `context/business-constitution.md` ("What it is not" → "Not an open-ended AI
chatbot"). Pairs with **ADR 0042** (the technical activation of the intents channel
for W3) — 0041 moves the *values* line, 0042 builds *within* it. Composes ADR 0016
(reserved the free-text gate behind "a future ADR + a constitution amendment" — this
is that amendment), ADR 0039 (Channel B / intents), ADR 0015/0017 (E2EE), the W3
design in `specs/two-way-engine-and-content-management-design.md` §4-W3.

**This ADR does not flip until the operator accepts it.** Until then the
constitution stands unchanged and W3 free-form capture cannot ship. The operator has
flagged this capability as **experimental / testable, product direction TBD, a
possible overstep** — see the EXPERIMENTAL tier in §Decision.

## Context

The constitution's scope firewall says (lines 35–39):

> **Not an open-ended AI chatbot or general assistant.** Recommendations come from a
> bounded, reviewable template catalog ("render, don't reason" — reasoning may run in
> external loops, but the product's surface is curated cards, not a free-text oracle).
> This caps cost, hallucination, and privacy exposure.

ADR 0016 §4 reserved **free-text conversational prompts** behind exactly "a future
ADR + a constitution amendment." The W3 "add context" feature, per the operator's
direction, is a **free-form composer**: a member types natural language and a
key-holding AI loop acts on it on a later run, authoring a result card. Example the
operator gave:

> "Research butler university dorm rooms and find all available info to create a rich
> card. Include items supplied by the school and dimensions."

Literally, a free-text box that drives AI work **is** the reserved surface. It cannot
land under the current constitution line. The question this ADR settles: *can the
"not a chatbot" line be narrowed to permit a **bounded, async, no-reply command
surface** without dissolving the firewall it exists to hold?*

## Decision

**Narrow — do not delete — the "not an open-ended AI chatbot" line.** A member may
submit free-form natural-language text *as a bounded command to the key-holding AI
loop*, subject to all five fences below. Everything the original line was protecting
(cost, hallucination, privacy, the calm/non-chat posture) is preserved by the fences,
not by banning free text.

**The five fences (all required; removing any re-opens the chatbot drift):**

1. **No conversation, ever.** The surface produces a **filed card**, never a reply.
   No thread, no turn-by-turn exchange, no "the AI said back to you." A submission is
   a one-shot command; the only output is content that appears in a hub later.
2. **Asynchronous, not real-time.** "Drop it now, it settles later" (the ADR 0016
   pull-loop). No live-typing oracle. This is what keeps it calm and non-addictive.
3. **Bounded to the submitter's entitlements** (ADR 0042 enforces it). The loop may
   author/file/update **only within what the submitter can already see and write**;
   **no destructive or audience-widening side-effects** from a member command. The
   member commands *their own reach*, never the family's.
4. **Key-holder reasoning only** (ADR 0016 §3 / 0015). The loop decrypts and reasons
   in a key-holding context (operator machine → controlled host); never a hosted
   server-side LLM. The honest claim "Processed by Claude on your device" stays true.
5. **Server still renders, never reasons.** The product *surface* is still curated
   cards with visible provenance ("Added by Claude · from your note"). The server
   relays opaque intents; it does not become a free-text oracle. "Render, don't
   reason" holds at the server boundary unchanged.

**What does NOT change (the firewall still stands):** no family chat/messaging; no
real-time assistant UI; no reply oracle; no member command that escalates privilege
or deletes/widens family content; no server-side reasoning; no third-party-LLM
routing of family content without disclosure (guardrail #3). The product is still
"calm cards, authored elsewhere, rendered honestly" — the *author* may now be a
member-issued command, but the *shape* is unchanged.

**EXPERIMENTAL tier (operator-flagged).** This capability ships **gated and
testable, not committed to the product**. Concretely: the W3 surface is built behind
a flag for dogfood testing, badged EXPERIMENTAL in the UI (ADR 0008 mockup:
`designs/two-way/Add-Context`), and **its promotion to a shipped feature is a
separate operator decision** once the bounded-command model is proven to read as a
tool (not a chatbot) and the cost/safety envelope is observed. Product direction is
explicitly TBD; this ADR authorizes *testing within the fences*, not a commitment to
ship.

### Proposed amended constitution text (applied on acceptance)

Replace the "Not an open-ended AI chatbot" bullet (lines 35–39) with:

> **Not an open-ended AI chatbot or general assistant.** The product's surface is
> curated cards with visible provenance, never a conversational oracle. Members may
> issue **bounded, asynchronous, no-reply commands** to a key-holding AI loop that
> authors a result card later (e.g. "research and make a card about X") — but there
> is **no chat, no reply thread, no real-time assistant**, a command acts **only
> within the submitter's own visibility and never destructively**, and reasoning runs
> **only in a key-holder** (never a hosted server-side LLM). This keeps cost,
> hallucination, and privacy exposure capped while allowing member-authored
> intelligence. A *conversational* surface (replies, threads, real-time) remains
> out of scope and would need its own superseding ADR.

## Rationale

- The line exists to cap **cost, hallucination, privacy, and attention-drift**. Each
  is held by a *fence*, not by the free-text ban itself: async + no-reply caps
  attention; bounded-to-submitter + key-holder caps privacy/blast-radius; the cost
  constants (ADR 0042) cap spend; visible provenance + "render don't reason" caps the
  oracle posture. So the ban on free *text* was a proxy for those; naming the real
  fences lets the capability exist without the drift.
- **No-reply is the load-bearing distinction** between "a command surface" and "a
  chatbot." A system that never talks back cannot become a conversational assistant,
  however free its input box is.
- **Bounded-to-submitter** dissolves the confused-deputy risk of "member text → a
  loop that can act": the loop's authority is the *member's* authority, so a command
  can never do what the member couldn't do by hand.
- **Experimental/gated** honors the operator's "possible overstep, TBD" — the
  amendment authorizes *learning* (the project's primary purpose) without betting the
  product identity on it.

**Rejected:** (a) delete the chatbot line outright — discards the firewall, invites
real-time-assistant drift. (b) keep free-text fully reserved, ship only
structured/template capture — contradicts the operator's direction and forecloses the
research-command use case the dogfood wants to test. (c) allow replies/threads "since
we're already doing free text" — that *is* the chatbot; the no-reply fence is
non-negotiable. (d) hosted-LLM reasoning to avoid the key-holder constraint — breaks
E2EE + guardrail #3.

## Consequences

**Positive:** unblocks the W3 dogfood experiment (member-issued research/authoring
commands) without dissolving the scope firewall; makes the *real* cost/privacy fences
explicit and reviewable; keeps "calm, no chat, render-don't-reason" intact; preserves
the option to ship or kill based on observed behavior.

**Negative / cost:** the constitution's simplest, brightest line ("not a free-text
oracle") becomes a *fenced* line that must be defended fence-by-fence in review — a
heavier ongoing discipline. A future reviewer could mistake "bounded command" for
"chatbot OK" and let replies/threads creep in (guard explicitly: the no-reply +
no-real-time fences are the bright line now). The capability is a genuine new surface
with a security envelope (ADR 0042) to maintain.

## Revisit Trigger

The operator decides to **promote W3 from experimental to shipped** (record the
product-direction decision); OR a *conversational* surface (replies/threads/real-time)
is wanted (a new superseding ADR — this amendment does **not** authorize it); OR the
observed cost/hallucination/privacy envelope in dogfood breaches the caps (re-tighten
or kill); OR a hosted (non-key-holder) loop is wanted (re-examine E2EE, ADR 0017).
