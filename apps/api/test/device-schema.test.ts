import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
const { pool, q } = await import("../src/db.ts");

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql", "0002_auth.sql", "0003_device_grant.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql"])
    await q(readFileSync(resolve(here, "../migrations/" + m), "utf8"));
});
afterAll(async () => { await pool.end(); });

describe("0003 device-grant schema", () => {
  it("status CHECK rejects junk; pending user_code is unique", async () => {
    await q(`INSERT INTO device_authorizations(device_code,user_code,expires_at) VALUES ('d1','AAAA-BBBB', now()+interval '10 min')`);
    await expect(q(`INSERT INTO device_authorizations(device_code,user_code,status,expires_at) VALUES ('d2','AAAA-BBBB','bogus', now())`)).rejects.toThrow();
    await expect(q(`INSERT INTO device_authorizations(device_code,user_code,expires_at) VALUES ('d3','AAAA-BBBB', now()+interval '10 min')`)).rejects.toThrow(); // pending dup
  });
  it("rate_limits PK(key,window_start); audit_log inserts", async () => {
    const fixedTs = '2026-01-01T00:00:00Z';
    await q(`INSERT INTO rate_limits(key,window_start,count) VALUES ('k', '${fixedTs}', 1)`);
    await expect(q(`INSERT INTO rate_limits(key,window_start,count) VALUES ('k', '${fixedTs}', 1)`)).rejects.toThrow();
    await q(`INSERT INTO audit_log(event) VALUES ('test.event')`);
    const a = await q(`SELECT count(*)::int n FROM audit_log`);
    expect(a.rows[0].n).toBeGreaterThan(0);
  });
});
