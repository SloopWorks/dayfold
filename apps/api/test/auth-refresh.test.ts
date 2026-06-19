import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
const { pool, q } = await import("../src/db.ts");
const { issueRefresh, rotate } = await import("../src/auth/refresh.ts");

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  await q(readFileSync(resolve(here, "../migrations/0001_m0_init.sql"), "utf8"));
  await q(readFileSync(resolve(here, "../migrations/0002_auth.sql"), "utf8"));
  await q(`INSERT INTO families(id,name) VALUES ('fam1','F')`);
  await q(`INSERT INTO credentials(id,kind,family_scope) VALUES ('c1','cli','fam1')`);
});
afterAll(async () => { await pool.end(); });

describe("refresh lineage", () => {
  it("rotate consumes old, issues new; old token no longer rotates fresh", async () => {
    const r1 = await issueRefresh("c1");
    const out = await rotate(r1);
    expect(out && "refresh" in out).toBe(true);
  });
  it("older-consumed reuse revokes the whole lineage (credential.revoked_at set)", async () => {
    const a = await issueRefresh("c1");
    const b = await rotate(a) as { refresh: string };   // a consumed → b
    await rotate(b.refresh);                              // b consumed → c
    const reuse = await rotate(a);                        // reuse of consumed a
    expect(reuse).toEqual({ reuse: true });
    const cred = await q(`SELECT revoked_at FROM credentials WHERE id='c1'`);
    expect(cred.rows[0].revoked_at).not.toBeNull();
  });
});
