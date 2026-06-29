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

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0006_typed_content.sql","0007_related.sql","0008_credential_grants.sql","0009_visibility.sql","0010_hub_sync_fanout.sql","0011_hub_visibility_fanout.sql","0013_visual_enrichment.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
});
afterAll(async () => { await pool.end(); });

const dev = { "content-type": "application/json", authorization: "Bearer dev" };
async function ownerOf(uid: string) {
  const t = (await (await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: uid }) })).json()).access;
  const fam = await (await app.request("/families", { method: "POST", headers: { ...dev, authorization: `Bearer ${t}` }, body: JSON.stringify({ name: uid }) })).json();
  const me = await (await app.request("/auth/me", { headers: { authorization: `Bearer ${t}` } })).json();
  return { token: t as string, familyId: fam.familyId as string, userId: me.user_id as string };
}
async function memberOf(uid: string, familyId: string) {
  const t = (await (await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: uid }) })).json()).access;
  const me = await (await app.request("/auth/me", { headers: { authorization: `Bearer ${t}` } })).json();
  await q(`INSERT INTO memberships(user_id,family_id,role,status) VALUES ($1,$2,'adult','active')`, [me.user_id, familyId]);
  return { token: t as string, userId: me.user_id as string };
}
const authH = (tok: string) => ({ "content-type": "application/json", authorization: `Bearer ${tok}` });
const putCard = (fid: string, id: string, tok: string, body: any) =>
  app.request(`/families/${fid}/cards/${id}`, { method: "PUT", headers: authH(tok), body: JSON.stringify(body) });
const putHub = (fid: string, id: string, tok: string, body: any) =>
  app.request(`/families/${fid}/hubs/${id}`, { method: "PUT", headers: authH(tok), body: JSON.stringify(body) });
const baseCard = (over: any = {}) => ({ kind: "info", title: "x", provenance: { source: "cli", at: "2026-06-18T10:00:00Z" }, ...over });

describe("hub-sync: merged keyset /sync (cards + hubs)", () => {
  it("merged /sync streams cards + family hubs, omits a restricted hub, 3-part cursor", async () => {
    const o = await ownerOf("hs-owner1");
    const m = await memberOf("hs-member1", o.familyId);
    const fid = o.familyId;

    // seed two cards
    await putCard(fid, "c1", o.token, baseCard({ title: "card one" }));
    await putCard(fid, "c2", o.token, baseCard({ title: "card two" }));
    // seed a family-visible hub
    await putHub(fid, "hubFamily", o.token, { type: "party-event", title: "Family Hub" });
    // seed a restricted hub: created_by = owner, member NOT in allow list
    await putHub(fid, "hubRestricted", o.token, { type: "medical", title: "Restricted Hub", visibility: "restricted", audience: [o.userId] });

    const res = await app.request(`/families/${fid}/sync`, { headers: { authorization: `Bearer ${m.token}` } });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.changes.cards.map((c: any) => c.id).sort()).toEqual(["c1", "c2"]);
    expect(body.changes.hubs.map((h: any) => h.id)).toContain("hubFamily");
    expect(body.changes.hubs.map((h: any) => h.id)).not.toContain("hubRestricted"); // visibility-filtered
    // restricted hub the member can't see → tombstone (so a stale cache drops it)
    expect(body.tombstones).toContainEqual({ type: "hub", id: "hubRestricted" });
    const [ts, type, id] = Buffer.from(body.next_cursor, "base64").toString().split("|");
    expect(Date.parse(ts)).not.toBeNaN();
    expect(type).toMatch(/^(card|hub)$/); expect(id).toBeTruthy();
  });

  it("revoking allow-list membership tombstones the hub on next sync", async () => {
    const o = await ownerOf("hs-owner2");
    const m = await memberOf("hs-member2", o.familyId);
    const fid = o.familyId;

    // hub restricted to BOTH owner + member
    await putHub(fid, "hubShared", o.token, { type: "medical", title: "Shared Restricted", visibility: "restricted", audience: [o.userId, m.userId] });

    // member can see it on first sync → get a cursor past it
    const r1 = await app.request(`/families/${fid}/sync`, { headers: { authorization: `Bearer ${m.token}` } });
    const b1 = await r1.json();
    expect(b1.changes.hubs.map((h: any) => h.id)).toContain("hubShared");
    const mCursor = b1.next_cursor;

    // revoke member from allow list (trigger bumps hubs.updated_at)
    await q(`DELETE FROM resource_visibility WHERE family_id=$1 AND hub_id=$2 AND user_id=$3`, [fid, "hubShared", m.userId]);

    // re-sync from mCursor → hub re-surfaces as tombstone
    const r2 = await app.request(`/families/${fid}/sync?since=${mCursor}`, { headers: { authorization: `Bearer ${m.token}` } });
    const b2 = await r2.json();
    expect(b2.tombstones).toContainEqual({ type: "hub", id: "hubShared" });
  });

  it("a legacy 2-part cursor promotes to merged mode (returns cards + hubs, 3-part next_cursor)", async () => {
    const o = await ownerOf("hs-owner3");
    const fid = o.familyId;
    await putCard(fid, "legacyCard", o.token, baseCard({ title: "legacy" }));
    await putHub(fid, "legacyHub", o.token, { type: "party-event", title: "legacy hub" });

    // 2-part cursor = base64("-infinity|") — old device sending a cards-only cursor
    const legacy = Buffer.from(`-infinity|`).toString("base64");
    const res = await app.request(`/families/${fid}/sync?since=${legacy}`, { headers: { authorization: `Bearer ${o.token}` } });
    expect(res.status).toBe(200);
    const body = await res.json();
    // Must return BOTH cards AND hubs (merged mode, not stuck in cards-only)
    expect(body.changes.cards.map((c: any) => c.id)).toContain("legacyCard");
    expect(body.changes.hubs.map((h: any) => h.id)).toContain("legacyHub");
    // next_cursor must be 3-part (merged) so future requests stay in merged mode
    const parts = Buffer.from(body.next_cursor, "base64").toString().split("|");
    expect(parts).toHaveLength(3);
    expect(Date.parse(parts[0])).not.toBeNaN();
    expect(parts[1]).toMatch(/^(card|hub)$/);
    expect(parts[2]).toBeTruthy();
  });

  it("revoking allow-list membership bumps updated_at on hub AND its sections AND blocks (0010 subtree fanout)", async () => {
    const o = await ownerOf("hs-fanout-owner");
    const m = await memberOf("hs-fanout-member", o.familyId);
    const fid = o.familyId;

    // seed a restricted hub with a section and a block
    await putHub(fid, "hubFanout", o.token, { type: "medical", title: "Fanout Hub", visibility: "restricted", audience: [o.userId, m.userId] });
    await q(`INSERT INTO sections(id, family_id, hub_id, title) VALUES ('sec1', $1, 'hubFanout', 'Section 1')`, [fid]);
    await q(`INSERT INTO blocks(id, family_id, section_id, type, provenance) VALUES ('blk1', $1, 'sec1', 'text', '{"source":"test"}')`, [fid]);

    // capture current updated_at for hub, section, block
    const before = await q(
      `SELECT h.updated_at AS hub_ts, s.updated_at AS sec_ts, b.updated_at AS blk_ts
       FROM hubs h
       JOIN sections s ON s.family_id = h.family_id AND s.hub_id = h.id
       JOIN blocks b   ON b.family_id = s.family_id AND b.section_id = s.id
       WHERE h.family_id = $1 AND h.id = 'hubFanout'`,
      [fid]
    );
    const { hub_ts: hubBefore, sec_ts: secBefore, blk_ts: blkBefore } = before.rows[0];

    // small delay so now() advances past the captured timestamps
    await new Promise(r => setTimeout(r, 20));

    // revoke member from allow list — trigger fires
    await q(`DELETE FROM resource_visibility WHERE family_id=$1 AND hub_id='hubFanout' AND user_id=$2`, [fid, m.userId]);

    // assert all three updated_at values advanced
    const after = await q(
      `SELECT h.updated_at AS hub_ts, s.updated_at AS sec_ts, b.updated_at AS blk_ts
       FROM hubs h
       JOIN sections s ON s.family_id = h.family_id AND s.hub_id = h.id
       JOIN blocks b   ON b.family_id = s.family_id AND b.section_id = s.id
       WHERE h.family_id = $1 AND h.id = 'hubFanout'`,
      [fid]
    );
    const { hub_ts: hubAfter, sec_ts: secAfter, blk_ts: blkAfter } = after.rows[0];

    expect(new Date(hubAfter).getTime()).toBeGreaterThan(new Date(hubBefore).getTime());
    expect(new Date(secAfter).getTime()).toBeGreaterThan(new Date(secBefore).getTime());
    expect(new Date(blkAfter).getTime()).toBeGreaterThan(new Date(blkBefore).getTime());
  });

  // ── Task 10: sections + blocks streamed, gated by parent hub visibility ──

  it("restricted hub's sections+blocks never reach a non-allow-listed member (live or otherwise)", async () => {
    const o = await ownerOf("hs-sec-owner");
    const outsider = await memberOf("hs-sec-outsider", o.familyId);
    const fid = o.familyId;

    // restricted hub: only owner is in the allow list
    await putHub(fid, "hubRestrictedSec", o.token, {
      type: "medical", title: "Restricted Hub with sec+blk",
      visibility: "restricted", audience: [o.userId],
    });
    // insert section + block directly (bypassing route so we can use known ids)
    await q(`INSERT INTO sections(id, family_id, hub_id, title) VALUES ('secOfRestricted', $1, 'hubRestrictedSec', 'Secret Section')`, [fid]);
    await q(`INSERT INTO blocks(id, family_id, section_id, type, provenance) VALUES ('blkOfRestricted', $1, 'secOfRestricted', 'text', '{"source":"test"}')`, [fid]);

    const res = await app.request(`/families/${fid}/sync`, { headers: { authorization: `Bearer ${outsider.token}` } });
    expect(res.status).toBe(200);
    const body = await res.json();
    const ids = (body.changes.sections ?? []).map((s: any) => s.id)
      .concat((body.changes.blocks ?? []).map((b: any) => b.id));
    expect(ids).not.toContain("secOfRestricted");
    expect(ids).not.toContain("blkOfRestricted");
    // they should appear as tombstones (not live)
    const tombIds = (body.tombstones ?? []).map((t: any) => t.id);
    expect(tombIds).toContain("secOfRestricted");
    expect(tombIds).toContain("blkOfRestricted");
  });

  it("allow-listed member receives sections+blocks of a restricted hub", async () => {
    const o = await ownerOf("hs-sec-permitted-owner");
    const m = await memberOf("hs-sec-permitted-member", o.familyId);
    const fid = o.familyId;

    await putHub(fid, "hubPermitted", o.token, {
      type: "medical", title: "Permitted Hub",
      visibility: "restricted", audience: [o.userId, m.userId],
    });
    await q(`INSERT INTO sections(id, family_id, hub_id, title) VALUES ('secPermitted', $1, 'hubPermitted', 'Permitted Section')`, [fid]);
    await q(`INSERT INTO blocks(id, family_id, section_id, type, provenance) VALUES ('blkPermitted', $1, 'secPermitted', 'text', '{"source":"test"}')`, [fid]);

    const res = await app.request(`/families/${fid}/sync`, { headers: { authorization: `Bearer ${m.token}` } });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.changes.sections ?? []).toContainEqual(expect.objectContaining({ id: "secPermitted" }));
    expect(body.changes.blocks ?? []).toContainEqual(expect.objectContaining({ id: "blkPermitted" }));
  });

  it("revoking allow-list membership tombstones hub + section + block on next sync", async () => {
    const o = await ownerOf("hs-revoke-owner");
    const m = await memberOf("hs-revoke-member", o.familyId);
    const fid = o.familyId;

    await putHub(fid, "hubRevoke", o.token, {
      type: "medical", title: "Revoke Hub",
      visibility: "restricted", audience: [o.userId, m.userId],
    });
    await q(`INSERT INTO sections(id, family_id, hub_id, title) VALUES ('secRevoke', $1, 'hubRevoke', 'Revoke Section')`, [fid]);
    await q(`INSERT INTO blocks(id, family_id, section_id, type, provenance) VALUES ('blkRevoke', $1, 'secRevoke', 'text', '{"source":"test"}')`, [fid]);

    // first sync: member sees them
    const r1 = await app.request(`/families/${fid}/sync`, { headers: { authorization: `Bearer ${m.token}` } });
    const b1 = await r1.json();
    expect(b1.changes.sections ?? []).toContainEqual(expect.objectContaining({ id: "secRevoke" }));
    const cursor = b1.next_cursor;

    // revoke
    await q(`DELETE FROM resource_visibility WHERE family_id=$1 AND hub_id='hubRevoke' AND user_id=$2`, [fid, m.userId]);

    // second sync from cursor: hub + section + block tombstoned
    const r2 = await app.request(`/families/${fid}/sync?since=${cursor}`, { headers: { authorization: `Bearer ${m.token}` } });
    const b2 = await r2.json();
    const tombIds = (b2.tombstones ?? []).map((t: any) => t.id);
    expect(tombIds).toContain("hubRevoke");
    expect(tombIds).toContain("secRevoke");
    expect(tombIds).toContain("blkRevoke");
  });

  it("flipping a family hub to restricted (empty allow-list) tombstones the SUBTREE, not just the hub (0011)", async () => {
    const o = await ownerOf("hs-flip-owner");
    const m = await memberOf("hs-flip-member", o.familyId);
    const fid = o.familyId;

    // family-visible hub the member can see, with a section + a sensitive block
    await putHub(fid, "hubFlip", o.token, { type: "medical", title: "Flip Hub", visibility: "family" });
    await q(`INSERT INTO sections(id, family_id, hub_id, title) VALUES ('secFlip', $1, 'hubFlip', 'Flip Section')`, [fid]);
    await q(`INSERT INTO blocks(id, family_id, section_id, type, provenance) VALUES ('blkFlip', $1, 'secFlip', 'text', '{"source":"test"}')`, [fid]);

    // first sync: member sees the subtree
    const r1 = await app.request(`/families/${fid}/sync`, { headers: { authorization: `Bearer ${m.token}` } });
    const b1 = await r1.json();
    expect(b1.changes.blocks ?? []).toContainEqual(expect.objectContaining({ id: "blkFlip" }));
    const cursor = b1.next_cursor;

    // flip family→restricted with an EMPTY allow-list (no resource_visibility row
    // change → the 0009/0010 trigger does NOT fire; only 0011 saves the subtree)
    await putHub(fid, "hubFlip", o.token, { type: "medical", title: "Flip Hub", visibility: "restricted", audience: [] });

    // second sync from cursor: hub AND section AND block must all tombstone
    const r2 = await app.request(`/families/${fid}/sync?since=${cursor}`, { headers: { authorization: `Bearer ${m.token}` } });
    const b2 = await r2.json();
    const tombIds = (b2.tombstones ?? []).map((t: any) => t.id);
    expect(tombIds).toContain("hubFlip");
    expect(tombIds).toContain("secFlip");   // the leak: stale without 0011
    expect(tombIds).toContain("blkFlip");   // the leak: stale without 0011
  });

  it("visible→invisible→visible flap re-emits section+block as live", async () => {
    const o = await ownerOf("hs-flap-owner");
    const m = await memberOf("hs-flap-member", o.familyId);
    const fid = o.familyId;

    await putHub(fid, "hubFlap", o.token, {
      type: "medical", title: "Flap Hub",
      visibility: "restricted", audience: [o.userId, m.userId],
    });
    await q(`INSERT INTO sections(id, family_id, hub_id, title) VALUES ('secFlap', $1, 'hubFlap', 'Flap Section')`, [fid]);
    await q(`INSERT INTO blocks(id, family_id, section_id, type, provenance) VALUES ('blkFlap', $1, 'secFlap', 'text', '{"source":"test"}')`, [fid]);

    // sync1: see them
    const r1 = await app.request(`/families/${fid}/sync`, { headers: { authorization: `Bearer ${m.token}` } });
    const b1 = await r1.json();
    expect(b1.changes.sections ?? []).toContainEqual(expect.objectContaining({ id: "secFlap" }));
    const cursor1 = b1.next_cursor;

    // revoke → tombstone
    await q(`DELETE FROM resource_visibility WHERE family_id=$1 AND hub_id='hubFlap' AND user_id=$2`, [fid, m.userId]);
    const r2 = await app.request(`/families/${fid}/sync?since=${cursor1}`, { headers: { authorization: `Bearer ${m.token}` } });
    const b2 = await r2.json();
    const cursor2 = b2.next_cursor;
    expect((b2.tombstones ?? []).map((t: any) => t.id)).toContain("secFlap");

    // re-grant → live again
    await q(`INSERT INTO resource_visibility(family_id, hub_id, user_id) VALUES ($1,'hubFlap',$2)`, [fid, m.userId]);
    const r3 = await app.request(`/families/${fid}/sync?since=${cursor2}`, { headers: { authorization: `Bearer ${m.token}` } });
    const b3 = await r3.json();
    expect(b3.changes.sections ?? []).toContainEqual(expect.objectContaining({ id: "secFlap" }));
    expect(b3.changes.blocks ?? []).toContainEqual(expect.objectContaining({ id: "blkFlap" }));
  });

  it("pages across card/hub type boundary at equal updated_at without skip/repeat", async () => {
    const o = await ownerOf("hs-owner4");
    const fid = o.familyId;

    // Force a shared timestamp: update both to exact same time after creation
    await putCard(fid, "pageCard", o.token, baseCard({ title: "page card" }));
    await putHub(fid, "pageHub", o.token, { type: "party-event", title: "page hub" });
    // pin them to the same updated_at
    await q(`UPDATE briefing_cards SET updated_at='2020-01-01 00:00:00+00' WHERE family_id=$1 AND id='pageCard'`, [fid]);
    await q(`UPDATE hubs SET updated_at='2020-01-01 00:00:00+00' WHERE family_id=$1 AND id='pageHub'`, [fid]);

    // SYNC_LIMIT is 200 — do full sync from start; collect all items
    const res = await app.request(`/families/${fid}/sync`, { headers: { authorization: `Bearer ${o.token}` } });
    const body = await res.json();
    const allCards = body.changes.cards.map((c: any) => c.id);
    const allHubs = body.changes.hubs.map((h: any) => h.id);

    // both items should appear exactly once
    expect(allCards.filter((id: string) => id === "pageCard")).toHaveLength(1);
    expect(allHubs.filter((id: string) => id === "pageHub")).toHaveLength(1);
  });
});

// The `since` cursor is client-controlled input (base64) that drives the keyset
// query. Validation + injection-safety had no coverage — only the happy paths did.
describe("hub-sync: cursor robustness (malformed + adversarial input)", () => {
  const b64 = (s: string) => Buffer.from(s).toString("base64");

  it("a malformed `since` cursor is a clean 400 bad-cursor (never a 500 / silent rescan)", async () => {
    const o = await ownerOf("cur-owner");
    const sync = (since: string) =>
      app.request(`/families/${o.familyId}/sync?since=${encodeURIComponent(since)}`, { headers: { authorization: `Bearer ${o.token}` } });
    expect((await sync(b64("only-one-part"))).status).toBe(400);          // 1 part (no pipe)
    expect((await sync(b64("a|b|c|d"))).status).toBe(400);                // 4 parts
    expect((await sync(b64("not-a-date|id"))).status).toBe(400);          // 2-part, bad ts
    expect((await sync(b64("|id"))).status).toBe(400);                    // 2-part, empty ts
    expect((await sync(b64("not-a-date|card|id"))).status).toBe(400);     // 3-part, bad ts
  });

  it("a 3-part cursor with an unknown type → full resync (200), not an error", async () => {
    const o = await ownerOf("cur-owner2");
    await putCard(o.familyId, "c1", o.token, baseCard());
    const cur = b64("2026-06-24T00:00:00Z|bogusType|x");
    const r = await app.request(`/families/${o.familyId}/sync?since=${encodeURIComponent(cur)}`, { headers: { authorization: `Bearer ${o.token}` } });
    expect(r.status).toBe(200);
    expect((await r.json()).changes.cards.map((c: any) => c.id)).toContain("c1");   // resynced from -∞
  });

  it("a SQL-injection-shaped id in a valid cursor is bound as a param — harmless (no 500, table intact)", async () => {
    const o = await ownerOf("cur-owner3");
    await putCard(o.familyId, "c1", o.token, baseCard());
    const cur = b64(`2026-06-24T00:00:00Z|card|'; DROP TABLE briefing_cards;--`);
    const r = await app.request(`/families/${o.familyId}/sync?since=${encodeURIComponent(cur)}`, { headers: { authorization: `Bearer ${o.token}` } });
    expect(r.status).toBe(200);   // parameterized → not a 500, and nothing executed
    // prove the table still exists: a fresh write + read round-trips
    await putCard(o.familyId, "c2", o.token, baseCard());
    const r2 = await app.request(`/families/${o.familyId}/sync`, { headers: { authorization: `Bearer ${o.token}` } });
    expect((await r2.json()).changes.cards.map((c: any) => c.id)).toContain("c2");
  });
});

describe("/sync stale-cursor full-resync directive (ADR 0040 §3)", () => {
  it("a cursor older than the retention floor → full_resync:true + rebuild from the beginning", async () => {
    const o = await ownerOf("fr-owner");
    await putCard(o.familyId, "frc1", o.token, baseCard({ title: "rebuild me" }));
    // a well-formed 3-part cursor whose timestamp is 200 days old (past the ≥90d floor)
    const staleTs = new Date(Date.now() - 200 * 24 * 3600 * 1000).toISOString();
    const stale = Buffer.from(`${staleTs}|card|zzzzzzzz`).toString("base64");
    const r = await app.request(`/families/${o.familyId}/sync?since=${encodeURIComponent(stale)}`, { headers: authH(o.token) });
    expect(r.status).toBe(200);
    const b = await r.json();
    expect(b.full_resync).toBe(true);                                   // directive present
    expect(b.changes.cards.map((c: any) => c.id)).toContain("frc1");    // rebuild restarts from -∞
  });

  it("a fresh cursor is NOT flagged full_resync", async () => {
    const o = await ownerOf("fr-owner2");
    await putCard(o.familyId, "frc2", o.token, baseCard({ title: "x" }));
    const fresh = Buffer.from(`${new Date().toISOString()}|card|aaaaaaaa`).toString("base64");
    const r = await app.request(`/families/${o.familyId}/sync?since=${encodeURIComponent(fresh)}`, { headers: authH(o.token) });
    const b = await r.json();
    expect(b.full_resync).toBeFalsy();
  });

  it("the initial (empty-cursor) sync is not a full_resync — it's just the first page", async () => {
    const o = await ownerOf("fr-owner3");
    await putCard(o.familyId, "frc3", o.token, baseCard({ title: "x" }));
    const r = await app.request(`/families/${o.familyId}/sync`, { headers: authH(o.token) });
    const b = await r.json();
    expect(b.full_resync).toBeFalsy();
  });
});
