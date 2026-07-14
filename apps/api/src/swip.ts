// The API's ONLY SWIP touch point (ADR 0058). Product code binds the SloopErrors
// facade; every vendor decision (which Sentry project, which region, which release,
// which environment) is codegen'd from `registry/products/dayfold.yaml` in the SWIP
// repo and reaches us through `DayfoldSwipNode` — INVARIANT 32 / SWIP ADR-0019. There
// is no `@sentry/*` import in this directory and no DSN in this repo: the DSN is read
// from the environment, by name, by generated code.
//
// Two things about this file are load-bearing and easy to undo:
//
// 1. THE HANDLE IS BUILT ONCE, AT MODULE SCOPE, FROM THE ENTRYPOINT — before the app
//    router is imported. `@sentry/node`'s auto-instrumentation patches modules as they
//    load, so a Sentry that starts after `app.ts` has pulled in `pg`/`hono` sees less
//    than one that starts before it. Vercel also reuses warm containers, so a
//    per-request init would re-register Sentry's global hooks on every invocation.
//
// 2. THE SWIP IMPORTS ARE DYNAMIC. `app.ts` imports this module for the middleware, and
//    the test suite imports `app.ts` with no SWIP env at all. A static import would pull
//    `@sentry/node` (and a boot-time DSN assertion) into `vitest`. `initSwip()` is the
//    only thing that loads SWIP, and only an entrypoint calls it.
import { HTTPException } from "hono/http-exception";
import type { MiddlewareHandler } from "hono";
import type { SwipInstance } from "@sloopworks/swip-js";

/** The flush budget awaited in the middleware's `finally`. SWIP races every leg
 *  (its pipeline AND Sentry's queue) against this deadline and never rejects, so the
 *  worst case is a LOST EVENT, never a lost response (SWIP INVARIANT 13). */
export const SWIP_FLUSH_BUDGET_MS = 2000;

let handle: SwipInstance | null = null;

/** The live handle, or null when SWIP was never booted (tests; local dev without
 *  credentials). Never throws — a caller must be able to run SWIP-less. */
export function swip(): SwipInstance | null {
  return handle;
}

/** Test seam: drive the REAL app (`test/swip-middleware.test.ts`) against a fake handle.
 *  Booting the real one in a test would need a real DSN and would send real events. */
export function __setSwipForTest(fake: SwipInstance | null): void {
  handle = fake;
}

function requireEnv(name: string): string {
  const value = process.env[name];
  // Same throw-idiom the rest of the API uses — `scripts/env-check.mjs` greps for it,
  // so a var required here is a var the deploy preflight refuses to skip.
  if (!value) throw new Error(`Missing required env var: ${name}`);
  return value;
}

/**
 * Boot the error pillar. Idempotent.
 *
 * `required: true` (Vercel) — a missing DSN/release/environment is a DEPLOY fault that is
 * identical on every request, and its silent form is a product that believes it has crash
 * reporting and does not. The generated wiring throws; we let it.
 *
 * `required: false` (local `node src/server.ts`) — no credentials, no SWIP, and it says so.
 */
export async function initSwip(opts: { required: boolean }): Promise<SwipInstance | null> {
  if (handle) return handle;
  if (!opts.required && !process.env.SENTRY_NODE_EU_DSN) {
    console.log("[swip] SENTRY_NODE_EU_DSN unset — error reporting is OFF (local dev).");
    return null;
  }

  const [{ Swip }, { PostHogTransport }, { DayfoldSwipNode }] = await Promise.all([
    import("@sloopworks/swip-js"),
    import("@sloopworks/swip-js/posthog"),
    import("@sloopworks/swip-schema-dayfold/node"),
  ]);

  const transport = new PostHogTransport({
    apiKey: requireEnv("POSTHOG_PROJECT_KEY"),
    host: requireEnv("POSTHOG_HOST"),
  });

  handle = Swip.init(
    DayfoldSwipNode.apiProd(),
    DayfoldSwipNode.apiProdDeps(
      { transport },
      {
        // CONSENT, SERVER-SIDE (ADR 0058). An error raised inside this API is a defect in
        // OUR infrastructure, reported about OURSELVES: the API never calls
        // `analytics.identify()`, so the owned event's `distinct_id` is a per-container
        // anonymous id, and swip-sentry sends no `request` object (no url, query, cookies,
        // headers, body) and no `extra`. Nothing in an event names a family or a member.
        // There is therefore no user whose consent could be asked for, and no user-scoped
        // data to withhold — so the gate is open. It is a gate, not a formality: the day an
        // event can carry a family/member identifier, this must become a real decision
        // again (see ADR 0058 "When this must be revisited").
        consented: () => true,
        // Keep `exception.message` — it is the triage payload, and this API is
        // content-blind by construction (ADR 0015): it stores opaque blobs and never
        // parses family content, so a server-side exception message carries ids and
        // driver text, not household content. SWIP's scrubber still runs over it.
        stripMessage: () => false,
      },
    ),
  );

  // Errors only. This process emits no product analytics and no telemetry; the pipeline
  // gate is per-scope, so denying those two costs the error stream nothing and keeps a
  // future analytics event from riding an un-decided consent scope by accident.
  handle.analytics.setConsent({ analytics: "denied", telemetry: "denied", errors: "granted" });
  return handle;
}

/**
 * The middleware. Two jobs, in this order, and the order is the whole point:
 *
 *   RECORD, then FLUSH.
 *
 * Hono's `onError` runs AFTER this middleware's `finally` has already unwound — so an
 * error recorded there would be recorded after the flush, and on Vercel the process is
 * frozen the moment the response is returned. It would leave the box: never. Recording in
 * the `catch` is what makes the flush able to see the event.
 *
 * The error is rethrown untouched, so Hono's own error handling (and the status the client
 * gets) is exactly what it was before this middleware existed: instrumentation must never
 * change behaviour.
 */
export function swipErrors(get: () => SwipInstance | null = swip): MiddlewareHandler {
  return async (c, next) => {
    const s = get();
    if (!s) return next();

    let reported = false;
    const report = (err: unknown): void => {
      if (reported) return; // c.error and a rethrow are the same error, seen twice
      reported = true;
      // A thrown HTTPException below 500 is the app SAYING something to the client
      // (401/404/413) — intended behaviour, not a defect. 5xx is a defect either way.
      if (err instanceof HTTPException && err.status < 500) return;
      s.errors.record(
        err,
        // The ROUTE PATTERN, never the URL: `/families/:fid/cards`, not the family id.
        { method: c.req.method, route: String(c.req.routePath ?? "unmatched") },
        "hono",
      );
    };

    try {
      await next();
      // A THROWN ERROR ARRIVES HERE, NOT IN THE `catch`. Hono's `compose` wraps every
      // handler: it catches a thrown Error, stashes it on the context (`c.error`) and runs
      // the app's own error handler itself — so `await next()` RESOLVES, and a middleware
      // that only looks in its `catch` records nothing. (It cost this branch a red test to
      // find; the `catch` below still matters, because Hono rethrows a non-Error throw.)
      if (c.error) report(c.error);
    } catch (err) {
      report(err);
      throw err;
    } finally {
      // THE FLUSH, and it must come after the report. Vercel freezes the container when
      // the response is returned; SWIP's pipeline batches and its Sentry tee is
      // fire-and-forget by design, so an unflushed event is simply lost — and every test
      // stays green while that happens. `flush()` is hard-bounded and never rejects: it is
      // built to be awaited exactly here.
      await s.flush(SWIP_FLUSH_BUDGET_MS);
    }
  };
}
