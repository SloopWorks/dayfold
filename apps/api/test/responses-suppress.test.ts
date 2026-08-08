// ADR 0064 — the suppression gate. Family-scoped rules and every done row BLOCK the write;
// a personal rule does NOT block, it removes its owner from the card's audience so the
// routine still mints for the rest of the family ("your family's feed is unchanged").
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { generateKeyPair, exportJWK } from "jose";
import { applyAllMigrations } from "./_migrations.ts";
import type { ContentResponseRow } from "../src/content/responses.ts";
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.AUTH_ISS = "https://fad.test/auth"; process.env.AUTH_AUD = "fad-api-test";
process.env.ENABLE_DEV_AUTH = "1"; process.env.DEV_AUTH_SECRET = "dev"; delete process.env.VERCEL_ENV;
const kp = await generateKeyPair("EdDSA", { crv: "Ed25519", extractable: true });
const priv = await exportJWK(kp.privateKey); priv.kid = "k1"; priv.alg = "EdDSA";
process.env.AUTH_SIGNING_KEY = JSON.stringify(priv);
const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");
// Dynamic: write-guard pulls db.ts, whose pool is built at module load. A static import
// hoists above the env assignments above and connects to the wrong database.
const { suppressedBy } = await import("../src/content/write-guard.ts");

beforeAll(async () => { await applyAllMigrations(q); });
afterAll(async () => { await pool.end(); });

const rule = (over: Partial<ContentResponseRow>): ContentResponseRow => ({
  id: "r", kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
  audience_scope: "family", user_id: null, created_by: "u1",
  label: "l", sublabel: null, note: null, version: 1, ...over,
}) as ContentResponseRow;

describe("suppressedBy (unit)", () => {
  const subject = { subjectRef: "card:c_1", kind: "weather", source: "mb" };

  it("blocks on a matching family mute", () => {
    expect(suppressedBy([rule({})], subject)).toEqual({ blocked: true, excludeUserIds: [] });
  });

  it("excludes the owner on a matching personal mute, and does not block", () => {
    const r = rule({ audience_scope: "personal", user_id: "u_mom" });
    expect(suppressedBy([r], subject)).toEqual({ blocked: false, excludeUserIds: ["u_mom"] });
  });

  it("collects every matching personal owner", () => {
    const rs = [
      rule({ audience_scope: "personal", user_id: "u_mom" }),
      rule({ audience_scope: "personal", user_id: "u_dad", subject_ref: "source:mb", match_scope: "source" }),
    ];
    expect(suppressedBy(rs, subject).excludeUserIds.sort()).toEqual(["u_dad", "u_mom"]);
  });

  it("blocks on a done row regardless of audience shape", () => {
    expect(suppressedBy([rule({ kind: "done", subject_ref: "card:c_1", match_scope: "subject" })], subject).blocked).toBe(true);
  });

  it("ignores non-matching rules", () => {
    expect(suppressedBy([rule({ subject_ref: "kind:traffic" })], subject))
      .toEqual({ blocked: false, excludeUserIds: [] });
  });

  it("an empty rule list suppresses nothing", () => {
    expect(suppressedBy([], subject)).toEqual({ blocked: false, excludeUserIds: [] });
  });
});

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
const put = (fid: string, path: string, tok: string, body: any) =>
  app.request(`/families/${fid}/${path}`, { method: "PUT", headers: authH(tok), body: JSON.stringify(body) });

describe("suppression end-to-end", () => {
  it("a family mute makes the next authored write 409", async () => {
    const o = await ownerOf("sup-1");
    await put(o.familyId, "responses/r_mute", o.token, {
      kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "family", label: "Weather cards",
    });
    const res = await put(o.familyId, "cards/c_rain", o.token, { kind: "weather", title: "Rain at soccer" });
    expect(res.status).toBe(409);
    const rows = await q(`SELECT 1 FROM briefing_cards WHERE family_id=$1 AND id=$2`, [o.familyId, "c_rain"]);
    expect(rows.rowCount).toBe(0);
  });

  it("an unrelated kind still writes", async () => {
    const o = await ownerOf("sup-2");
    await put(o.familyId, "responses/r_mute", o.token, {
      kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "family", label: "Weather cards",
    });
    expect((await put(o.familyId, "cards/c_ok", o.token, { kind: "info", title: "Fine" })).status).toBe(200);
  });

  it("a personal mute strips only that member from audience[]", async () => {
    const o = await ownerOf("sup-3");
    const m = await memberOf("sup-3-b", o.familyId);
    await put(o.familyId, "responses/r_p", o.token, {
      kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "personal", label: "Weather cards",
    });
    const res = await put(o.familyId, "cards/c_rain2", o.token, {
      kind: "weather", title: "Rain", visibility: "restricted", audience: [o.userId, m.userId],
    });
    expect(res.status).toBe(200);
    const row = await q(`SELECT audience FROM briefing_cards WHERE family_id=$1 AND id=$2`, [o.familyId, "c_rain2"]);
    expect(row.rows[0].audience).toEqual([m.userId]);   // the muting owner is gone, the other member stays
  });

  // Stripping everyone leaves nobody to write for. Writing it anyway would create a card
  // no member can see — invisible content that still consumes sync + storage.
  it("rejects when stripping empties the audience", async () => {
    const o = await ownerOf("sup-4");
    await put(o.familyId, "responses/r_p", o.token, {
      kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "personal", label: "Weather cards",
    });
    const res = await put(o.familyId, "cards/c_rain3", o.token, {
      kind: "weather", title: "Rain", visibility: "restricted", audience: [o.userId],
    });
    expect(res.status).toBe(409);
  });

  it("a removed rule stops suppressing", async () => {
    const o = await ownerOf("sup-5");
    await put(o.familyId, "responses/r_m", o.token, {
      kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "family", label: "Weather cards",
    });
    expect((await put(o.familyId, "cards/c_r", o.token, { kind: "weather", title: "Rain" })).status).toBe(409);
    await app.request(`/families/${o.familyId}/responses/r_m`, { method: "DELETE", headers: authH(o.token) });
    expect((await put(o.familyId, "cards/c_r", o.token, { kind: "weather", title: "Rain" })).status).toBe(200);
  });

  it("marking done tombstones the subject and blocks the next write to it", async () => {
    const o = await ownerOf("sup-6");
    await put(o.familyId, "cards/c_task", o.token, { kind: "action", title: "Verify emergency contact" });
    await put(o.familyId, "responses/r_done", o.token, {
      kind: "done", subject_ref: "card:c_task", match_scope: "subject",
      audience_scope: "family", label: "Verify emergency contact", note: "Confirmed",
    });
    const row = await q(`SELECT deleted_at FROM briefing_cards WHERE family_id=$1 AND id=$2`, [o.familyId, "c_task"]);
    expect(row.rows[0].deleted_at).not.toBeNull();          // leaves every member's Now
    const res = await put(o.familyId, "cards/c_task", o.token, { kind: "action", title: "Verify emergency contact" });
    expect(res.status).toBe(409);                            // the next run does not re-mint it
  });

  it("a subject mute blocks a block write to that node", async () => {
    const o = await ownerOf("sup-7");
    await put(o.familyId, "hubs/h1", o.token, { type: "party-event", title: "Party" });
    await put(o.familyId, "sections/s1", o.token, { hubId: "h1", title: "Plan", ord: 0 });
    await put(o.familyId, "responses/r_b", o.token, {
      kind: "mute", subject_ref: "hub:h1/section:s1/block:b1", match_scope: "subject",
      audience_scope: "family", label: "That block",
    });
    const res = await put(o.familyId, "blocks/b1", o.token, { sectionId: "s1", type: "text", body_md: "nope", ord: 0 });
    expect(res.status).toBe(409);
  });
});
