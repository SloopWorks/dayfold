# Strategic Fit & Business Model

*Raw per-agent output. Domain: strategy/business. Archived per `processes/research-workflow.md` §5.*

**Spike:** Should Dayfold let families subscribe to public/community-authored hubs (school calendars, league schedules, local kids' events)?

## 1. Fit with the defensible wedge
The validation-found wedge is **multi-member family-tenant briefing** — one account, multiple members, a calm rendered surface no native OS ships. Two very different products hide under "community feeds":

**A. Thin "subscribe a feed for my own family" (ICS in, rendered into our hub).** **Deepens the wedge.** Just *more high-signal input* to the same briefing — the kid's school ICS + soccer ICS become first-class content in the Event Hub the family already opens. Still rendering *this family's* signals, still not a system of record, still not social. Sits cleanly inside the constitution.

**B. Full community-authored / aggregated feeds (we source, normalize, moderate, redistribute public local-events content).** **A different game** — the Burbio / Macaroni KID / Locable business, whose core asset is the *catalog*, not the family tenant. Burbio is explicitly B2B data-intelligence selling school-calendar datasets [fact: about.burbio.com]; Macaroni KID runs on paid "Publisher Moms" per town doing manual weekly research; Locable concedes submission-based calendars depend on "manual submissions, reminders, and staff follow-up." None strengthens a *family-tenant briefing*; it adds a sourcing/freshness/moderation business that competes for operator attention with the one defensible thing.

**Verdict:** Option A reinforces the wedge. Option B is a strategic distraction re-aiming the project at the aggregator game — the adjacent drift the constitution's scope firewall is meant to stop. [estimate, high confidence]

## 2. Thin-slice vs full-vision options

| | **Crawl: per-family ICS subscribe** | **Walk: curated regional packs** | **Run: community-authored hubs** |
|---|---|---|---|
| What | Family pastes/subscribes a single ICS/webcal URL for *their own* school/team; we parse + render into their hub | We maintain a small set of vetted public ICS feeds families pick from | Public/community members author hubs others subscribe to |
| Asset | The family tenant (unchanged) | A small curated catalog (new asset) | A content platform + contributor network |
| Legal/ops burden | **Near-zero** — family supplies URL; we render their own data | Medium — we choose/vet/refresh feeds; redistribution posture | High — moderation, contributor trust, spam, takedown, PII |
| Learning-lab compatible | Yes | Marginal | No |
| Business-model match | Same (render this family's signals) | Drifting toward aggregator | Aggregator / social-adjacent |

The **crawl slice captures most user-felt value** (kid's school dates + soccer schedule are the two feeds that matter) at **near-zero marginal burden**, because the family does the sourcing and the content is *their own data they chose to point us at* — no redistribution, no moderation, no catalog SLA. Mirrors how Caldzy already works (paste TeamSnap/SportsEngine/GameChanger feed → shared family view) [fact: caldzy.com] — validation that the slice has value AND a warning the standalone version is taken (§4).

## 3. Cost/effort reality for solo operator + AI agents
Decisive asymmetry: **who owns freshness and moderation.**
- **Thin slice (A):** ICS parsing is a solved, bounded engineering task agents can maintain. Freshness is the *source's* problem; failures degrade *one family's hub* and visibly point upstream. ICS is laggy (12–24h, recurrence parsing slow, source-side throttling) [fact: calendarbridge.com] — but tolerable + non-scaling.
- **Full vision (B):** operator inherits an *ongoing, non-delegable* burden — sourcing every district (varied formats, unreliable ICS), continuous freshness re-validation, and **moderating community-authored content** (a real spam/abuse surface). AI can *assist* moderation but cannot *own* liability/judgment. A part-time job, not a feature. **Incompatible with a learning lab** targeting near-zero steady-state ops.

**Verdict:** [estimate, high confidence] thin slice is agent-maintainable + lab-compatible; full vision converts Dayfold from renderer to content operation with a permanent human-in-the-loop tax.

## 4. Does Burbio / Gemini / native already commoditize this?
Yes, at every layer:
- **Plumbing (ICS subscribe) is free + universal** — Apple/Google/Outlook natively subscribe; TeamSnap/SportsEngine/GameChanger/LeagueApps/Spond expose free ICS; districts publish free ICS [fact: teamsnap.com, fcps.edu/subscribe]. **Zero WTP for the act of subscribing.**
- **"Render nicely for the whole family" niche is taken** by Caldzy (paste feed → shared iCloud family view → reminders) [fact: caldzy.com] — the validation weakness made concrete (incumbents free; one paid niche already exists).
- **Aggregator layer is owned** by Burbio (B2B) + Macaroni KID / Mommy Poppins (consumer local-events, staffed by local publishers).
- **Gemini Daily Brief** is paid-only, 18+, pulls Gmail+Calendar, pitched at parents — but does **not** do local kids' events or multi-member family tenancy [fact: gemini.google/overview/daily-brief]. Confirms the wedge is the *family-tenant* angle, not the feed angle.

**Verdict:** Community feeds as a *business* are commoditized from below (free native ICS), beside (Caldzy), and above (Burbio/Macaroni). The only non-commoditized thing remains **multi-member family-tenant rendering** — reachable via the thin slice without entering contested markets.

## 5. Recommendation (go/no-go-shaped)
**NO-GO on the full community-feeds / community-authored vision.** A different business (content aggregation + moderation), occupied by better-resourced incumbents at every layer, permanent non-delegable ops/moderation/legal burden, pulls off the one defensible asset. The "Burbio drift" the spike worried about — confirmed.

**CONDITIONAL GO on the crawl/thin slice — as a wedge *input*, not a *business line*, sequenced behind core.** Let a family subscribe a single ICS/webcal URL for *their own* school/team, rendered into their Event Hub. Reasoning:
- Strengthens the defensible wedge (more reasons to open the briefing) at near-zero burden.
- Best **acquisition angle** in the spike: "subscribe your kid's school + soccer team and the whole family sees it" — concrete, demoable, a story Gemini/native don't tell. [estimate]
- **But does not, by itself, fix the validation weakness.** No WTP proven; native subscribe is free; Caldzy monetizes the thin slice. Treat ICS-subscribe as a *learning probe for acquisition* ("does 'subscribe your school + team' get a stranger family to set up an account?"), **not** the revenue thesis. If it converts strangers, strong signal for the wedge; if not, you've spent only a bounded, agent-built ICS parser.

**Guardrails if pursued:** (1) family-supplied URLs only — no aggregation/catalog/redistribution/community authoring (ADR-gated scope changes); (2) render-only, never system of record; (3) surface source + staleness honestly; (4) no PII ingestion beyond the public feed.

**Learning vs business value:** thin-slice learning value high + cheap (tests acquisition hypothesis, exercises agent-build/ingest loop); business value low + unproven (commoditized, no WTP). Full-vision learning value modest (mostly content-ops, off-thesis) with high cost + distraction. For a learning-lab-first project: **build the thin slice as an acquisition experiment; do not build the community platform.**

## Sources
- Burbio (B2B): https://about.burbio.com/ ; school-calendar insights: https://about.burbio.com/school-calendar-insights
- Caldzy (the thin slice, monetized): https://www.caldzy.com/
- TeamSnap calendar sync (free): https://www.teamsnap.com/teams/features/calendar-syncing
- SportsEngine subscribe (free): https://help.sportsengine.com/en/articles/6311504-how-to-sync-your-team-schedule-to-a-calendar-application
- GameChanger personal calendar: https://help.gc.com/hc/en-us/articles/115005457626-Integrating-Your-Personal-Calendar
- Fairfax County PS ICS (free): https://www.fcps.edu/subscribe
- Gemini Daily Brief: https://gemini.google/overview/daily-brief/
- ICS reliability limits: https://calendarbridge.com/blog/ics-icalendar-feeds-vs-real-time-sync-whats-the-difference/
- Macaroni KID: https://national.macaronikid.com/
- Community-calendar ops burden: https://www.locable.com/the-self-updating-community-calendar/
- Calendar spam: https://support.google.com/calendar/thread/172489030/
