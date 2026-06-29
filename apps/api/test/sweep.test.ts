import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { generateKeyPair, exportJWK } from "jose";
const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.AUTH_ISS = "https://fad.test/auth"; process.env.AUTH_AUD = "fad-api-test";
process.env.ENABLE_DEV_AUTH = "1"; process.env.DEV_AUTH_SECRET = "dev"; delete process.env.VERCEL_ENV;
const kp = await generateKeyPair("EdDSA", { crv: "Ed25519", extractable: true });
const priv = await exportJWK(kp.privateKey); priv.kid = "k1"; priv.alg = "EdDSA";
process.env.AUTH_SIGNING_KEY = JSON.stringify(priv);
const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");
const { sweep } = await import("../src/auth/sweep.ts");

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0005_invites.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql","0015_two_way_reserve.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
});
afterAll(async () => { await pool.end(); });

const dev = { "content-type":"application/json", authorization:"Bearer dev" };
async function ownerOf(uid: string) {
  const t = (await (await app.request("/auth/dev-token",{method:"POST",headers:dev,body:JSON.stringify({provider:"dev",provider_uid:uid})})).json()).access;
  const fam = await (await app.request("/families",{method:"POST",headers:{...dev,authorization:`Bearer ${t}`},body:JSON.stringify({name:uid})})).json();
  return { token: t, familyId: fam.familyId };
}

describe("retention sweep", () => {
  it("purges expired rate_limits + terminal device codes, keeps fresh ones", async () => {
    const old = new Date(Date.now() - 3 * 24 * 3600 * 1000).toISOString();
    await q(`INSERT INTO rate_limits(key, window_start, count) VALUES ('old:rl', $1, 9)`, [old]);   // dead
    await q(`INSERT INTO rate_limits(key, window_start, count) VALUES ('fresh:rl', now(), 1)`);     // keep
    await q(`INSERT INTO rate_limits(key, window_start, count, locked_until) VALUES ('locked:rl', $1, 9, now() + interval '1 hour')`, [old]); // keep (still locked)
    await q(`INSERT INTO device_authorizations(device_code, user_code, status, created_at, expires_at) VALUES ('dc_old','UC1','expired',$1,$1)`, [old]); // dead
    await q(`INSERT INTO device_authorizations(device_code, user_code, status, created_at, expires_at) VALUES ('dc_fresh','UC2','pending',now(), now() + interval '10 min')`); // keep

    const r = await sweep();
    expect(r.rate_limits).toBeGreaterThanOrEqual(1);
    expect(r.device_authorizations).toBeGreaterThanOrEqual(1);
    expect((await q(`SELECT 1 FROM rate_limits WHERE key='old:rl'`)).rowCount).toBe(0);
    expect((await q(`SELECT 1 FROM rate_limits WHERE key='fresh:rl'`)).rowCount).toBe(1);
    expect((await q(`SELECT 1 FROM rate_limits WHERE key='locked:rl'`)).rowCount).toBe(1);   // lock still active
    expect((await q(`SELECT 1 FROM device_authorizations WHERE device_code='dc_old'`)).rowCount).toBe(0);
    expect((await q(`SELECT 1 FROM device_authorizations WHERE device_code='dc_fresh'`)).rowCount).toBe(1);
  });

  it("sweeps EXPIRED refresh tokens but KEEPS consumed-but-unexpired ones (reuse-detection)", async () => {
    await q(`INSERT INTO credentials(id,kind) VALUES ('swcred','app') ON CONFLICT DO NOTHING`);
    await q(`INSERT INTO refresh_tokens(token_hash,credential_id,expires_at) VALUES ('rt_expired','swcred', now()-interval '2 days')`);              // past lifetime → swept
    await q(`INSERT INTO refresh_tokens(token_hash,credential_id,consumed_at,expires_at) VALUES ('rt_consumed','swcred', now(), now()+interval '30 days')`); // consumed but live → KEEP (reuse-detection)
    await q(`INSERT INTO refresh_tokens(token_hash,credential_id,expires_at) VALUES ('rt_fresh','swcred', now()+interval '30 days')`);                // live → keep

    const r = await sweep();
    expect(r.refresh_tokens).toBeGreaterThanOrEqual(1);
    expect((await q(`SELECT 1 FROM refresh_tokens WHERE token_hash='rt_expired'`)).rowCount).toBe(0);   // gone
    expect((await q(`SELECT 1 FROM refresh_tokens WHERE token_hash='rt_consumed'`)).rowCount).toBe(1);  // KEPT — replay must still be caught
    expect((await q(`SELECT 1 FROM refresh_tokens WHERE token_hash='rt_fresh'`)).rowCount).toBe(1);     // kept
  });

  // Slice 6 (ADR 0040 §3) — content-tombstone GC: hard-purge soft-deleted content rows
  // older than the retention floor, but KEEP recent tombstones (so a client that synced
  // within the floor never misses a delete) and KEEP live rows.
  it("hard-purges content tombstones older than the retention floor; keeps recent + live", async () => {
    const o = await ownerOf("tombowner");
    const prov = JSON.stringify({ source: "claude" });
    const old = new Date(Date.now() - 200 * 24 * 3600 * 1000).toISOString();   // well past the ≥90d floor
    await q(`INSERT INTO briefing_cards(id,family_id,title,provenance,deleted_at,updated_at) VALUES ('c_oldtomb',$1,'x',$2,$3,$3)`, [o.familyId, prov, old]);
    await q(`INSERT INTO briefing_cards(id,family_id,title,provenance,deleted_at,updated_at) VALUES ('c_recenttomb',$1,'y',$2,now(),now())`, [o.familyId, prov]);
    await q(`INSERT INTO briefing_cards(id,family_id,title,provenance,updated_at) VALUES ('c_live',$1,'z',$2,now())`, [o.familyId, prov]);

    const r = await sweep();
    expect(r.content_tombstones).toBeGreaterThanOrEqual(1);
    expect((await q(`SELECT 1 FROM briefing_cards WHERE id='c_oldtomb'`)).rowCount).toBe(0);     // purged (past floor)
    expect((await q(`SELECT 1 FROM briefing_cards WHERE id='c_recenttomb'`)).rowCount).toBe(1);  // kept (within floor — slow clients still get the delete)
    expect((await q(`SELECT 1 FROM briefing_cards WHERE id='c_live'`)).rowCount).toBe(1);         // kept (live)
  });

  it("deletes an orphan expired invite but KEEPS one a membership references", async () => {
    const o = await ownerOf("sweepowner");
    // a redeemed invite → a pending membership references it (used_count=1)
    const mk = await (await app.request(`/families/${o.familyId}/invites`, { method:"POST", headers:{...dev,authorization:`Bearer ${o.token}`}, body: JSON.stringify({ mode:"link", max_uses:5 }) })).json();
    const invitee = (await (await app.request("/auth/dev-token",{method:"POST",headers:dev,body:JSON.stringify({provider:"dev",provider_uid:"sweep-invitee"})})).json()).access;
    const red = await app.request("/invites:redeem", { method:"POST", headers:{...dev,authorization:`Bearer ${invitee}`}, body: JSON.stringify({ token: mk.token }) });
    expect(red.status).toBe(200);
    // force both invites into the past
    await q(`UPDATE invites SET expires_at = now() - interval '2 days' WHERE id=$1`, [mk.invite_id]);
    // an orphan, never-redeemed, expired invite
    const orphan = await (await app.request(`/families/${o.familyId}/invites`, { method:"POST", headers:{...dev,authorization:`Bearer ${o.token}`}, body: JSON.stringify({ mode:"link", max_uses:5 }) })).json();
    await q(`UPDATE invites SET status='expired', expires_at = now() - interval '2 days' WHERE id=$1`, [orphan.invite_id]);

    const r = await sweep();
    expect(r.invites).toBeGreaterThanOrEqual(1);
    expect((await q(`SELECT 1 FROM invites WHERE id=$1`, [orphan.invite_id])).rowCount).toBe(0);   // orphan gone
    expect((await q(`SELECT 1 FROM invites WHERE id=$1`, [mk.invite_id])).rowCount).toBe(1);        // referenced kept (FK-safe)
  });
});

describe("GET /cron/sweep (Vercel Cron, CRON_SECRET-gated)", () => {
  const path = "/cron/sweep";
  it("unconfigured (no CRON_SECRET) → 404 (invisible)", async () => {
    delete process.env.CRON_SECRET;
    expect((await app.request(path)).status).toBe(404);
  });
  it("configured but missing/wrong bearer → 401", async () => {
    process.env.CRON_SECRET = "cron-top-secret";
    expect((await app.request(path)).status).toBe(401);                                   // no header
    expect((await app.request(path, { headers: { authorization: "Bearer wrong" } })).status).toBe(401);
    delete process.env.CRON_SECRET;
  });
  it("correct bearer → 200 + sweep counts", async () => {
    process.env.CRON_SECRET = "cron-top-secret";
    const r = await app.request(path, { headers: { authorization: "Bearer cron-top-secret" } });
    expect(r.status).toBe(200);
    const b = await r.json();
    expect(b).toHaveProperty("rate_limits");
    expect(b).toHaveProperty("device_authorizations");
    expect(b).toHaveProperty("invites");
    expect(b).toHaveProperty("refresh_tokens");
    delete process.env.CRON_SECRET;
  });
});
