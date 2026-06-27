# Prior Art & Competitive Landscape: Subscribable Community Feeds for Families

*Raw per-agent output. Domain: prior art & competitors. Archived per `processes/research-workflow.md` §5.*

**Scope of this spike:** Who already lets families subscribe to public/community/institutional schedules & events that render alongside their own family content — and how. Read-only desktop research; no contact made, nothing signed up for.

**Headline finding:** The *plumbing* for subscribing to community/institutional calendars is utterly commoditized (native ICS subscribe in Google/Apple, and every family-calendar app supports "add calendar by URL"). What does **not** exist in consumer family products is a **curated, discoverable directory/marketplace of family-relevant local feeds** — i.e., the "find and subscribe to your school / your league / your library in one tap" layer. The one company that aggregated 200k+ such calendars (Burbio) **abandoned the consumer side entirely and became a B2B EdTech-sales-intelligence tool.** That is the central signal: the aggregation is hard, was built once, and was found to monetize on the B2B side, not the family side.

## 1. Landscape map (who does what)

| Player | Category | Subscribe to external/community feeds? | Mechanism | Verdict |
|---|---|---|---|---|
| **Burbio** | Calendar aggregator (former consumer, now B2B) | Aggregates 200k+ school/gov/library/nonprofit calendars — but consumer family product is **gone**; now sells K-12 procurement intelligence | Crawls + normalizes public calendars; output is B2B dashboards/RFP alerts | **confirmed (pivoted away from consumer)** |
| **Cozi** | Family organizer | Yes — explicitly markets subscribing to school, kids' sports, pro-sports feeds | "Add a Calendar from a URL" (ICS); auto-updates | **confirmed** |
| **FamilyWall** | Family organizer | Yes, but **gated behind Premium** | Subscribe to any public/shared calendar via URL | **confirmed** |
| **Maple** | Family OS | Partial — syncs Google/Outlook/Apple/**TeamSnap**, not arbitrary community feeds prominently | Account sync + TeamSnap integration | **partially-confirmed** |
| **Skylight Calendar** | Hardware family display | Yes — explicitly pitches "school / community center / sports team" via ICS URL, one-way sync | Synced Calendars → paste ICS/public URL | **confirmed** |
| **Jam** | Family calendar (email-forward) | **No subscribe-to-feed model** — instead ingests schedules by **forwarding emails**; auto-extracts events | Forward email → LLM-style extraction → calendar | **confirmed (different mechanism — closest to Dayfold's wedge)** |
| **Tinybeans** (acq. Red Tricycle 2020) | Photo app + local-events content | Has a local "Activities for Kids" event calendar as content, **not a subscribable feed into your calendar** | Editorial/listings; weekly newsletter | **partially-confirmed** |
| **Macaroni KID** | Local family-events content network | Local event calendars + weekly newsletter per city; **no evidence of ICS-subscribe-into-your-app** | Web listings + email newsletter | **partially-confirmed** |
| **Google Calendar** | Native | Yes — "Add other calendars → From URL" (public .ics) | ICS subscribe, ~12–24h refresh, desktop-only to add | **confirmed** |
| **Apple Calendar / iCloud** | Native | Yes — Add Subscribed Calendar (webcal://), public read-only links | ICS/webcal subscribe | **confirmed** |
| **Microsoft Outlook** | Native | Yes — subscribe via ICS URL ("Add calendar → From web") | ICS subscribe | **confirmed** (standard; consistent with the above) |
| **ParentSquare** | School-comms | Yes — parents can subscribe school calendar into iPhone/Google via ICS; two-way Google link | School publishes; parent subscribes via ICS | **confirmed** |
| **TeamSnap** | Youth sports | Yes — subscribe team/combined schedule to iCal/Google, auto-updates | Web → Sync Calendar/Export → ICS link | **confirmed** |
| **SportsEngine** | Youth sports | Yes — iCal Feed subscribe (mobile subscribe is iOS-only; ~24h sync lag) | iCal Feed icon on team/calendar page | **confirmed** |
| **Bloomz / ClassDojo / Remind / Schoology / PowerSchool / LeagueApps / GameChanger** | School-comms / sports | Mixed/walled — primarily in-app calendars; no clear marketed consumer ICS-subscribe-out | In-app event feeds | **partially-confirmed / unverifiable** |
| **Eventbrite / library / local-gov calendars** | Local events | Yes at the source level — many expose ICS/RSS feeds | Standard ICS/RSS per venue | **confirmed (per-source, fragmented)** |
| **Gemini Daily Brief** | AI briefing | **No** — only Gmail/Calendar/Tasks/Meet; no external community feeds | Google Workspace data only | **confirmed (refuted that it ingests community feeds)** |
| **Alexa+** | AI briefing | No evidence found of community-feed ingestion | — | **unverifiable** |

## 2. Closest analogues to the Dayfold concept

**(a) Burbio — the cautionary precedent (most important).** Burbio is the only company that actually built the thing this spike imagines at scale: it aggregated **200,000+ school, government, library and nonprofit community calendars across all 50 states** for a consumer-facing "subscribe to your community's calendars" product. It then **pivoted entirely to B2B** — today it's a PreK-12 sales/procurement intelligence platform, with **no consumer family calendar surface remaining**. Implication: the data-aggregation moat is real and hard to build, *but the consumer monetization wasn't there*. *confirmed.* (https://about.burbio.com/, https://www.crunchbase.com/organization/burbio-com)

**(b) Skylight / Cozi / FamilyWall — the "ICS subscribe" baseline.** All three already let a family paste a school/league/community-center ICS URL and render those events beside family events — exactly the literal mechanic in the spike. This is **table stakes, not differentiation.** Shared friction: the user must *find* the ICS URL themselves. **Nobody removes the discovery step.** *confirmed.* (https://www.cozi.com/how-to-add-an-ical-feed-to-cozi/, https://myskylight.com/lp/calendar-syncing/)

**(c) Jam — closest to Dayfold's *actual* wedge (competitor to watch).** Jam does **email-forward → auto-extract events + smart reminders** ("buy shin guards before soccer"). Structurally the closest thing to Dayfold's content-API/forward + smart-actions model, applied to family logistics, live on iOS/Android. Not doing community hubs, but occupies adjacent mindshare. *confirmed.* (https://www.jamfamilycalendar.com/how-it-works)

**(d) ParentSquare / TeamSnap / SportsEngine — the institutional sources.** Where families actually *get* schedules, and they all already expose ICS subscribe-out. So the upstream feeds Dayfold would want largely *exist as standard ICS today*. Caveats: SportsEngine mobile-subscribe iOS-only, ~24h late; native ICS refresh 12–24h. *confirmed.*

## 3. What's notably ABSENT (the white space)

1. **A curated directory/marketplace of family-relevant local feeds.** No consumer product offers "search your district/league/library and one-tap subscribe." Every existing tool makes the *user* find the raw ICS URL. Burbio built this once and walked away on the consumer side.
2. **Community feeds inside an AI briefing.** Gemini Daily Brief ingests **only** Gmail/Calendar/Tasks/Meet — no external/community feeds. No briefing product blends *your* signals with *subscribed community* signals. (Absence of a feature, not proof of demand.)
3. **Structured "hubs" vs flat event lists.** No one renders a community thing as a structured page the way Dayfold's Event Hubs do. The community-authored *hub* (vs *event*) is genuinely novel here.
4. **Multi-member family-tenant rendering of subscribed feeds.** None model "this league feed → child A, this PTA feed → both parents" as a first-class family-tenant concept.

**Exhaustive negatives:** Bloomz/ClassDojo/Remind = in-app calendars only (partially-confirmed/unverifiable); Schoology/PowerSchool/LeagueApps/GameChanger consumer ICS-out not surfaced (unverifiable); Red Tricycle = acquired by Tinybeans 2020, not shut (refuted); Alexa+ community-feed ingestion = no evidence (unverifiable); no "marketplace/directory of subscribable family hubs" exists.

## 4. Sources
- Burbio: https://about.burbio.com/ · https://www.crunchbase.com/organization/burbio-com
- Cozi: https://www.cozi.com/how-to-add-an-ical-feed-to-cozi/ · https://www.cozi.com/how-to-add-a-school-calendar-to-cozi/
- FamilyWall: https://support.familywall.com/en/support/solutions/articles/47001239556-add-external-calendars-in-familywall-via-url
- Skylight: https://myskylight.com/lp/calendar-syncing/
- Jam: https://www.jamfamilycalendar.com/how-it-works
- Maple: https://www.growmaple.com/blog-posts/best-family-calendar-app
- Tinybeans / Red Tricycle: https://theygotacquired.com/content/red-tricycle-acquired-by-tinybeans/ · https://tinybeans.com/event-calendar-faq/
- Macaroni KID: https://national.macaronikid.com/events/calendar
- Google Calendar ICS: https://support.google.com/calendar/answer/37100
- Apple/iCloud subscribe: https://support.apple.com/guide/icloud/share-a-calendar-mm6b1a9479/icloud
- ParentSquare: https://www.parentsquare.com/school-services/calendar-rsvp/
- TeamSnap: https://helpme.teamsnap.com/article/1245-subscribe-to-a-team-schedule
- SportsEngine: https://help.sportsengine.com/en/articles/6307106-how-to-subscribe-to-an-ical-feed
- Gemini Daily Brief: https://gemini.google/overview/daily-brief/ · https://support.google.com/gemini/answer/17077455

**One-line takeaway:** The subscribe-to-community-feed *mechanic* is fully commoditized; the **curated discovery/hub layer and AI-briefing-with-community-feeds are genuinely empty** — but Burbio built the hard aggregation once and **fled the consumer side for B2B**, a strong demand-side caution, not a green light.
