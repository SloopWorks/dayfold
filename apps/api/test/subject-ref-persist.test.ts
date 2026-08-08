// ADR 0064 — subject_ref is stamped by the SERVER on every content write, never accepted
// from the author. It is the suppression key, so an author-supplied value would let a write
// choose its own key and step around a mute.
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { generateKeyPair, exportJWK } from "jose";
import { applyAllMigrations } from "./_migrations.ts";
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.AUTH_ISS = "https://fad.test/auth"; process.env.AUTH_AUD = "fad-api-test";
process.env.ENABLE_DEV_AUTH = "1"; process.env.DEV_AUTH_SECRET = "dev"; delete process.env.VERCEL_ENV;
const kp = await generateKeyPair("EdDSA", { crv: "Ed25519", extractable: true });
const priv = await exportJWK(kp.privateKey); priv.kid = "k1"; priv.alg = "EdDSA";
process.env.AUTH_SIGNING_KEY = JSON.stringify(priv);
const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");

beforeAll(async () => { await applyAllMigrations(q); });
afterAll(async () => { await pool.end(); });

const dev = { "content-type": "application/json", authorization: "Bearer dev" };
async function ownerOf(uid: string) {
  const t = (await (await app.request("/auth/dev-token", { method: "POST", headers: dev, body: JSON.stringify({ provider: "dev", provider_uid: uid }) })).json()).access;
  const fam = await (await app.request("/families", { method: "POST", headers: { ...dev, authorization: `Bearer ${t}` }, body: JSON.stringify({ name: uid }) })).json();
  return { token: t as string, familyId: fam.familyId as string };
}
const authH = (tok: string) => ({ "content-type": "application/json", authorization: `Bearer ${tok}` });
const put = (fid: string, path: string, tok: string, body: any) =>
  app.request(`/families/${fid}/${path}`, { method: "PUT", headers: authH(tok), body: JSON.stringify(body) });

describe("subject_ref is stamped on write (ADR 0064)", () => {
  it("stamps a card subject_ref from its id", async () => {
    const o = await ownerOf("sr-card");
    expect((await put(o.familyId, "cards/c_sr1", o.token, { kind: "info", title: "Rain at soccer", body_md: "70% at 5 PM" })).status).toBe(200);
    const row = await q(`SELECT subject_ref FROM briefing_cards WHERE family_id=$1 AND id=$2`, [o.familyId, "c_sr1"]);
    expect(row.rows[0].subject_ref).toBe("card:c_sr1");
  });

  it("stamps a block subject_ref from its hub/section/block path", async () => {
    const o = await ownerOf("sr-block");
    await put(o.familyId, "hubs/h_sr1", o.token, { type: "party-event", title: "Party" });
    await put(o.familyId, "sections/s_sr1", o.token, { hubId: "h_sr1", title: "Plan", ord: 0 });
    expect((await put(o.familyId, "blocks/b_sr1", o.token, { sectionId: "s_sr1", type: "text", body_md: "hello", ord: 0 })).status).toBe(200);
    const row = await q(`SELECT subject_ref FROM blocks WHERE family_id=$1 AND id=$2`, [o.familyId, "b_sr1"]);
    expect(row.rows[0].subject_ref).toBe("hub:h_sr1/section:s_sr1/block:b_sr1");
  });

  // The key is the server's to assign. If an author could set it, a routine could mint a
  // card whose key does not match the rule the family wrote — suppression by consent.
  it("ignores an author-supplied subject_ref", async () => {
    const o = await ownerOf("sr-spoof");
    await put(o.familyId, "cards/c_sr2", o.token, { kind: "info", title: "x", subject_ref: "card:something_else" });
    const row = await q(`SELECT subject_ref FROM briefing_cards WHERE family_id=$1 AND id=$2`, [o.familyId, "c_sr2"]);
    expect(row.rows[0].subject_ref).toBe("card:c_sr2");
  });

  // An id carrying a reserved marker would make the ref decompose two ways (see
  // subject-ref.test.ts). The route's own RESOURCE_ID guard already rejects it here; the
  // builder's throw is the second line of defense. Pinned so a later loosening of
  // RESOURCE_ID cannot quietly let an ambiguous key through.
  it("refuses an id that would build an ambiguous ref", async () => {
    const o = await ownerOf("sr-ambig");
    const res = await put(o.familyId, "cards/c%2Fblock%3Ax", o.token, { kind: "info", title: "x" });
    expect(res.status).toBe(422);
  });

  it("re-stamps on a resurrect-by-PUT so a mute set before the delete still matches", async () => {
    const o = await ownerOf("sr-resurrect");
    await put(o.familyId, "cards/c_sr3", o.token, { kind: "info", title: "x" });
    await app.request(`/families/${o.familyId}/cards/c_sr3`, { method: "DELETE", headers: authH(o.token) });
    await put(o.familyId, "cards/c_sr3", o.token, { kind: "info", title: "x again" });
    const row = await q(`SELECT subject_ref, deleted_at FROM briefing_cards WHERE family_id=$1 AND id=$2`, [o.familyId, "c_sr3"]);
    expect(row.rows[0].subject_ref).toBe("card:c_sr3");
    expect(row.rows[0].deleted_at).toBeNull();
  });

  it("backfills every pre-existing row, tombstoned ones included", async () => {
    const orphans = await q(
      `SELECT count(*)::int AS n FROM briefing_cards WHERE subject_ref IS NULL`, []);
    expect(orphans.rows[0].n).toBe(0);
    const blockOrphans = await q(
      `SELECT count(*)::int AS n FROM blocks WHERE subject_ref IS NULL`, []);
    expect(blockOrphans.rows[0].n).toBe(0);
  });
});
