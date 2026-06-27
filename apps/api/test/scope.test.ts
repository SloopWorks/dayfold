import { describe, it, expect, afterAll } from "vitest";
process.env.DATABASE_URL ||= "postgres:///fad_test";
const { pool } = await import("../src/db.ts");
const { scopeAllows, grantedHubIds } = await import("../src/auth/scope.ts");

// Pure ADR 0029 scope matching. scope-gate.test.ts covers the global content path
// over HTTP; this locks the RESOURCE-QUALIFIED (`hub:<id>:read/write`) matching —
// the per-hub security primitive — and the structural parse (no split(':')).
afterAll(async () => { await pool.end(); });

describe("scopeAllows (ADR 0029 resource-scoped grants)", () => {
  it("a global content grant allows any resource + matching action", () => {
    expect(scopeAllows(["content:read"], "content", "read")).toBe(true);
    expect(scopeAllows(["content:read"], "hub:h1", "read")).toBe(true);   // global covers per-hub
    expect(scopeAllows(["content:write"], "hub:h1", "write")).toBe(true);
  });

  it("a resource-qualified grant allows ONLY that resource + action", () => {
    expect(scopeAllows(["hub:h1:read"], "hub:h1", "read")).toBe(true);
    expect(scopeAllows(["hub:h1:read"], "hub:h2", "read")).toBe(false);   // different hub denied
    expect(scopeAllows(["hub:h1:read"], "hub:h1", "write")).toBe(false);  // read grant ≠ write
  });

  it("fails closed with no matching grant", () => {
    expect(scopeAllows([], "content", "read")).toBe(false);
    expect(scopeAllows(["hub:h1:read"], "content", "read")).toBe(false);  // per-hub ≠ global
  });
});

describe("grantedHubIds (the per-hub LIST filter)", () => {
  it("a global content grant returns null = all hubs", () => {
    expect(grantedHubIds(["content:read"], "read")).toBeNull();
  });

  it("returns exactly the hub ids granted for that action", () => {
    expect(grantedHubIds(["hub:h1:read", "hub:h2:read"], "read")).toEqual(["h1", "h2"]);
    expect(grantedHubIds(["hub:h1:write"], "read")).toEqual([]);          // wrong action → none
    expect(grantedHubIds([], "read")).toEqual([]);
  });

  it("parses ids structurally so a hub id may contain ':' (no split)", () => {
    expect(grantedHubIds(["hub:a:b:read"], "read")).toEqual(["a:b"]);     // id = "a:b"
  });

  it("rejects a malformed empty-hub-id grant — no zero-length id leaks into the filter", () => {
    // `hub::read` has prefix+suffix but a ZERO-length id; the length guard must drop it,
    // else grantedHubIds returns [""] and the WHERE-IN would match an empty-id hub.
    expect(grantedHubIds(["hub::read"], "read")).toEqual([]);
    expect(grantedHubIds(["hub::read", "hub:h1:read"], "read")).toEqual(["h1"]); // valid one survives
  });
});
