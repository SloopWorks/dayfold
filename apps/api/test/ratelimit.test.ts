import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
const { pool, q } = await import("../src/db.ts");
const { hit, isLocked, recordFailure, resetFailures } = await import("../src/auth/ratelimit.ts");
const { audit } = await import("../src/auth/audit.ts");

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
});
afterAll(async () => { await pool.end(); });

describe("ratelimit + audit", () => {
  it("hit counts within one window and reports over-cap", async () => {
    let last;
    for (let i = 0; i < 4; i++) last = await hit("ip:authorize:1.2.3.4", 600, 3);
    expect(last!.count).toBe(4);
    expect(last!.ok).toBe(false); // 4 > cap 3
  });
  it("lockout: recordFailure sets locked_until after threshold", async () => {
    for (let i = 0; i < 5; i++) await recordFailure("account:approve:uX", 900, 5, 900);
    expect(await isLocked("account:approve:uX")).toBe(true);
    await resetFailures("account:approve:uX");
    expect(await isLocked("account:approve:uX")).toBe(false);
  });
  it("audit writes a row", async () => {
    await audit("device.approve", { actorUserId: "u1", familyId: "f1", detail: { x: 1 } });
    const r = await q(`SELECT event, actor_user_id, detail FROM audit_log WHERE event='device.approve'`);
    expect(r.rows[0].actor_user_id).toBe("u1");
    expect(r.rows[0].detail.x).toBe(1);
  });
});
