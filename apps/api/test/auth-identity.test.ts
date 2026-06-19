import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
const { pool, q } = await import("../src/db.ts");
const { findOrCreateUser, createFamily } = await import("../src/auth/identity.ts");

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  await q(readFileSync(resolve(here, "../migrations/0001_m0_init.sql"), "utf8"));
  await q(readFileSync(resolve(here, "../migrations/0002_auth.sql"), "utf8"));
});
afterAll(async () => { await pool.end(); });

describe("identity + family", () => {
  it("find-or-create is idempotent per (provider, provider_uid)", async () => {
    const a = await findOrCreateUser({ provider: "dev", provider_uid: "x" });
    const b = await findOrCreateUser({ provider: "dev", provider_uid: "x" });
    expect(a.userId).toBe(b.userId);
  });
  it("createFamily makes the creator an active owner", async () => {
    const u = await findOrCreateUser({ provider: "dev", provider_uid: "y" });
    const f = await createFamily(u.userId, "Smiths");
    const m = await q(`SELECT role,status FROM memberships WHERE user_id=$1 AND family_id=$2`, [u.userId, f.familyId]);
    expect(m.rows[0]).toEqual({ role: "owner", status: "active" });
  });
});
