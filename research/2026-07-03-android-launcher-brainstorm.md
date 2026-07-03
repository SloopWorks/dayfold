# Brainstorm: Dayfold as an Android Launcher (2026-07-03)

**Status: exploration / pre-ADR brainstorm** — operator-requested. Not a spec,
not adversarially reviewed, nothing here is decided. Any build touching this is
**ADR-gated** (scope: ADR 0007 declared home-screen surfaces OUT of the
prototype; N5 tracks the widget variant) and **design-gated** (ADR 0008 —
hi-fi mockups before build). Claim labels follow the research convention:
`[fact:source]` / `[estimate]` / `[assumption]`.

**Operator framing:** surface Dayfold's info *fully, when needed, first-class*.
App launching / app discoverability is explicitly **secondary** — the launcher
is a delivery vehicle for the briefing, not a launcher product.

---

## 1. The idea in one paragraph

An Android **launcher** is just an ordinary app whose activity declares the
`HOME` intent category; once the user picks it as their default home app, it is
the screen shown on every unlock, every Home press, and after every app closes
[fact:Android platform docs — `Intent.CATEGORY_HOME`, `RoleManager.ROLE_HOME`].
That makes it the **highest-frequency surface on a phone** — tens of glances a
day with *zero* navigation cost. "Dayfold Home" would make the Now feed (the
briefing + smart actions + hub timeline that already ship) literally *be* the
home screen: unlock the phone → today's brief is simply there. It is the
maximal version of the ambient-surfacing thesis that Phase A/B (derived Now,
geofence + time notifications) has been climbing toward, and the physical-world
version — a wall/fridge tablet in kiosk mode — is the strongest expression of
the one defensible wedge validation round 1 found (the **family-shared**
briefing no native OS ships [fact:research/validation-round1-agent-outputs/tech-platform.md]).

## 2. What it would look like

A calm, content-first home — closer to Before Launcher / Niagara / Olauncher
(the "minimalist launcher" category [fact:established Play-store category])
than to Nova. Sketch:

- **Home = the Now feed.** The existing merged feed (authored cards + derived
  items, bands, why-chips, geo-active ring) rendered full-bleed as the home
  screen, over the user's wallpaper or a calm Dayfold surface. Header: time /
  date / next-block glance. This is the shipped `FeedScreen` — not a new UI.
- **Dock:** 4–6 user-pinned essentials (Phone, Camera, Messages, Maps…). The
  one concession to "it must still be a phone."
- **Swipe up → app drawer:** search-first (keyboard up, type-to-launch),
  plain alphabetical list. No icon-grid billboard, no folders-as-decoration,
  optional grayscale icons. Deliberately boring — launching is secondary.
- **Swipe right/left or fold gesture → Hubs.** The existing hub list/detail
  as the second pane. The fold gesture already exists in the design language.
- **Swipe down:** system notification shade (standard launcher behavior).
- **Deep-link actions stay the point.** "School email needs an RSVP [reply]"
  on the *home screen* is the product thesis at full strength — Dayfold as the
  calm front door that routes you into Gmail/Calendar/lists, never replacing
  them. The launcher makes the "reads and links back" constitution posture
  *more* visible, not less.
- **Per-member by construction.** A launcher is per-device → each member's
  phone shows their own visibility-filtered view. This maps 1:1 onto the
  shipped per-member visibility model (ADR 0030) with zero new access-control
  work.
- **Anti-goals (scope firewall):** no news/feed panel, no app-usage stats, no
  notification badges, no "smart suggestions" rail. Every one of those is the
  attention-competing drift the constitution forbids.

### The tablet/kiosk variant (probably the best version)

A cheap Android tablet on a kitchen wall or charging dock, Dayfold Home as
launcher + keep-screen-on + Android's lock-task/dedicated-device ("kiosk")
mode [fact:Android Enterprise lock task mode docs]. That is a **family display**
— the Skylight Calendar / Hearth Display category (US$300–600 hardware plus
subscriptions [estimate — verify current pricing]) — for the cost of a used
tablet. Unlike the phone-launcher case there is **no behavior-change ask** (no
one has to give up their personal home screen), it is inherently
**family-shared** (the wedge), and the reliability bar is lower (one device,
one owner, plugged in). Dogfooding this in the operator's own kitchen is
exactly the learning-lab move.

## 3. Capabilities a launcher can leverage

What being HOME actually buys, and what it doesn't:

| Capability | What it gives Dayfold | Cost / caveat |
|---|---|---|
| **HOME role** (`ROLE_HOME` request dialog) | Default surface on every unlock/Home press — the whole point. No special Play permission to *be* a launcher [fact:Android docs] | Must be crash-free and instant; a broken home screen strands the user |
| **App list + launch** (`LauncherApps`, `QUERY_ALL_PACKAGES`) | The drawer. Launchers are an explicitly permitted use of `QUERY_ALL_PACKAGES` under Play policy [fact:Play policy — package-visibility permitted uses] | Play data-safety declaration + policy review; work-profile handling for completeness |
| **`AppWidgetHost`** | Embed *other apps'* widgets (family's Google Calendar widget, a weather widget) under the briefing — "reads and links back" made literal, and it back-fills data sources Dayfold doesn't integrate yet | Real API surface to host correctly (resize, config, permission dialog); defer past MVP [estimate] |
| **App shortcuts** (`LauncherApps.getShortcuts`) | Deep actions on dock icons (e.g. long-press Messages → spouse) | Nice-to-have only |
| **Boot presence** | The launcher is alive from boot — the already-shipped geofence/alarm background pass and sync engine ride along naturally; the existing `BootReceiver` pattern applies | None — glue already exists |
| **Kiosk / lock-task mode** | The tablet family-display variant | Device-owner provisioning is fiddly one-time setup [fact:Android Enterprise docs] |
| **`CalendarContract` (on-device calendar read)** | Not a launcher power per se, but the natural companion: the no-OAuth, no-CASA, fully-local calendar path already tracked in N4 — a launcher that also reads the device calendar locally gets "real family data on the home screen" with **zero Google verification burden** [fact:CalendarContract is a local content provider; runtime permission only] | Separate READ_CALENDAR permission + its own scope decision (N4) |
| ~~`UsageStatsManager`~~ (app suggestions) | — | **Skip.** Surveillance-flavored, engagement-optimizing; anti-calm |
| ~~`NotificationListenerService`~~ (badges) | — | **Skip at MVP.** Reads every notification on the device — a privacy posture change far beyond guardrail comfort |

**What a launcher does NOT get:** no privileged access to anyone's email,
calendar, or notifications. Dayfold's content still comes from the content API
exactly as today. The launcher changes *where the render lives*, not what data
exists. (Lock screen: not reachable — that's a different, mostly OEM-reserved
surface; Android 16-era lock-screen widgets are tablet-first and separate from
the HOME role [assumption — re-verify current Android release state].)

## 4. Feasibility

**Technically: clearly feasible, and unusually cheap for *this* codebase.**

- **UI reuse is ~total.** A launcher is a normal Compose activity. The KMP
  `:client` module already renders the whole product on Android;
  `:androidApp` is already a thin shell over it. "Dayfold Home" is plausibly a
  second thin Android module (or a build flavor / second activity-alias of the
  same app) that adds: the HOME intent filter, a transparent/wallpaper window,
  a dock row, and a drawer screen. Compare N5's widget note: Glance is a
  *second UI system* ("native-only, 2× UI") — the launcher, counterintuitively,
  reuses **more** of the existing UI than a widget does.
- **Everything hard is already shipped:** sync engine, offline-first store,
  per-member visibility, deep links, background notifications, single-writer
  WAL process-shared store. The launcher consumes all of it unchanged.
- **Effort:** dogfood-grade (operator's phone + kitchen tablet): roughly
  **2–4 part-time weeks** on top of the existing client [estimate]. The core
  is small: HOME manifest wiring + launcher-lifecycle correctness
  (`onNewIntent` on Home press, back-stack-root behavior, instant cold start),
  dock + drawer, wallpaper handling, kiosk recipe. **Public production-grade
  is a different animal** — OEM default-home flows (Samsung/Xiaomi quirks),
  gesture-nav edge cases, widget hosting, and a permanent compatibility tail;
  think months plus an ongoing maintenance tax [estimate].

**Where the real costs are (not code):**

1. **Reliability bar.** Your home screen must never crash, never jank, never
   show a spinner on unlock. The offline-first cold-start work already done
   helps a lot, but this raises the floor permanently — and steady-state ops
   is capped at <2 hrs/wk (goals-and-constraints).
2. **Adoption ask (phones).** "Replace your home screen" is one of the highest-
   friction asks in consumer Android; launcher-switchers are a small
   enthusiast niche [assumption — consistent with minimalist-launcher category
   size]. As a *distribution strategy* for families, this is weak. As a
   *power-user/dogfood mode*, it's fine. (The tablet variant dodges this
   entirely.)
3. **Android-only.** iOS has no launcher concept, period [fact:iOS platform].
   This is the first surface that structurally breaks the CMP
   every-platform-parity pattern. Acceptable if it's framed as an
   Android-native *mode*, not a core surface.
4. **Scope gravity.** Launchers want to grow features (search, badges,
   suggestions, news panels). Each is drift toward the "what it is not" list.
   The operator's "launching is secondary" framing needs to be written into
   the ADR as an explicit anti-goal, or a hundred small conveniences will
   accrete an attention product.
5. **Governance.** ADR 0007 scoped home-screen surfaces out; the constitution
   requires an ADR for scope changes; ADR 0008 requires signed-off mockups
   before build; a Play-published launcher adds a data-safety/policy review
   (operator-gated external action).

## 5. The honest comparison: launcher vs. widget vs. kiosk

| | Glance widget (N5) | Phone launcher | Tablet kiosk launcher |
|---|---|---|---|
| Ambient value | High (glanceable on any launcher) | Maximal | Maximal, and *shared* |
| Behavior-change ask | ~zero | Very high | Zero (new device) |
| Build cost | Medium — Glance = 2nd UI system | Medium — big reuse, new shell | Launcher + kiosk recipe |
| Ongoing tax | Low | High (OEM/compat tail) | Low (one known device) |
| iOS story | WidgetKit sibling exists | None | None (or a web dashboard later) |
| Wedge fit | Personal | Personal | **Family-shared — the validated wedge** |
| Audience | Every user | Launcher-switchers | Every family with a spare tablet |

The widget and the launcher are not rivals — they're different rungs of the
same "ambient Dayfold" ladder, and the kiosk tablet is the rung where the
launcher idea is *unambiguously* right.

## 6. Verdict and recommended path

**Is it feasible?** Yes — and cheaper than it sounds, because the entire
product UI and sync/background stack already exist; the launcher is mostly a
new front door. **Is it a good idea?** As a *learning-lab artifact and dogfood
mode*: genuinely good — it maximizes the surfacing thesis, exercises deep
Android system territory (HOME role, widget hosting, kiosk provisioning), and
its tablet form is the physical embodiment of the family-tenant wedge. As a
*mainstream phone distribution strategy*: no — the ask is too high and the
maintenance tail conflicts with the <2 hrs/wk constraint.

Suggested sequencing (all pending operator direction; nothing builds from this
doc):

1. **P0 — Glance widget (re-opens N5).** Cheapest ambient win for daily-carry
   phones; works under any launcher; pairs with the shipped notification lanes.
2. **P1 — "Dayfold Home" dogfood launcher,** operator's own phone and/or a
   kitchen tablet. Scope: HOME wiring + dock + search-first drawer + kiosk
   recipe. Explicit anti-goals in the ADR. No Play listing — sideload/internal
   track only, so no policy surface yet.
3. **P2 — decide with data.** After weeks of real household use: does the
   tablet display earn a place in the product story (a "works with any cheap
   tablet" family display vs. Skylight's $300 hardware)? That's a viability-
   review input, not a pre-commitment.

Gates before any build: **scope ADR** (launcher mode + its anti-goals;
supersedes/amends the ADR 0007 exclusion), **ADR 0008 mockups** (home surface,
drawer, dock, kiosk states — light+dark), and the usual adversarial plan
review. Play publication, if ever, is a separate operator-gated external
action.
