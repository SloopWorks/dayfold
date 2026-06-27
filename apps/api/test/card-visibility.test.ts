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
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0006_typed_content.sql","0007_related.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql"])
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
// A second adult, made an active member of an existing family.
async function memberOf(uid: string, familyId: string) {
  const t = (await (await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: uid }) })).json()).access;
  const me = await (await app.request("/auth/me", { headers: { authorization: `Bearer ${t}` } })).json();
  await q(`INSERT INTO memberships(user_id,family_id,role,status) VALUES ($1,$2,'adult','active')`, [me.user_id, familyId]);
  return { token: t as string, userId: me.user_id as string };
}
const put = (fid: string, id: string, tok: string, body: any) =>
  app.request(`/families/${fid}/cards/${id}`, { method: "PUT", headers: { "content-type": "application/json", authorization: `Bearer ${tok}` }, body: JSON.stringify(body) });
const getJson = async (path: string, tok: string) => (await app.request(path, { headers: { authorization: `Bearer ${tok}` } })).json();
const baseCard = (over: any = {}) => ({ kind: "info", title: "x", provenance: { source: "cli", at: "2026-06-18T10:00:00Z" }, ...over });

describe("card per-member visibility (ADR 0030)", () => {
  it("family card visible to all members; restricted card only to its audience", async () => {
    const o = await ownerOf("vis-owner");
    const b = await memberOf("vis-bob", o.familyId);
    // a family-visible card + a restricted card whose audience is the owner only
    expect((await put(o.familyId, "fam1", o.token, baseCard({ title: "shared" }))).status).toBe(200);
    expect((await put(o.familyId, "sec1", o.token, baseCard({ title: "secret", visibility: "restricted", audience: [o.userId] }))).status).toBe(200);

    const ownerCards = await getJson(`/families/${o.familyId}/cards`, o.token);
    expect(ownerCards.map((c: any) => c.id).sort()).toEqual(["fam1", "sec1"]);
    const bobCards = await getJson(`/families/${o.familyId}/cards`, b.token);
    expect(bobCards.map((c: any) => c.id)).toEqual(["fam1"]);          // sec1 omitted, not 403
  });

  it("restricted card the caller can't see is a TOMBSTONE in /sync (not a leak, not stale)", async () => {
    const o = await ownerOf("vis-owner2");
    const b = await memberOf("vis-carol", o.familyId);
    await put(o.familyId, "famX", o.token, baseCard({ title: "shared" }));
    await put(o.familyId, "secX", o.token, baseCard({ visibility: "restricted", audience: [o.userId] }));

    const sync = await getJson(`/families/${o.familyId}/sync`, b.token);
    expect(sync.changes.cards.map((c: any) => c.id)).toEqual(["famX"]);      // bob sees the shared card
    expect(sync.tombstones.some((t: any) => t.id === "secX")).toBe(true);    // restricted → tombstone for bob
    expect(sync.next_cursor).toBeTruthy();                                   // cursor advanced over both rows
  });

  it("audience membership is honored — a card restricted to bob is visible to bob, not to a non-audience member", async () => {
    const o = await ownerOf("vis-owner3");
    const b = await memberOf("vis-dan", o.familyId);
    const e = await memberOf("vis-eve", o.familyId);
    await put(o.familyId, "forBob", o.token, baseCard({ visibility: "restricted", audience: [b.userId] }));

    expect((await getJson(`/families/${o.familyId}/cards`, b.token)).map((c: any) => c.id)).toContain("forBob");
    expect((await getJson(`/families/${o.familyId}/cards`, e.token)).map((c: any) => c.id)).not.toContain("forBob");
    // author/owner (not in audience) also does not see it — owner is NOT auto-permitted (ADR 0030 option A)
    expect((await getJson(`/families/${o.familyId}/cards`, o.token)).map((c: any) => c.id)).not.toContain("forBob");
  });

  // "Restricted to nobody" must FAIL CLOSED: a restricted card whose audience is empty
  // OR omitted (the route normalizes both → []) is visible to NO ONE — not the author,
  // not any member. Guards the data boundary so a mis-authored restricted card can never
  // leak by defaulting to visible. The author-not-auto-permitted rule (ADR 0030 option A)
  // means even the owner who wrote it sees only a tombstone.
  it("restricted card with EMPTY or MISSING audience is invisible to everyone incl. the author (fail-closed)", async () => {
    const o = await ownerOf("vis-owner4");
    const b = await memberOf("vis-frank", o.familyId);
    expect((await put(o.familyId, "empty", o.token, baseCard({ visibility: "restricted", audience: [] }))).status).toBe(200);
    expect((await put(o.familyId, "noaud", o.token, baseCard({ visibility: "restricted" }))).status).toBe(200);   // audience omitted
    await put(o.familyId, "shared4", o.token, baseCard({ title: "shared" }));   // a family card → proves filtering, not emptiness

    // /cards — neither restricted-to-nobody card reaches the author or the member
    expect((await getJson(`/families/${o.familyId}/cards`, o.token)).map((c: any) => c.id).sort()).toEqual(["shared4"]);
    expect((await getJson(`/families/${o.familyId}/cards`, b.token)).map((c: any) => c.id).sort()).toEqual(["shared4"]);

    // /sync — both are tombstones (not leaked, not stale) for the author AND the member
    for (const who of [o, b]) {
      const sync = await getJson(`/families/${o.familyId}/sync`, who.token);
      const visible = sync.changes.cards.map((c: any) => c.id);
      expect(visible).toContain("shared4");
      expect(visible).not.toContain("empty");
      expect(visible).not.toContain("noaud");
      const tombs = sync.tombstones.map((t: any) => t.id);
      expect(tombs).toContain("empty");
      expect(tombs).toContain("noaud");
    }
  });
});
