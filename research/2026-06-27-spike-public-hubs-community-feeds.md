# Spike — Public Hubs (C): Subscribable Community Feeds

**Date:** 2026-06-27 · **Status:** Research spike (investigation + recommendation; any adoption is ADR-gated)
**Author:** agent fleet (5 domain-blind agents + adversarial pass) · **Feeds:** OQ-public-hubs
**Question:** Could Dayfold let families **subscribe to public/community-authored "hubs"** —
school calendars, sports-league schedules, local kids' events — that render alongside
their own family content? Is it worth pursuing for a learning-lab-first, solo+AI project?

Labeling: `[fact:source]` / `[estimate]` / `[assumption]`. Claim verdicts:
confirmed / partially-confirmed / refuted / unverifiable. Raw per-agent outputs:
`research/2026-06-27-public-hubs-community-feeds-agent-outputs/`.

---

## 0. Verdict (read this first)

Framing C is best understood as a **three-rung ladder** (the strategy domain's
Crawl/Walk/Run), with different verdicts per rung — not a clean binary:

1. **Run — full community-feeds product** (Dayfold sources, aggregates, moderates, and
   redistributes public/community local-events content; or hosts community-authored
   hubs others subscribe to): **NO-GO.** It is a *different business* (content
   aggregation + moderation), commoditized at every layer, carries a permanent
   non-delegable human ops/moderation/legal burden, and pulls the project off its
   one defensible asset. This is the "Burbio drift" the spike worried about. The
   NO-GO is over-determined — it holds on commoditization + ops burden + off-thesis
   *independent of* the Burbio precedent (which is one strong but over-determined
   cautionary data point, not proof of absent demand — see §1).

2. **Walk — curated regional feed packs** (Dayfold maintains a small set of vetted
   public ICS feeds families pick from, removing the discovery step): **DEFER, with
   an explicit trigger.** This is the one genuine *white space* the fan-out found
   ("nobody removes the discovery step") — but it re-introduces a curation/freshness/
   redistribution burden and a redistribution legal posture that the thin slice
   avoids. Revisit only **if** the thin slice (rung 3) ships, converts, AND
   discovery-friction is measured as the actual drop-off point. Not now.

3. **Crawl — thin "bring-your-own-feed" slice** (a family subscribes a single public
   ICS/webcal URL for *their own* school/team; Dayfold normalizes + renders it into
   that family's Event Hub + briefing): **CONDITIONAL — spec-only now; build later as
   an acquisition *experiment*, sequenced AFTER core ships, ADR-gated, with the legal
   pre-gate cleared and a defined success bar first.** It deepens the validated
   multi-member family-tenant wedge at near-zero *freshness/ops* burden (the family
   supplies the feed) — **but not near-zero legal burden** (two `[needs-counsel]`
   gates + a minors'-data regime; §3). It adds **no new differentiation** of its own
   (the differentiator is the core wedge's multi-member rendering, not the feed), and
   it does **not** move OQ-wtp: native subscribe is free and a paid niche competitor
   (Caldzy) already does exactly this slice. A **NO-GO-for-now** reading is equally
   defensible — nothing about the acquisition hypothesis can be *learned* until core
   exists to convert strangers into. The honest recommendation is therefore "record
   as a candidate experiment; do not build ahead of core."

The four "Public Hubs" framings (A shareable event hubs, B template gallery,
C community feeds, D define-the-concept) are recorded in `backlog/later.md` +
`OQ-public-hubs`. This spike targets **C** and finds: the ambitious rung is a no-go,
the curated-pack rung is a deferred white-space bet, and the only near-term-viable
rung (thin BYO-feed) is a wedge *input* worth spec'ing but not building ahead of core.

---

## 1. Why the full vision is a NO-GO

**It is commoditized from below, beside, and above** [fact: multiple, below]:
- **Below — the plumbing is free + universal.** Apple/Google/Outlook natively
  subscribe to webcal/ICS; TeamSnap, SportsEngine, GameChanger, LeagueApps, Spond
  all expose free ICS; school districts publish free ICS feeds. There is **zero
  willingness-to-pay for the act of subscribing to a feed.** *confirmed*
  [fact: teamsnap.com/teams/features/calendar-syncing; fcps.edu/subscribe;
  support.google.com/calendar/answer/37100].
- **Beside — the "render it nicely for the whole family" niche is already a paid
  product.** Caldzy does exactly the thin slice (paste a TeamSnap/SportsEngine/
  GameChanger feed → shared family iCloud view → auto-reminders). *confirmed*
  [fact: caldzy.com]. This is the validation weakness made concrete: incumbents are
  free, and the one paid niche already exists.
- **Above — the aggregator layer is owned.** Burbio (now **B2B** K-12 sales/
  procurement intelligence) and Macaroni KID / Mommy Poppins (consumer local-events,
  staffed by paid local publishers). *confirmed* [fact: about.burbio.com;
  national.macaronikid.com].

**The Burbio precedent is a strong cautionary data point — but read it carefully.**
Burbio is the only company that built "subscribe to your community's calendars" at
scale — **200k+ school/gov/library/nonprofit calendars across 50 states** — and then
**moved its consumer product aside to become a B2B EdTech sales-intelligence tool.**
*confirmed* [fact: about.burbio.com; crunchbase.com/organization/burbio-com]. The
tempting read — "families don't want this" — is **over-determined and should not be
treated as proof**: the consumer product + pivot window overlap COVID (when local
events were largely cancelled), B2B procurement-intelligence ARPU dwarfs consumer
subscription ARPU regardless of consumer demand, it is n=1 founder path-dependence,
and "couldn't *monetize* families" ≠ "families don't *want* it" (a pricing finding,
not a demand finding). The honest verdict: **a strong demand-side caution, not a
green light** — and the full-vision NO-GO does not depend on it (it stands on
commoditization + ops burden + off-thesis alone). The genuine white space the
fan-out found — *community feeds inside a briefing*, and a *curated discovery layer*
(rung 2) — is an **absence of a feature, not evidence of demand**; nobody has proven
families will pay, and the same caution applies. (Native AI-briefing threats:
Gemini does not ingest community feeds today [fact: gemini.google/overview/daily-brief];
**Alexa+ community-feed ingestion is unverifiable** and is a standing commoditization
risk per the kill-switches, not a cleared field.)

**Freshness + moderation is an ops cost, not an engineering trick.** Burbio sources
its dataset with **~15 full-time humans, each owning a set of districts on a re-check
cadence reported at ~72 hours during the COVID tracking period** — not automated
scraping. *confirmed* [fact: kappanonline.org "The story behind Burbio"]. The moat is
labor ("freshness scales with headcount, not servers"). For a learning lab whose
steady-state target is near-zero human ops, an aggregation/moderation operation is a
**permanent, non-delegable, part-time job** — AI agents can *assist* moderation but
cannot *own* liability or judgment calls. [estimate, high confidence]

**The high-value content can't even be aggregated.** The granular schedules families
actually want (this 3rd-grade class's field trip; this U10 team's rescheduled game)
live in **account-scoped, auth-gated** per-user feeds (Schoology/PowerSchool personal
iCal; TeamSnap/SportsEngine per-team). These **cannot be aggregated into a public
hub** — they're scoped to the account holder, so they can only ever be onboarded by
the household connecting its *own* feed. *confirmed* [fact:
uc.powerschool-docs.com; helpme.teamsnap.com]. What *is* publicly aggregatable
(district academic calendars, library/city events) is the low-resolution,
commoditized part.

**The high-value content also can't be aggregated (the sharpest finding).** The
granular schedules families actually want (this 3rd-grade class's field trip; this
U10 team's rescheduled game) live in **account-scoped, auth-gated** per-user feeds
(Schoology/PowerSchool personal iCal; TeamSnap/SportsEngine per-team) that **cannot
be aggregated into a public hub** — they're scoped to the account holder. *confirmed*
[fact: uc.powerschool-docs.com; helpme.teamsnap.com]. So even setting demand aside,
aggregation structurally collapses to per-household onboarding (rung 3); what is
publicly aggregatable is the low-resolution, commoditized district/library/city layer.

**Closest competitor to watch — Jam.** Not a feed product, but the structurally
nearest thing to Dayfold's *actual* model: **email-forward → LLM extract events →
smart reminders** ("buy shin guards before soccer"), live on iOS/Android. *confirmed*
[fact: jamfamilycalendar.com/how-it-works]. It validates the content-API/forward +
smart-actions direction and is the competitor to track regardless of the feed verdict.

---

## 2. Why the thin BYO-feed slice is at most a CONDITIONAL (spec-only) bet

**It deepens the validated wedge instead of changing the game.** The validation
verdict's one defensible finding is a **multi-member family-tenant briefing** (no
native OS ships it). A family subscribing its *own* school + team ICS feeds, rendered
into the shared Event Hub, is simply **more high-signal input to the same family
briefing** — still rendering *this family's* signals, still not a system of record,
still not social. It sits cleanly inside the constitution. [estimate, high confidence]

**The *freshness/ops* burden is near-zero — but the *legal/privacy* burden is not.**
The family solves the cold-start/discovery problem by pointing Dayfold at its own
public ICS URL: no catalog to maintain, no content to moderate, no redistribution.
Freshness is the *source's* problem; failures degrade one family's hub and visibly
point upstream; ICS parsing is a bounded, agent-maintainable task. [estimate, high
confidence] **However, "near-zero" stops at the engineering axis.** The same feed
ingests roster/league data naming *other families' minors* and their recurring
locations, which triggers a real **one-time legal/privacy pre-gate** — two
`[needs-counsel]` reviews (per-source ToS; multi-state minors'-privacy) + a
minors'-data-minimization regime + a third-party-subject deletion path (a teammate
named in a team ICS has no account; §3). That is a non-trivial design cost brushing
Guardrail 1, and it must be cleared in the ADR *before* build. (Engineering caveat
too: ICS is genuinely laggy — 12–24h refresh, recurrence-parse slowness, source-side
throttling — and needs a *tolerant* parser; sourced to vendor write-ups
[partially-confirmed: calendarbridge.com; spreadevent.com/blog/ics-file-complete-guide].)

**It is the best acquisition *angle* in the spike — but that is faint praise.**
*"Subscribe your kid's school + soccer team and the whole family sees it"* is a
concrete, demoable hook in a story Gemini/native don't tell. But the feature adds
**no differentiation of its own**: what makes it compelling is the core wedge's
multi-member rendering, not the feed. And a paid competitor (Caldzy) already does
exactly this slice [fact: caldzy.com] without breaking out — so "best in the spike"
means best of a weak field.

**It is a candidate learning probe, not a revenue thesis — and probably not buildable
*now*.** No WTP is proven; native subscribe is free; Caldzy monetizes this exact
slice. The justification for building it is to **test an acquisition hypothesis**
("does 'subscribe your school + team' convert a stranger family to sign-up?"). But
that hypothesis is **untestable until core ships** — there is nothing to convert
strangers *into* yet — and the slice is itself "sequenced behind core." So building
ahead of core teaches nothing. **A NO-GO-for-now reading is equally defensible.** The
honest recommendation: **record it as a candidate post-core experiment with a defined
success bar** (e.g. a pre-registered stranger-conversion rate / sample / cost ceiling,
set when core exists), and **do not build it ahead of core.** It does not move OQ-wtp
on its own either way.

---

## 3. What the thin slice must get right (build constraints, if/when it's specced)

These are the non-negotiables surfaced by the trust + legal + sourcing domains. They
are **design/architecture constraints for a future ADR + spec**, not a build order.

1. **Stable event identity + change-detection is first-class.** The canonical
   aggregation failure is **UID-instability → duplicate events + missed updates**
   (Outlook/Google feeds rotate UIDs on re-import). *confirmed* [fact:
   theeventscalendar.com/knowledgebase/troubleshooting-duplicate-imports]. Design a
   stable per-event identity + dedupe/diff from day one.
2. **Tolerant ICS parsing.** Timezones (missing VTIMEZONE → wrong times/double-books)
   are the #1 bug source; malformed RRULEs silently drop/misplace recurring events;
   strict CRLF/75-octet-folding/escaping cause silent import failures. *confirmed*
   [fact: spreadevent.com; fullcalendar #6106]. Use a battle-tested parser +
   per-feed normalization + a validator — not naive parsing.
3. **Honest freshness UX, not "verify with the school."** Every comparable product
   closes the trust gap with a "verify with the source" disclaimer — the *opposite*
   of Dayfold's calm "I handle this" promise. *confirmed* [fact:
   publicschoolscalendar.com/disclaimer]. The honest version for a trust-first
   product: **"last synced 2h ago — tap source"** + graceful degradation, never an
   assertion the app can't stand behind.
4. **Deep-link every item to its authoritative source (mirror, not record).**
   Required to stay inside **Guardrail 4** ("never the family's system of record")
   and for copyright/attribution hygiene. UI frames feeds as a pointer to the source.
5. **Ingest facts, re-render in Dayfold's own format.** Facts (date/time/place) are
   not copyrightable [fact: *Feist v. Rural Telephone*, 499 U.S. 340 — supreme.
   justia.com/cases/federal/us/499/340]; a source's *selection/arrangement*, prose,
   images, and logos can be. Do **not** mirror descriptive copy/images/logos verbatim.
6. **Subscribe to publicly-offered feeds only; don't accept restrictive ToS.**
   CFAA exposure for public, no-login data is low (9th Cir. *hiQ v. LinkedIn*) — but
   the risk has moved to **contract/tort** (hiQ ultimately settled with stipulated
   trespass-to-chattels + misappropriation liability), and accepting a feed's ToS /
   creating an account / using an API key can bind anti-redistribution clauses.
   *confirmed / partially-confirmed* [fact: hiQ 9th Cir. opinion; Meta v. Bright Data
   N.D. Cal. 2024]. Honor robots.txt + rate limits. `[needs-counsel]` per source +
   on multi-state jurisdiction.
7. **Minimize minor-identifying data + provide a third-party-subject deletion path.**
   Even with adults-only accounts, ingesting roster/league feeds means storing
   identifiable minors' names + **recurring precise locations** — a child-safety-
   sensitive profile that the expanding **state AADC / minors'-privacy patchwork**
   (under-18, design-focused) scrutinizes, *broader than COPPA's "from a child"
   gate.* *confirmed* [fact: insideprivacy.com 2025 minors'-privacy roundup]. Note
   this leaks into the *thin* slice too: a kid's team ICS names teammates who have no
   account and today no delete pathway (a real obligation gap [fact: copyright.gov/512
   context; legal domain §4]). Avoid storing minors' surnames + precise recurring
   locations; treat minor fields as sensitive; data-minimize + retention-limit; and
   stand up a third-party-subject deletion path. `[needs-counsel]`

**The Section 230 wrinkle (a speculative-but-ADR-class theory, sharpest if the slice
ever re-authors feed content via LLM):** Pure aggregators lean on §230 for
third-party content. But Dayfold's differentiator is **re-authoring** content into
AI-generated cards ("party Saturday — pack jackets"). The 2024–25 *direction* of
commentary is that §230 protects *what users say*, not *what the platform generates*.
**This is an untested theory, not an established exposure** — there is **no
calendar-specific case law**, and the driving harm scenario (a card surfacing a wrong/
cancelled time as live) has **no documented post-mortem**. *partially-confirmed (by
analogy only)* [fact: ABA Business Law Today Nov-2024; congress.gov CRS LSB11097;
trust domain §5 negatives]. Treat as **speculative risk for counsel to opine on**,
not as fact. **Mitigation that also de-risks it:** for the thin slice, prefer
**faithful rendering of the family's own subscribed feed** over LLM re-authoring of
third-party event facts; if cards ARE LLM-authored from feed content, that routes
*children's* PI (third parties who never consented) through a third-party LLM →
**intensifies Guardrail 3's disclosure duty** and is ADR-gated.

---

## 4. Corrections / reinforcements to prior documents

- **Reinforces** `research/validation-round1-2026-06.md`: the AI-briefing concept is
  commoditized and there is no proven family WTP. This spike adds two concrete
  data points the validation round lacked — **Caldzy** (a live paid product doing the
  thin slice) and the **Burbio consumer→B2B pivot** (a direct demand-side
  counter-signal for the aggregation business).
- **No corrections** to existing accepted ADRs. The hub model (ADR 0006) and per-hub
  visibility (ADR 0030) are tenant-internal; nothing here changes them. Any public/
  cross-tenant hub is a *new* ADR, not an edit.

---

## 5. Promotion — what this spike changes

- **OQ-public-hubs** (already recorded) gains its resolution pointer: framing C is a
  three-rung ladder — Run (full vision) = NO-GO; Walk (curated regional packs) =
  DEFER-with-trigger; Crawl (thin BYO-feed) = spec-only candidate experiment, not
  built ahead of core. (Updated in `context/open-questions.md`.)
- **New open question — OQ-byo-feed-acquisition:** Does "subscribe your school +
  team, whole family sees it" convert a *non-operator* family to sign-up? This is the
  acquisition hypothesis the thin slice would test — but it is **untestable until
  core ships**, and needs a **pre-registered success bar** (stranger-conversion rate /
  sample / cost ceiling) before it justifies build. Feeds OQ-wtp / OQ-niche / Gate
  G1b. (Added to `context/open-questions.md`.)
- **No ADR authored here.** Adopting even the thin slice is **ADR-class** (touches
  scope, customer-data handling incl. minors' data + LLM routing, and maintenance/
  liability burden) and carries a **legal pre-gate** (two `[needs-counsel]` reviews +
  a minors'-data regime) that must clear *before* build. Per CLAUDE.md this requires a
  Proposed ADR + operator sign-off, and a hi-fi mockup before deep planning (ADR
  0008). This spike is the evidence input to that future ADR, not the decision.
- **Parked, not pursued:** the full community-feeds vision (rung 3), the curated-pack
  rung (deferred), and framings A (shareable event hubs) + B (template gallery) —
  recorded in `backlog/later.md`.

---

## 6. Sources (consolidated; full per-domain lists in the agent-outputs archive)

**Competitive / commoditization:** about.burbio.com · crunchbase.com/organization/
burbio-com · caldzy.com · cozi.com/how-to-add-an-ical-feed-to-cozi · myskylight.com/
lp/calendar-syncing · jamfamilycalendar.com/how-it-works · gemini.google/overview/
daily-brief · national.macaronikid.com
**Sourcing / feasibility:** teamsnap.com/teams/features/calendar-syncing · fcps.edu/
subscribe · finalsite.com/school-websites/cms-for-schools/calendars · dev.sportsengine.com ·
getdugout.com/gamechanger-api (no public API) · eventbrite search-API deprecation
(github.com/Automattic/eventbrite-api/issues/83) · kappanonline.org "The story behind
Burbio" (manual ops, ~15 staff, 72h cadence) · calendarbridge.com (ICS lag) ·
spreadevent.com/blog/ics-file-complete-guide
**Trust / moderation:** theeventscalendar.com/knowledgebase/troubleshooting-duplicate-
imports · publicschoolscalendar.com/disclaimer · helpnetsecurity.com/2024/10/29/
eventbrite-phishing · about.fb.com/news/2025/04/cracking-down-spammy-content-facebook ·
blog.nextdoor.com/2021/02/10/how-moderation-works-on-nextdoor · clutch.co/content-
moderation/pricing
**Legal / privacy:** hiQ 9th Cir. opinion (cdn.ca9.uscourts.gov/datastore/opinions/
2022/04/18/17-16783.pdf) · Meta v. Bright Data (newmedialaw.proskauer.com 2024-01-24) ·
Feist v. Rural Telephone (supreme.justia.com/cases/federal/us/499/340) · COPPA 16 CFR
§312.2 (law.cornell.edu/cfr/text/16/312.2) · FTC COPPA FAQ · insideprivacy.com 2025
minors'-privacy · copyright.gov/512 (DMCA) · ABA Business Law Today Nov-2024 (§230 +
generative AI) · congress.gov CRS LSB11097
