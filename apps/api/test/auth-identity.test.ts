import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { applyAllMigrations } from "./_migrations.ts";
process.env.DATABASE_URL ||= "postgres:///fad_test";
const { pool, q } = await import("../src/db.ts");
const { findOrCreateUser, createFamily } = await import("../src/auth/identity.ts");

beforeAll(async () => {
  await applyAllMigrations(q);
});
afterAll(async () => { await pool.end(); });

describe("identity + family", () => {
  it("find-or-create is idempotent per (provider, provider_uid)", async () => {
    const a = await findOrCreateUser({ provider: "dev", provider_uid: "x" });
    const b = await findOrCreateUser({ provider: "dev", provider_uid: "x" });
    expect(a.userId).toBe(b.userId);
  });
  it("findOrCreateUser converges under concurrent calls (race-safety)", async () => {
    const idn = { provider: "dev", provider_uid: "concurrent-race-" + Date.now() };
    const results = await Promise.all([
      findOrCreateUser(idn),
      findOrCreateUser(idn),
      findOrCreateUser(idn),
    ]);
    const userIds = results.map((r) => r.userId);
    // All callers must resolve to the same userId — no duplicates, no errors.
    expect(userIds[0]).toBe(userIds[1]);
    expect(userIds[0]).toBe(userIds[2]);
    // Exactly one identity row for this (provider, provider_uid).
    const rows = await q(
      `SELECT user_id FROM user_identities WHERE provider=$1 AND provider_uid=$2`,
      [idn.provider, idn.provider_uid],
    );
    expect(rows.rowCount).toBe(1);
  });
  it("createFamily makes the creator an active owner", async () => {
    const u = await findOrCreateUser({ provider: "dev", provider_uid: "y" });
    const f = await createFamily(u.userId, "Smiths");
    const m = await q(`SELECT role,status FROM memberships WHERE user_id=$1 AND family_id=$2`, [u.userId, f.familyId]);
    expect(m.rows[0]).toEqual({ role: "owner", status: "active" });
  });
});
