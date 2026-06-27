# Data Sourcing & Supply Feasibility

*Raw per-agent output. Domain: data sourcing & ingestion feasibility. Archived per `processes/research-workflow.md` §5.*

**Bottom line:** ICS is real and near-universal as a *format*, but it is fragmented at the *per-instance URL* level (every school/team has its own feed, not a directory). There is no public discovery layer for the long tail. Aggregation is achievable but is fundamentally a labor/coverage problem, not a technical one — exactly the moat Burbio built with a manual ops team, not clever scraping.

## 1. Feed-format & source-availability map

| Source class | Public ICS/feed exists? | Access model | Verdict |
|---|---|---|---|
| **District CMS — Finalsite** | Yes. Per-calendar `.ics` URLs + RSS. | Public, unauthenticated, per-calendar URL. | **Confirmed** |
| **District CMS — Blackboard/Edline** | Yes. iCal/webcal link, subscribable. | Public webcal URL per calendar. | **Confirmed** |
| **District CMS — Edlio** | Integrates Google & iCal; outbound `.ics`/RSS export not clearly documented. | Likely public, export unconfirmed. | **Partially-confirmed** |
| **SIS — PowerSchool/Schoology** | Yes, but **personal/account-scoped** iCal link, gated behind login, breaks if calendar empty. Not a public community feed. | Per-user, auth-gated. | **Confirmed (wrong shape — personal, not community)** |
| **SIS — Infinite Campus** | Parent-portal calendar; no public iCal export found. | Auth-gated portal. | **Partially-confirmed (negative-leaning)** |
| **TeamSnap** | Per-team subscribe / iCal / CSV. **Plus public REST API** (OAuth2, SDKs). | Per-team ICS public-ish; API OAuth2 consent. | **Confirmed** |
| **SportsEngine** | Per-team "Sync Schedule" → iCal/Google. **Plus GraphQL Integrations API** (OAuth2, org-admin consent). Web-cal lag ≤24h. | Per-team ICS; API gated. | **Confirmed** |
| **GameChanger** | **No public API**, no dev portal. CSV/PDF export only; scraping violates ToS + auth-walled. | Effectively closed. | **Confirmed (closed)** |
| **LeagueApps** | API referenced + import/export tooling. Not a public discovery feed. | Partner/API access. | **Partially-confirmed** |
| **Libraries — LibCal (Springshare)** | Yes. iCal, Google, RSS widgets + read/write API. | iCal/RSS public; API admin-enabled, priced per-institution. | **Confirmed** |
| **Eventbrite** | **Public event search API killed Dec 12, 2019** and remains dead. Only retrieval by event/venue/org ID. Discovery requires gated Distribution Partner Program. | Closed for discovery. | **Confirmed (refutes "just use Eventbrite")** |
| **Municipal / gov open data** | Varies wildly; no single standard or directory. | Heterogeneous. | **Partially-confirmed** |
| **Schema.org Event / JSON-LD** | Widely deployed for SEO; page-embedded, requires crawling each page; no feed/directory. | Crawl-only. | **Confirmed (scrape target, not a feed)** |

**Key format fact:** ICS/iCalendar is the universal lingua franca — schools, leagues, libraries, gov all speak it, and Google/Apple/Outlook all consume it. The supply problem is **not format, it's discovery + per-instance URL fragmentation + reliability.**

**ICS reliability/quality issues (confirmed):** timezones are the #1 bug source (missing VTIMEZONE → wrong times/double-bookings); malformed RRULEs silently drop/misplace recurring events; strict formatting (CRLF, 75-octet folding, escaping) → silent import failures (esp. Outlook). A production ingester needs a tolerant battle-tested parser + per-feed normalization + a validator — ongoing maintenance, not one-time.

## 2. Ingestion approaches, ranked by effort vs coverage

**A. Re-host a known public ICS URL the user supplies ("bring your own feed").** *Lowest effort, lowest coverage-burden, highest reliability per feed.* User pastes their school's Finalsite `.ics` + kid's TeamSnap subscribe link; Dayfold normalizes + renders. No discovery problem — user solves cold-start by self-identifying feeds. **Best fit for Dayfold's dogfood-first / multi-member-tenant wedge.**

**B. Official APIs (TeamSnap, SportsEngine, LibCal, LeagueApps).** *Medium effort/coverage, requires per-user OAuth.* Richer data, but every one is OAuth-gated with user/org-admin consent — no "read all public events" mode. Still per-household onboarding, not a populated public feed. GameChanger + Eventbrite-discovery closed.

**C. Crawl + scrape (schema.org JSON-LD, CMS pages, gov calendars).** *High effort, high coverage ceiling, high fragility + ToS exposure.* Per-CMS-template scrapers, constant breakage, ToS landmines. Burbio clarified to press it does **not** primarily scrape.

**D. Buy/license an aggregator (Burbio).** *Lowest build effort for breadth, but $$$ + dependency.* Burbio aggregates 80k+ K-12 calendars (>90% of US public K-12 students) + gov/library/community events, sells an API to consumer apps. Quote-only pricing; on AWS Marketplace. Re-introduces vendor dependency + recurring spend (Guardrail 6) + it's the same commoditized layer validation flagged.

**How Burbio actually sources (headline):** **Confirmed — predominantly a manual human ops team, not automated scraping or pure ICS harvest.** Co-founder on record: ~15 FT staff, each owns a set of districts, checks on a cadence (~72h during COVID), handle pop-up announcements case-by-case. The dataset is the moat *because* it's labor. **At-scale freshness is an ops cost, not an engineering trick.**

## 3. The cold-start / coverage problem (assessment)

- **A feed is worthless if the user's exact school/team isn't in it.** No public directory maps "Lincoln Elementary, district X" → its ICS URL. Discovery is the hard part; format is solved.
- **The long tail is brutal.** Even Burbio (funded, 15-person, multi-year) covers district-level academic calendars well, but the *granular* stuff families want (this specific class's field trip, this U10 team's rescheduled game) lives in **auth-gated per-user feeds** that **cannot be aggregated into a public hub** — scoped to the account holder.
- **"Community feeds" splits into two products:** (1) *broad public calendars* (district academic, library, city) — aggregatable but commoditized + low-resolution; (2) *granular team/class schedules* — high-value but inherently per-household + consent-gated, onboardable only via approach A/B.
- **Implication:** the "subscribe to a community hub" framing oversells what's aggregatable. The defensible buildable version is **"a household connects its own feeds and Dayfold normalizes + renders them into the multi-member briefing."** A genuinely populated public marketplace needs a Burbio license (recurring spend, commoditized) or a Burbio-scale manual ops team (out of scope for a learning lab). **Treat broad-public aggregation as buy-not-build if at all; center value on user-supplied/OAuth'd feeds.**

**Freshness cost signal:** only concrete public data point is Burbio's ~15 FT humans on a 72h cadence for nationwide coverage, plus standing ICS-parser engineering. **Freshness scales with headcount, not servers** — poor fit for a side-income learning lab unless scoped to "the family's own feeds."

## 4. Sources
- Finalsite Calendars — https://www.finalsite.com/school-websites/cms-for-schools/calendars
- Fairfax County PS subscribe — https://www.fcps.edu/subscribe
- Blackboard/Edline sync — https://help.blackboard.com/Edline/Student/Calendars/Sync_the_School_Calendar_to_Your_Personal_Calendar
- Edlio Calendars — https://help.edlio.com/apps/pages/calendars
- PowerSchool/Schoology iCal (empty-calendar limitation) — https://uc.powerschool-docs.com/en/schoology/latest/personal-account-parent-settings
- TeamSnap subscribe — https://helpme.teamsnap.com/article/1245-subscribe-to-a-team-schedule ; Ruby SDK — https://github.com/teamsnap/teamsnap_rb
- SportsEngine Dev Portal — https://dev.sportsengine.com/ ; Integrations API — https://www.sportsengine.com/blog/admin-topics-product-release-internal-use-managing-operations/new-release-integrations-api/
- GameChanger (no public API) — https://www.getdugout.com/gamechanger-api
- LeagueApps API — https://leagueapps.com/api/
- LibCal — https://www.springshare.com/libcal
- Eventbrite API — https://www.eventbrite.com/platform/api ; search-API deprecation — https://github.com/Automattic/eventbrite-api/issues/83
- Schema.org Event — https://schema.org/Event
- Burbio sourcing (manual, ~15 staff, 72h) — https://kappanonline.org/ladyzhets-the-story-behind-burbio-the-school-data-company-journalists-rely-on-russo/ ; overview — https://about.burbio.com/overview ; Datarade — https://datarade.ai/data-providers/burbio/data-products
- ICS reliability — https://www.spreadevent.com/blog/ics-file-complete-guide ; recurrence TZ bug — https://github.com/fullcalendar/fullcalendar/issues/6106

**Residual unknowns:** Edlio outbound `.ics`, Infinite Campus public iCal, exact LibCal API pricing, LeagueApps API depth — unverified from primary docs (would need login, out of scope). All "closed/no-API" verdicts (GameChanger, Eventbrite discovery) confirmed from multiple references.
