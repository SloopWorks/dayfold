import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { applyAllMigrations } from "./_migrations.ts";
process.env.DATABASE_URL ||= "postgres:///fad_test";
const { pool, q } = await import("../src/db.ts");

beforeAll(async () => {
  await applyAllMigrations(q);
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
