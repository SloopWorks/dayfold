// ADR 0064 — response rows ride the ADR 0040 merged cursor. The per-member visibility rule
// is the interesting part: a personal rule is its owner's alone, and every other member gets
// a TOMBSTONE rather than an omission, so the cursor still advances and a rule that changed
// hands leaves the other member's cache.
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
const putResp = (fid: string, id: string, tok: string, body: any) =>
  app.request(`/families/${fid}/responses/${id}`, { method: "PUT", headers: authH(tok), body: JSON.stringify(body) });
const sync = async (fid: string, tok: string, since?: string) => {
  const url = `/families/${fid}/sync${since ? `?since=${encodeURIComponent(since)}` : ""}`;
  const r = await app.request(url, { headers: { authorization: `Bearer ${tok}` } });
  return { status: r.status, body: r.status === 200 ? await r.json() : null };
};

const mute = (over: any = {}) => ({
  kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
  audience_scope: "family", label: "Weather in Now", ...over,
});
const done = (subjectRef: string) => ({
  kind: "done", subject_ref: subjectRef, match_scope: "subject",
  audience_scope: "family", label: "Restricted task",
});

describe("/sync emits response rows (ADR 0064)", () => {
  it("emits a family rule to every member", async () => {
    const o = await ownerOf("sync-1");
    const m = await memberOf("sync-1-b", o.familyId);
    await putResp(o.familyId, "r_fam", o.token, mute());
    const mine = await sync(o.familyId, o.token);
    const theirs = await sync(o.familyId, m.token);
    expect(mine.body.changes.responses.map((r: any) => r.id)).toContain("r_fam");
    expect(theirs.body.changes.responses.map((r: any) => r.id)).toContain("r_fam");
  });

  it("emits a personal rule to its owner and a tombstone to everyone else", async () => {
    const o = await ownerOf("sync-2");
    const m = await memberOf("sync-2-b", o.familyId);
    await putResp(o.familyId, "r_me", o.token, mute({ audience_scope: "personal", subject_ref: "kind:traffic" }));
    const mine = await sync(o.familyId, o.token);
    const theirs = await sync(o.familyId, m.token);
    expect(mine.body.changes.responses.map((r: any) => r.id)).toContain("r_me");
    expect(theirs.body.changes.responses.map((r: any) => r.id)).not.toContain("r_me");
    expect(theirs.body.tombstones).toContainEqual({ type: "response", id: "r_me" });
  });

  it("emits a tombstone to everyone once a rule is removed", async () => {
    const o = await ownerOf("sync-3");
    await putResp(o.familyId, "r_gone", o.token, mute());
    await app.request(`/families/${o.familyId}/responses/r_gone`, { method: "DELETE", headers: authH(o.token) });
    const after = await sync(o.familyId, o.token);
    expect(after.body.tombstones).toContainEqual({ type: "response", id: "r_gone" });
  });

  it("accepts a 3-part cursor whose type token is `response`", async () => {
    const o = await ownerOf("sync-4");
    await putResp(o.familyId, "r_c", o.token, mute());
    const cursor = Buffer.from(`${new Date().toISOString()}|response|r_c`).toString("base64");
    expect((await sync(o.familyId, o.token, cursor)).status).toBe(200);   // not 400 bad-cursor
  });

  // The cursor must advance past another member's personal rules. If they were omitted
  // instead of tombstoned, a page consisting only of such rules would look empty and the
  // client would resume from the same place forever.
  it("advances the cursor across a page of another member's personal rules", async () => {
    const o = await ownerOf("sync-5");
    const m = await memberOf("sync-5-b", o.familyId);
    for (let i = 0; i < 3; i++) {
      await putResp(o.familyId, `r_p${i}`, o.token, mute({ audience_scope: "personal", subject_ref: `kind:k${i}` }));
    }
    const first = await sync(o.familyId, m.token);
    expect(first.body.next_cursor).toBeTruthy();
    const second = await sync(o.familyId, m.token, first.body.next_cursor);
    expect(second.status).toBe(200);
    expect(second.body.tombstones).toEqual([]);      // fully drained, not stuck re-serving
    expect(second.body.changes.responses).toEqual([]);
  });

  it("a resumed cursor picks up a rule written after it", async () => {
    const o = await ownerOf("sync-6");
    const first = await sync(o.familyId, o.token);
    await putResp(o.familyId, "r_late", o.token, mute());
    const second = await sync(o.familyId, o.token, first.body.next_cursor);
    expect(second.body.changes.responses.map((r: any) => r.id)).toContain("r_late");
  });

  it("inherits a restricted Hub ACL and re-emits Done after access is granted", async () => {
    const o = await ownerOf("sync-restricted-response-owner");
    const m = await memberOf("sync-restricted-response-member", o.familyId);
    await q(`INSERT INTO hubs(id,family_id,type,title,visibility,created_by) VALUES ('private_done_hub',$1,'other','Private','restricted',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO sections(id,family_id,hub_id,title) VALUES ('private_done_section',$1,'private_done_hub','Tasks')`, [o.familyId]);
    const subject = "hub:private_done_hub/section:private_done_section/block:private_done_block";
    await q(`INSERT INTO blocks(id,family_id,section_id,type,body_md,provenance,subject_ref) VALUES ('private_done_block',$1,'private_done_section','text','Restricted task','{}',$2)`, [o.familyId, subject]);
    expect((await putResp(o.familyId, "private_done_response", o.token, done(subject))).status).toBe(200);

    const hidden = await sync(o.familyId, m.token);
    expect(hidden.body.changes.responses.map((r: any) => r.id)).not.toContain("private_done_response");
    expect(hidden.body.tombstones).toContainEqual({ type: "response", id: "private_done_response" });

    const getHidden = await (await app.request(`/families/${o.familyId}/responses`, {
      headers: { authorization: `Bearer ${m.token}` },
    })).json();
    expect(getHidden.responses.map((r: any) => r.id)).not.toContain("private_done_response");

    const grant = await app.request(
      `/families/${o.familyId}/hubs/private_done_hub/participants/${m.userId}`,
      { method: "PUT", headers: authH(o.token), body: JSON.stringify({ role: "viewer" }) },
    );
    expect(grant.status).toBe(200);

    const visible = await sync(o.familyId, m.token, hidden.body.next_cursor);
    expect(visible.body.changes.responses.map((r: any) => r.id)).toContain("private_done_response");
  });

  it("tombstones an owner's personal concrete response when their Hub access is revoked", async () => {
    const o = await ownerOf("sync-personal-concrete-owner");
    const m = await memberOf("sync-personal-concrete-member", o.familyId);
    await q(`INSERT INTO hubs(id,family_id,type,title,visibility,created_by) VALUES ('personal_acl_hub',$1,'other','Private','restricted',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES ($1,'personal_acl_hub',$2,'viewer')`, [o.familyId, m.userId]);
    const subject = "hub:personal_acl_hub";
    expect((await putResp(o.familyId, "personal_acl_response", m.token, mute({
      subject_ref: subject, match_scope: "subject", audience_scope: "personal",
    }))).status).toBe(200);
    const before = await sync(o.familyId, m.token);
    expect(before.body.changes.responses.map((r: any) => r.id)).toContain("personal_acl_response");

    const removed = await app.request(
      `/families/${o.familyId}/hubs/personal_acl_hub/participants/${m.userId}`,
      { method: "DELETE", headers: authH(o.token) },
    );
    expect(removed.status).toBe(204);
    const after = await sync(o.familyId, m.token, before.body.next_cursor);
    expect(after.body.tombstones).toContainEqual({ type: "response", id: "personal_acl_response" });
    expect(after.body.changes.responses.map((r: any) => r.id)).not.toContain("personal_acl_response");
  });

  it("re-emits a concrete card response on audience grant and tombstones it on revoke", async () => {
    const o = await ownerOf("sync-card-response-owner");
    const m = await memberOf("sync-card-response-member", o.familyId);
    const repo = await import("../src/repo.ts");
    await repo.upsertCard(o.familyId, "response_acl_card", {
      title: "Private card", provenance: {}, visibility: "restricted", audience: [o.userId, m.userId],
    });
    expect((await putResp(o.familyId, "response_acl_card_rule", o.token, mute({
      subject_ref: "card:response_acl_card", match_scope: "subject", audience_scope: "family",
    }))).status).toBe(200);
    const visible = await sync(o.familyId, m.token);
    expect(visible.body.changes.responses.map((r: any) => r.id)).toContain("response_acl_card_rule");

    await repo.upsertCard(o.familyId, "response_acl_card", {
      title: "Private card", provenance: {}, visibility: "restricted", audience: [o.userId],
    });
    const hidden = await sync(o.familyId, m.token, visible.body.next_cursor);
    expect(hidden.body.tombstones).toContainEqual({ type: "response", id: "response_acl_card_rule" });

    await repo.upsertCard(o.familyId, "response_acl_card", {
      title: "Private card", provenance: {}, visibility: "restricted", audience: [o.userId, m.userId],
    });
    const granted = await sync(o.familyId, m.token, hidden.body.next_cursor);
    expect(granted.body.changes.responses.map((r: any) => r.id)).toContain("response_acl_card_rule");
  });

  it("emits a response tombstone after its Hub is deleted", async () => {
    const o = await ownerOf("sync-response-hub-delete");
    await q(`INSERT INTO hubs(id,family_id,type,title,created_by) VALUES ('gone_response_hub',$1,'other','Gone',$2)`, [o.familyId, o.userId]);
    expect((await putResp(o.familyId, "gone_hub_response", o.token, mute({
      subject_ref: "hub:gone_response_hub", match_scope: "subject",
    }))).status).toBe(200);
    const before = await sync(o.familyId, o.token);
    expect(before.body.changes.responses.map((r: any) => r.id)).toContain("gone_hub_response");

    const removed = await app.request(`/families/${o.familyId}/hubs/gone_response_hub`, {
      method: "DELETE", headers: authH(o.token),
    });
    expect(removed.status).toBe(204);
    const after = await sync(o.familyId, o.token, before.body.next_cursor);
    expect(after.body.tombstones).toContainEqual({ type: "response", id: "gone_hub_response" });
  });

  it("emits a response tombstone after its section moves to another Hub", async () => {
    const o = await ownerOf("sync-response-section-move");
    await q(`INSERT INTO hubs(id,family_id,type,title,created_by) VALUES ('section_old_hub',$1,'other','Old',$2),('section_new_hub',$1,'other','New',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO sections(id,family_id,hub_id,title) VALUES ('moving_section',$1,'section_old_hub','Tasks')`, [o.familyId]);
    expect((await putResp(o.familyId, "moving_section_response", o.token, mute({
      subject_ref: "hub:section_old_hub/section:moving_section", match_scope: "subject",
    }))).status).toBe(200);
    const before = await sync(o.familyId, o.token);

    const moved = await app.request(`/families/${o.familyId}/sections/moving_section`, {
      method: "PUT", headers: authH(o.token),
      body: JSON.stringify({ hubId: "section_new_hub", title: "Tasks", ord: 0 }),
    });
    expect(moved.status).toBe(200);
    const after = await sync(o.familyId, o.token, before.body.next_cursor);
    expect(after.body.tombstones).toContainEqual({ type: "response", id: "moving_section_response" });
  });

  it("re-keys every child block when its section moves to another Hub", async () => {
    const o = await ownerOf("sync-section-child-rekey");
    await q(`INSERT INTO hubs(id,family_id,type,title,created_by) VALUES ('rekey_old_hub',$1,'other','Old',$2),('rekey_new_hub',$1,'other','New',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO sections(id,family_id,hub_id,title) VALUES ('rekey_section',$1,'rekey_old_hub','Tasks')`, [o.familyId]);
    await q(`INSERT INTO blocks(id,family_id,section_id,type,body_md,provenance,subject_ref,version) VALUES ('rekey_block',$1,'rekey_section','text','Move me','{}','hub:rekey_old_hub/section:rekey_section/block:rekey_block',3)`, [o.familyId]);

    const moved = await app.request(`/families/${o.familyId}/sections/rekey_section`, {
      method: "PUT", headers: authH(o.token),
      body: JSON.stringify({ hubId: "rekey_new_hub", title: "Tasks", ord: 0 }),
    });
    expect(moved.status).toBe(200);
    const block = (await q(`SELECT subject_ref,version FROM blocks WHERE family_id=$1 AND id='rekey_block'`, [o.familyId])).rows[0];
    expect(block.subject_ref).toBe("hub:rekey_new_hub/section:rekey_section/block:rekey_block");
    expect(Number(block.version)).toBe(4);
    expect((await putResp(o.familyId, "rekey_response", o.token, mute({
      subject_ref: block.subject_ref, match_scope: "subject",
    }))).status).toBe(200);
  });

  it("emits a response tombstone after its block moves to another section", async () => {
    const o = await ownerOf("sync-response-block-move");
    await q(`INSERT INTO hubs(id,family_id,type,title,created_by) VALUES ('block_move_hub',$1,'other','Move',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO sections(id,family_id,hub_id,title) VALUES ('block_old_section',$1,'block_move_hub','Old'),('block_new_section',$1,'block_move_hub','New')`, [o.familyId]);
    const oldSubject = "hub:block_move_hub/section:block_old_section/block:moving_block";
    await q(`INSERT INTO blocks(id,family_id,section_id,type,body_md,provenance,subject_ref,created_by) VALUES ('moving_block',$1,'block_old_section','text','Move me','{}',$2,$3)`, [o.familyId, oldSubject, o.userId]);
    expect((await putResp(o.familyId, "moving_block_response", o.token, mute({
      subject_ref: oldSubject, match_scope: "subject",
    }))).status).toBe(200);
    const before = await sync(o.familyId, o.token);

    const moved = await app.request(`/families/${o.familyId}/blocks/moving_block`, {
      method: "PUT", headers: authH(o.token),
      body: JSON.stringify({ sectionId: "block_new_section", type: "text", body_md: "Move me", ord: 0 }),
    });
    expect(moved.status).toBe(200);
    const after = await sync(o.familyId, o.token, before.body.next_cursor);
    expect(after.body.tombstones).toContainEqual({ type: "response", id: "moving_block_response" });
  });
});
