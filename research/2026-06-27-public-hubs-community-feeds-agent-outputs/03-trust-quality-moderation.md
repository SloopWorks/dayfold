# Trust, Quality, Accuracy & Moderation

*Raw per-agent output. Domain: trust/quality/moderation. Archived per `processes/research-workflow.md` §5.*

**Bottom line:** Community-authored/aggregated event feeds carry three compounding risk classes — (1) **accuracy/staleness** (dominant, mundane), (2) **spam/abuse** (adversarial, scales with openness), and (3) **liability for amplification**, sharpened for Dayfold because it *re-authors* feed content into AI briefing cards — which likely strips the Section 230 shield that protects pure aggregators. The family use case sets an unusually high trust bar (a missed game / wrong pickup is real-world harm), and the gap between that bar and what community feeds reliably deliver is the central risk of this direction.

## 1. Failure Modes Catalog

### A. Accuracy & staleness (the everyday killer)
- **Duplicate events from re-imports** — canonical iCal-aggregation failure: per-event UID changes on each import (Outlook/Google notorious), so the aggregator can't recognize an event it already has. **Confirmed** (The Events Calendar KB: https://theeventscalendar.com/knowledgebase/troubleshooting-duplicate-imports/).
- **Stale / cancelled events persist** — aggregated copies miss the source's cancellation/reschedule (same UID-instability root). **Partially confirmed** (strong inference; league vendors sell "instant cancellation propagation" because the pain is real: https://www.ezfacility.com/blog/sports-league-scheduling-problem/). No primary post-mortem of a specific aggregator surfacing a cancelled event as live — flagged as a gap.
- **Source-of-truth itself is wrong/outdated** — schools revise dates, third parties lag. **Confirmed** (public-schools calendar disclaimers tell users to "verify all dates directly with your school district": https://publicschoolscalendar.com/disclaimer/).
- **Wrong times / timezone & recurrence drift** — **Partially confirmed** (sync threads cite malformed/duplicated recurring events; no single authoritative TZ breakdown).

**Key technical takeaway:** the most reliable, documented failure of *aggregating* (vs authoring) calendars is **UID-instability → duplicates + missed updates.** Any ingest design must treat stable event identity + change-detection as first-class.

### B. Spam, abuse & bad actors (scales with openness)
- **Phishing-via-events** — Eventbrite abused to launder phishing through a trusted domain; attacks +900% total / 25% WoW since July 2024. **Confirmed (quantified)** (https://www.helpnetsecurity.com/2024/10/29/eventbrite-phishing/).
- **Fake/duplicate/placeholder listings** to harvest emails / sell fake tickets. **Confirmed** (Trustpilot reports: https://ca.trustpilot.com/review/www.eventbrite.com).
- **Event impersonation/ticket scams** on Facebook Events ("20+ fake events" cloning one show); Meta removed 100M+ fake Pages in 2024. **Confirmed** (https://about.fb.com/news/2025/04/cracking-down-spammy-content-facebook/).
- **Coordinated flagging / report-brigading** — bad actors weaponize moderation itself (Nextdoor). **Confirmed** (https://blog.nextdoor.com/2021/02/10/how-moderation-works-on-nextdoor).

**Key abuse takeaway:** worst-case isn't a typo — it's a **trusted-channel phishing vector aimed at families.** A subscribable hub families implicitly trust is a higher-value target than a generic listing site. Avoid replicating email/notification fan-out from feeds.

### C. Moderation-system failure modes
- **Ill-defined policy → inconsistent enforcement** (Nextdoor "misinformation" critique). **Confirmed.**
- **Community/volunteer moderation → bias & capture** (Nextdoor allowed false candidate info to circulate). **Confirmed** (https://www.kolotv.com/2024/12/20/claims-political-censorship-causes-community-outcry/).

## 2. Moderation / Trust Models + Tradeoffs

| Model | How it works | Reliability | Cost | Fit for family-trust product |
|---|---|---|---|---|
| **Fully curated / pre-moderated** | Every item reviewed before publish | Highest accuracy/safety | Highest ongoing labor; doesn't scale | Best matches family trust bar; cost is the killer for solo operator |
| **Community-submitted-with-review** (post-mod) | Publishes, flags for later | Medium — bad content live until caught | Medium; scales w/ AI pre-filters | Risky: the *window* is exactly when a wrong pickup time harms |
| **Open / reactive (flag-driven)** | Publishes freely, relies on reports | Lowest — spam/bias/brigading | Lowest labor, highest reputational cost | **Poor fit** |

**How small teams do it (confirmed pattern):** manual review by one person while volume is low → layer AI/keyword/image pre-filters to auto-reject obvious junk → route only ambiguous cases to humans (a hybrid funnel). Outsourced moderation ~$50–99/hr. (https://www.unitary.ai/articles/why-user-generated-content-moderation-is-critical-for-small-and-medium-sized-platforms, https://clutch.co/content-moderation/pricing)

**Implication for Dayfold:** the operator-scale-friendly path is **not** open community submission. It's **ingest-from-authoritative-source + automated dedupe/change-detection + curated allowlist of feeds** — treating "which hubs exist" as a curated decision and "what's in them" as machine-validated against a single source. Sidesteps the human-moderation cost wall + the open-model abuse modes at once.

## 3. The Trust-Bar Gap for a Family Product

**The trust bar is unusually high and the harm is concrete.** A family calendar is treated as a *single source of truth*; a wrong pickup time / missed game is real-world harm to a child. Industry disclaimer language tacitly admits the gap: calendar sites "cannot guarantee" accuracy and instruct users to "verify with official sources" — i.e., they explicitly **disclaim being a source of truth.**

**The gap, plainly:** families *want* a single source of truth; community/aggregated feeds *structurally cannot be one* (UID-instability → stale/dup; source revises late; open submission invites spam); every comparable product closes the gap with a **legal disclaimer ("verify with the source")** — the *opposite* of Dayfold's calm, trustworthy, "I handle this" promise. **Direct tension with positioning.** Defensible resolution: **only surface what ties to an authoritative source with a freshness guarantee, and degrade gracefully** ("last synced 2h ago — tap source") rather than footnote every card with "verify with the school."

### Liability layer (sharpened for Dayfold)
- **Pure aggregators lean on Section 230** for third-party content. (https://en.wikipedia.org/wiki/Section_230)
- **But §230 likely does NOT cover Dayfold's core mechanic.** Dayfold *re-authors* feed content into AI-generated briefing cards. Legal consensus (2024–25): §230 protects platforms for *what users say*, not for what the platform itself generates; no immunity where the platform "materially contributes to the development of content." **Generating a card asserting a (wrong) pickup time is plausibly Dayfold's own speech, not the school's.** (https://www.americanbar.org/groups/business_law/resources/business-law-today/2024-november/beyond-search-bar-generative-ai-section-230-tightrope-walk/, https://www.congress.gov/crs-product/LSB11097)

**Net:** the AI-re-authoring that *is* Dayfold's differentiator is also what *removes* the cheap legal shield aggregators rely on. ADR-class (customer-data handling + maintenance/liability posture).

### Standard mitigation patterns observed
"Verify with source" disclaimers (universal); attribution-to-authoritative-source + link-back (Burbio's "mirror from source" model); liability-limiting ToS; freshness/last-synced signals + deep-link to source (the honest version for a trust-first product).

## 4. Sources
- https://theeventscalendar.com/knowledgebase/troubleshooting-duplicate-imports/
- https://support.google.com/calendar/thread/508581/
- https://www.ezfacility.com/blog/sports-league-scheduling-problem/
- https://publicschoolscalendar.com/disclaimer/
- https://www.helpnetsecurity.com/2024/10/29/eventbrite-phishing/
- https://ca.trustpilot.com/review/www.eventbrite.com
- https://about.fb.com/news/2025/04/cracking-down-spammy-content-facebook/
- https://blog.nextdoor.com/2021/02/10/how-moderation-works-on-nextdoor
- https://www.kolotv.com/2024/12/20/claims-political-censorship-causes-community-outcry/
- https://www.unitary.ai/articles/why-user-generated-content-moderation-is-critical-for-small-and-medium-sized-platforms
- https://clutch.co/content-moderation/pricing
- https://en.wikipedia.org/wiki/Section_230
- https://www.americanbar.org/groups/business_law/resources/business-law-today/2024-november/beyond-search-bar-generative-ai-section-230-tightrope-walk/
- https://www.congress.gov/crs-product/LSB11097
- https://about.burbio.com/community-event-tracker

## 5. Exhaustive Negatives & Gaps
- **Burbio accuracy complaints: NOT FOUND** (only marketing/help + city pages). Burbio's accuracy reputation is **unverifiable** from public search; only its self-asserted "real-time from source sites." Recommend deeper Reddit/Trustpilot dive if it matters.
- **No calendar-specific case law** on aggregator liability for inaccurate event info under §230 — the generative-AI-strips-230 reasoning is by analogy.
- **No single authoritative post-mortem** of an aggregator surfacing a cancelled event as live, or of TZ/recurrence corruption — inferred from UID-instability mechanism.

**One-line recommendation:** treat viability as gated on a *curated-feeds + authoritative-source-binding + freshness-guarantee* architecture, not community submission — and surface the **AI-re-authoring-removes-Section-230** point as an ADR-class liability question before any build.
