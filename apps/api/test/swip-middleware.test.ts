// The SWIP error middleware, driven through the REAL app (ADR 0058).
//
// What these tests are actually defending: on Vercel the container is frozen the moment
// the response is returned, and SWIP's pipeline batches (its Sentry tee is deliberately
// fire-and-forget). So an event recorded but not flushed BEFORE the response is simply
// lost — in production, silently, with the whole suite green. Two orderings are therefore
// load-bearing and are asserted here:
//
//   record() happens BEFORE flush()   (Hono's onError runs after our `finally` — a record
//                                      there would miss the flush by one tick, forever)
//   flush() completes BEFORE the response is returned
//
// The handle is a fake: a real one needs a real DSN and would send real events. The real
// one is proven by the live smoke (processes/agent-dev-loop.md § API), which is the only
// thing that can see a lost event.
import { beforeEach, describe, expect, it } from "vitest";
import type { SwipInstance } from "@sloopworks/swip-js";

process.env.ENABLE_DEV_ERRORS = "1"; // mounts /debug/boom + /debug/wtf (never in prod/preview)
const { app } = await import("../src/app.ts");
const { __setSwipForTest, swipErrors } = await import("../src/swip.ts");

interface Fake {
  handle: SwipInstance;
  calls: string[];
  recorded: { error: unknown; attrs?: Record<string, string>; mechanism?: string }[];
  flushed: number[];
  flushDelayMs: number;
}

function fakeSwip(): Fake {
  const f: Fake = { calls: [], recorded: [], flushed: [], flushDelayMs: 0, handle: null as never };
  f.handle = {
    errors: {
      record(error: unknown, attrs?: Record<string, string>, mechanism?: string) {
        f.calls.push("record");
        f.recorded.push({ error, attrs, mechanism });
      },
      wtf(key: string) {
        f.calls.push(`wtf:${key}`);
      },
      breadcrumb() {},
      drainStorms() {},
      async flush() {},
      health: () => ({ error_storm_evicted: 0, error_severity_coerced: 0 }),
    },
    async flush(timeoutMs?: number) {
      f.flushed.push(timeoutMs ?? -1);
      await new Promise((r) => setTimeout(r, f.flushDelayMs));
      f.calls.push("flush");
    },
  } as unknown as SwipInstance;
  return f;
}

describe("swip error middleware", () => {
  beforeEach(() => __setSwipForTest(null));

  it("records the error BEFORE it flushes, and flushes before the response returns", async () => {
    const f = fakeSwip();
    f.flushDelayMs = 25; // a flush that is not awaited will finish AFTER the response
    __setSwipForTest(f.handle);

    const res = await app.request("/debug/boom");

    expect(f.calls).toEqual(["record", "flush"]);
    expect(res.status).toBe(500); // behaviour unchanged: instrumentation is not a handler
  });

  it("passes the flush budget SWIP is documented to bound (never an unbounded await)", async () => {
    const f = fakeSwip();
    __setSwipForTest(f.handle);
    await app.request("/health");
    expect(f.flushed).toEqual([2000]);
  });

  it("flushes on the happy path too — an error mirrored in by Sentry's global hooks has no throw", async () => {
    const f = fakeSwip();
    __setSwipForTest(f.handle);
    const res = await app.request("/health");
    expect(res.status).toBe(200);
    expect(f.calls).toEqual(["flush"]);
    expect(f.recorded).toEqual([]);
  });

  it("records the route PATTERN and the method — never the URL (a family id is not telemetry)", async () => {
    const f = fakeSwip();
    __setSwipForTest(f.handle);
    await app.request("/debug/boom");
    expect(f.recorded).toHaveLength(1);
    expect(f.recorded[0]!.attrs).toEqual({ method: "GET", route: "/debug/boom" });
    expect(f.recorded[0]!.mechanism).toBe("hono");
    expect((f.recorded[0]!.error as Error).message).toContain("deliberate unhandled route error");
  });

  it("wtf() rides the same flush — the deliberate non-crash report is the point of the pillar", async () => {
    const f = fakeSwip();
    __setSwipForTest(f.handle);
    const res = await app.request("/debug/wtf");
    expect(res.status).toBe(200);
    expect(f.calls).toEqual(["wtf:dayfold.api.smoke", "flush"]);
  });

  it("is inert without a handle: no boot, no SWIP, unchanged behaviour", async () => {
    const res = await app.request("/health");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true, surface: "m0" });
  });

  it("does not report a deliberate 4xx (HTTPException) — that is the app talking, not a defect", async () => {
    const f = fakeSwip();
    const { Hono } = await import("hono");
    const { HTTPException } = await import("hono/http-exception");
    const probe = new Hono();
    probe.use("*", swipErrors(() => f.handle));
    probe.get("/nope", () => {
      throw new HTTPException(401, { message: "unauthorized" });
    });
    probe.get("/oops", () => {
      throw new HTTPException(503, { message: "upstream gone" });
    });

    const four = await probe.request("/nope");
    expect(four.status).toBe(401);
    expect(f.calls).toEqual(["flush"]); // flushed, not recorded

    const five = await probe.request("/oops");
    expect(five.status).toBe(503);
    expect(f.calls).toEqual(["flush", "record", "flush"]); // a 5xx IS a defect
  });

  it("never swallows the error it records — the client still gets the app's own response", async () => {
    const f = fakeSwip();
    const { Hono } = await import("hono");
    const probe = new Hono();
    probe.use("*", swipErrors(() => f.handle));
    probe.onError((err, c) => c.json({ handled: err.message }, 500));
    probe.get("/boom", () => {
      throw new Error("kaboom");
    });

    const res = await probe.request("/boom");
    expect(res.status).toBe(500);
    expect(await res.json()).toEqual({ handled: "kaboom" }); // the app's onError still ran
    expect(f.calls).toEqual(["record", "flush"]);
  });
});
