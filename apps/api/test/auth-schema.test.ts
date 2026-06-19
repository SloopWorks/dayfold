import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
const { pool, q } = await import("../src/db.ts");

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  await q(readFileSync(resolve(here, "../migrations/0001_m0_init.sql"), "utf8"));
  await q(readFileSync(resolve(here, "../migrations/0002_auth.sql"), "utf8"));
});
afterAll(async () => { await pool.end(); });

describe("0002_auth schema", () => {
  it("user_identities unique (provider, provider_uid)", async () => {
    await q(`INSERT INTO users(id) VALUES ('u1')`);
    await q(`INSERT INTO user_identities(id,user_id,provider,provider_uid) VALUES ('i1','u1','dev','uid1')`);
    await expect(
      q(`INSERT INTO user_identities(id,user_id,provider,provider_uid) VALUES ('i2','u1','dev','uid1')`),
    ).rejects.toThrow();
  });
  it("membership role CHECK rejects junk; PK prevents dupes", async () => {
    await q(`INSERT INTO families(id,name) VALUES ('fam1','F')`);
    await expect(q(`INSERT INTO memberships(user_id,family_id,role) VALUES ('u1','fam1','king')`)).rejects.toThrow();
    await q(`INSERT INTO memberships(user_id,family_id,role) VALUES ('u1','fam1','owner')`);
    await expect(q(`INSERT INTO memberships(user_id,family_id,role) VALUES ('u1','fam1','adult')`)).rejects.toThrow();
  });
});
