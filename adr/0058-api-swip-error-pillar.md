# ADR 0058: The API Reports Errors Through SWIP — Owned Stream + Sentry, Joined on a Fingerprint, Flushed Before the Function Freezes

## Status

**Proposed** 2026-07-14 (agent-drafted with the `feat/api-swip-errors` PR; accept
on merge). Composes with ADR 0055 (SWIP analytics on the client — same PostHog EU
project, same Infisical secrets path), ADR 0056 (SloopLogging), ADR 0015
(vendor accounts / data residency), ADR 0012 (agent-operated build). Realizes the
Dayfold half of SWIP's error-pillar design
(`docs/superpowers/specs/2026-07-13-swip-errors-sentry-design.md`, SWIP PR #67/#68).

**Blocked on publication:** the SWIP packages carrying this work
(`@sloopworks/swip-js`, `@sloopworks/swip-sentry`, `@sloopworks/swip-schema-dayfold`)
are not on GitHub Packages yet — SWIP PR #68 must merge and release first. See
"Merge order" below.

## Context

The API (Hono on Node, esbuild-bundled, Vercel functions) had **no error reporting at
all**: an exception in a route became a 500 and a line in Vercel's log, seen by nobody,
searchable for 24 hours. The client already reports analytics through SWIP (ADR 0055);
SWIP's error pillar is now built and proven against real vendors, so the API is the last
mile.

Three things about this environment shape every decision below:

- **Vercel freezes the container the moment the response is returned.** Anything not on
  the wire by then is lost.
- **SWIP's tee to Sentry is fire-and-forget by design** (`captureException` does disk
  I/O; it must never sit in a request's critical path), and SWIP's own pipeline batches.
- **Neither of those failures is visible to a test.** The suite is green whether or not
  the events ever leave the box.

## Decision

1. **One bootstrap module, `apps/api/src/swip.ts`, and no vendor wiring anywhere.**
   The handle is built from `DayfoldSwipNode.apiProd()` / `apiProdDeps()` — codegen
   output from `registry/products/dayfold.yaml` (SWIP INVARIANT 32 / SWIP ADR-0019).
   Product code binds the `SloopErrors` facade only: **zero `@sentry/*` imports in
   `apps/api/src`, no DSN in this repo, no `Sentry.init` we wrote.** The DSN is read from
   the environment, by name, by generated code.

2. **The Sentry project is the API's own — `SENTRY_NODE_EU_DSN`.** Dayfold has three
   Sentry DSNs in one EU org: this one, `SENTRY_KOTLIN_EU_DSN` (the KMP app) and a legacy
   `SENTRY_DSN`. All three are `*.ingest.de.sentry.io`, so a region check cannot tell them
   apart, and the wrong one files API errors into the mobile app's issue stream with the
   wrong alerts and the wrong release health — silently. SWIP asserts the org id
   (`o4511720596570112`) and project id (`4511734782820432`) against the live DSN at boot,
   so a mixup fails the boot instead. **Do not loosen that assertion to make a boot pass.**

3. **The flush lives in Hono middleware, in a `finally`, and the record happens before
   it.** `swipErrors()` is the outermost middleware in `app.ts`:

   ```ts
   try { await next(); if (c.error) report(c.error); }
   catch (err) { report(err); throw err; }
   finally { await swip.flush(2000); }
   ```

   Two orderings are load-bearing, and both are asserted in `test/swip-middleware.test.ts`:

   - **record → flush.** Hono's `onError` — the "natural" place to record — runs *after*
     this middleware's `finally` has unwound. An event recorded there would be recorded
     after the flush and would leave the box never.
   - **flush → response.** `flush(2000)` is hard-bounded and never rejects (SWIP builds it
     to be awaited in a `finally`): a stalled ingest costs a lost event, never a lost
     response, and never a 504.

   A thrown error is **rethrown untouched** — the client gets exactly the response it got
   before this middleware existed. Instrumentation must never change behaviour. A thrown
   `HTTPException` below 500 (401/404/413) is the app *talking to the client*, not a
   defect, and is not reported.

   **Hono catches a thrown `Error` inside `compose` and stashes it on the context, so
   `await next()` RESOLVES** — a middleware that only inspects its `catch` records nothing.
   That is why the `c.error` branch exists; it cost a red test to find, and it is exactly
   the failure mode that leaves a green suite and an empty issue stream.

4. **Consent, server-side, is granted (`consented: () => true`) — and that is a decision,
   not a default.** `initSentryNode` requires a consent gate because Sentry initializes
   globally and captures immediately; the gate stops the *SDK*, not merely its events.
   An error raised inside this API is a defect in **our infrastructure, reported about
   ourselves**:
   - the API never calls `analytics.identify()`, so the owned event's `distinct_id` is a
     per-container anonymous ULID — it names no family and no member;
   - swip-sentry sends **no `request` object** (no url, query, cookies, headers, body) and
     **no `extra`** — verified on the real wire, not just in a unit test;
   - the API is content-blind by construction (ADR 0015): it stores opaque blobs and never
     parses family content, so an exception message carries ids and driver text, not
     household content. `stripMessage` is therefore `false` — the message is the triage
     payload.

   There is no user whose consent could be asked for and no user-scoped data to withhold,
   so the gate is open. **When this must be revisited:** the moment an error event can
   carry a family id, a member id, or any user-supplied string — e.g. if we ever attach the
   authenticated subject to an error, or start reporting from a path that echoes request
   bodies. Then this becomes a real consent question and this ADR is superseded, not edited.

   Consent for the *owned* stream is scoped: `errors: granted`, `analytics: denied`,
   `telemetry: denied`. This process emits no product analytics.

5. **SWIP is bundled into the function; `@sentry/node` stays external.** The SWIP packages
   publish **TypeScript sources** (`"main": "src/index.ts"`), and Node refuses to
   type-strip anything under `node_modules` — left external, the function would fail to
   load in production on the first request. So `apps/api/scripts/build-fn.mjs` (replacing
   the one-line `esbuild --packages=external`) bundles `@sloopworks/*` and externalizes
   everything else. `@sentry/node` must stay external: Sentry does not support bundling it,
   and its OpenTelemetry instrumentation patches modules at load time.

   Consequence, stated rather than discovered: in a single-file ESM bundle every external
   import is hoisted, so Sentry's **auto-instrumentation of `pg`/`http` is not guaranteed**.
   We rely on `captureException` and Sentry's global handlers, which do not need it. We get
   no DB spans from Sentry; the telemetry pillar is where spans will come from.

6. **The reporter boots in the ENTRYPOINT, before the router is imported**
   (`vercel-entry.ts`, `server.ts`), and once per container (Vercel reuses warm ones).
   On Vercel it is **required**: a missing `SENTRY_NODE_EU_DSN` / `SENTRY_RELEASE` /
   `VERCEL_ENV` fails the boot, because its silent form is an API that believes it has
   error reporting and does not. Locally it is **optional**: no DSN ⇒ no SWIP, said out
   loud. `npm run env:check` now refuses a deploy environment missing the Sentry vars.

7. **`SENTRY_RELEASE` is set by whatever step deploys**, and must match any future
   sourcemap upload exactly or nothing symbolicates — silently. We upload no sourcemaps
   today (the committed bundle is the artifact), so the release is a label, not a symbol
   key; it becomes load-bearing the day a sourcemap lane exists. `VERCEL_ENV` (Vercel sets
   it) is what stops preview deploys reporting as `production` and polluting the prod issue
   stream and release health.

8. **A gated smoke route.** `ENABLE_DEV_ERRORS=1` (refused in production/preview, exactly
   like `/auth/dev-token`) mounts `/debug/boom` and `/debug/wtf`. A green unit test cannot
   see a lost event; only a real request against the real vendors can.

## Consequences

- Handled errors and `wtf()`s land in **both** PostHog (SWIP's owned stream) and Sentry
  (triage), joinable on `error.fingerprint` == Sentry's `swip.fingerprint` tag. Unhandled
  errors caught by Sentry's global hooks are mirrored back into the owned stream
  (`swip.origin: tee` is the loop guard that keeps our own tee from being mirrored twice).
- Every request now awaits a flush. On an empty pipeline that is a no-op (no batch is
  formed, so no network call); on a request that recorded something it is one batched
  POST, bounded at 2 s.
- PostHog has **no error-tracking UI** for these events (SWIP's schema is not PostHog's
  `$exception` shape; the mapping is a gateway-side job in SWIP's Phase 1). Sentry is the
  triage surface; PostHog is the owned archive and the join.
- The API now needs GitHub Packages credentials to install (`.npmrc` + `NODE_AUTH_TOKEN`),
  in CI and locally — the same posture the Gradle lanes already have for `swip-core`.

## Merge order (do not skip)

1. SWIP PR #68 merges → the changesets release publishes `@sloopworks/swip-js`,
   `@sloopworks/swip-sentry`, `@sloopworks/swip-schema-dayfold` to GitHub Packages.
2. `apps/api/package.json` pins the **published** versions; `npm install` refreshes
   `package-lock.json`; `npm run build:fn` re-emits the committed bundle.
3. Only then is this PR mergeable: CI runs `npm ci`, which cannot resolve a version that
   does not exist.

## Alternatives rejected

- **Recording in `app.onError`.** The natural Hono hook, and wrong: it runs after the
  middleware's `finally`, so the event misses the flush. Green tests, empty issue stream.
- **Flushing on a timer / not at all.** SWIP's batcher would flush "eventually" — but a
  Vercel container is frozen the instant the response is returned, so "eventually" is
  "never". This is the single easiest thing to get wrong and the hardest to notice.
- **Hand-writing `Sentry.init` in the API** (a DSN in one file, "just this once"). It is
  what INVARIANT 32 exists to forbid: the vendor decisions — region, project identity,
  release, environment, privacy defaults — would then live in product code where nothing
  checks them, and the wrong-project mixup would have no gate at all.
- **A stricter server-side consent gate** (e.g. off unless a family opted in). It would gate
  our own infrastructure telemetry on a user decision the user has no stake in, while the
  events carry nothing about that user. Revisit if that stops being true (§4).
