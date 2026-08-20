// ADR 0064 — the response endpoints. The security-shaped assertions here are the point:
// ownership is assigned from the token (never the body), the identity columns are immutable
// after creation, and a personal rule belongs to exactly one member.
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
const responseRepo = await import("../src/content/responses.ts");

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
const putResp = (fid: string, id: string, tok: string, body: any, opId?: string) =>
  app.request(`/families/${fid}/responses/${id}`, {
    method: "PUT",
    headers: { ...authH(tok), ...(opId ? { "idempotency-key": opId } : {}) },
    body: JSON.stringify(body),
  });
const delResp = (fid: string, id: string, tok: string, opId?: string) =>
  app.request(`/families/${fid}/responses/${id}`, {
    method: "DELETE",
    headers: { ...authH(tok), ...(opId ? { "idempotency-key": opId } : {}) },
  });

const mute = (over: any = {}) => ({
  kind: "mute", subject_ref: "kind:weather", match_scope: "kind",
  audience_scope: "personal", label: "Weather cards", sublabel: "From Morning briefing", ...over,
});
const done = (subjectRef: string, over: any = {}) => ({
  kind: "done", subject_ref: subjectRef, match_scope: "subject",
  audience_scope: "family", label: "Call the caterer", ...over,
});

describe("response endpoints (ADR 0064)", () => {
  it("creates a personal mute owned by the caller", async () => {
    const o = await ownerOf("resp-1");
    const res = await putResp(o.familyId, "r_1", o.token, mute());
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.audience_scope).toBe("personal");
    expect(body.user_id).toBe(o.userId);
    expect(body.created_by).toBe(o.userId);
    expect(body.version).toBe("1");
  });

  // Ownership comes from the token. If a body could set it, one member could write rules
  // that suppress content for another member.
  it("ignores a client-supplied user_id", async () => {
    const o = await ownerOf("resp-2");
    const res = await putResp(o.familyId, "r_2", o.token, mute({ user_id: "someone_else" }));
    expect((await res.json()).user_id).toBe(o.userId);
  });

  it("creates a family-wide mute with no owner, still attributed", async () => {
    const o = await ownerOf("resp-3");
    const res = await putResp(o.familyId, "r_3", o.token, mute({ audience_scope: "family" }));
    const body = await res.json();
    expect(body.user_id).toBeNull();
    expect(body.created_by).toBe(o.userId);
  });

  it("is idempotent under a replayed op id", async () => {
    const o = await ownerOf("resp-4");
    const a = await putResp(o.familyId, "r_4", o.token, mute(), "op_abc");
    const b = await putResp(o.familyId, "r_4", o.token, mute(), "op_abc");
    expect((await a.json()).version).toBe((await b.json()).version);
  });

  it("binds an idempotency key to its exact response and never replays a private rule", async () => {
    const o = await ownerOf("resp-replay-private-owner");
    const m = await memberOf("resp-replay-private-member", o.familyId);
    expect((await putResp(o.familyId, "private_rule", o.token, mute({ note: "Private note" }), "private-op")).status).toBe(200);

    // Even the original op key does not become a read capability for another member.
    const hidden = await putResp(o.familyId, "private_rule", m.token, mute(), "private-op");
    expect(hidden.status).toBe(404);
    expect(await hidden.text()).toBe("");

    // Nor can a member bind an arbitrary op key to one response and replay another id.
    expect((await putResp(o.familyId, "member_rule", m.token, mute(), "member-op")).status).toBe(200);
    const reused = await putResp(o.familyId, "private_rule", m.token, mute(), "member-op");
    expect(reused.status).toBe(409);
    expect((await reused.json()).type).toBe("idempotency-key-reused");
  });

  it("binds operation keys to the response verb as well as the target id", async () => {
    const o = await ownerOf("resp-replay-verb");
    expect((await putResp(o.familyId, "verb_rule", o.token, mute(), "verb-put-op")).status).toBe(200);

    const putAsDelete = await delResp(o.familyId, "verb_rule", o.token, "verb-put-op");
    expect(putAsDelete.status).toBe(409);
    expect((await putAsDelete.json()).type).toBe("idempotency-key-reused");

    expect((await delResp(o.familyId, "verb_rule", o.token, "verb-delete-op")).status).toBe(204);
    const deleteAsPut = await putResp(o.familyId, "verb_rule", o.token, mute(), "verb-delete-op");
    expect(deleteAsPut.status).toBe(409);
    expect((await deleteAsPut.json()).type).toBe("idempotency-key-reused");
  });

  it("binds even a no-op response delete key to its requested id", async () => {
    const o = await ownerOf("resp-replay-noop-delete");
    expect((await delResp(o.familyId, "missing_rule", o.token, "noop-delete-op")).status).toBe(204);

    const reused = await delResp(o.familyId, "different_missing_rule", o.token, "noop-delete-op");
    expect(reused.status).toBe(409);
    expect((await reused.json()).type).toBe("idempotency-key-reused");
  });

  it("rechecks a restricted Hub ACL before replaying a recorded completion", async () => {
    const o = await ownerOf("resp-replay-hub-owner");
    const m = await memberOf("resp-replay-hub-member", o.familyId);
    await q(`INSERT INTO hubs(id,family_id,type,title,visibility,created_by) VALUES ('replay_hub',$1,'other','Private','restricted',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES ($1,'replay_hub',$2,'viewer')`, [o.familyId, m.userId]);
    await q(`INSERT INTO sections(id,family_id,hub_id,title) VALUES ('replay_section',$1,'replay_hub','Tasks')`, [o.familyId]);
    const subject = "hub:replay_hub/section:replay_section/block:replay_block";
    await q(`INSERT INTO blocks(id,family_id,section_id,type,body_md,provenance,subject_ref) VALUES ('replay_block',$1,'replay_section','text','Secret task','{}',$2)`, [o.familyId, subject]);
    const body = done(subject, { note: "Sensitive completion note" });
    expect((await putResp(o.familyId, "replay_done", m.token, body, "replay-done-op")).status).toBe(200);

    await q(`DELETE FROM resource_visibility WHERE family_id=$1 AND hub_id='replay_hub' AND user_id=$2`, [o.familyId, m.userId]);
    const replay = await putResp(o.familyId, "replay_done", m.token, body, "replay-done-op");
    expect(replay.status).toBe(404);
    expect(await replay.text()).toBe("");
  });

  it("cannot reuse a response id to edit a now-inaccessible concrete rule", async () => {
    const o = await ownerOf("resp-id-acl-owner");
    const m = await memberOf("resp-id-acl-member", o.familyId);
    await q(`INSERT INTO hubs(id,family_id,type,title,visibility,created_by) VALUES ('id_acl_hub',$1,'other','Private','restricted',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES ($1,'id_acl_hub',$2,'viewer')`, [o.familyId, m.userId]);
    const original = mute({ subject_ref: "hub:id_acl_hub", match_scope: "subject", note: "Private note" });
    expect((await putResp(o.familyId, "reused_response_id", m.token, original)).status).toBe(200);
    await q(`DELETE FROM resource_visibility WHERE family_id=$1 AND hub_id='id_acl_hub' AND user_id=$2`, [o.familyId, m.userId]);

    const reused = await putResp(o.familyId, "reused_response_id", m.token, mute({
      subject_ref: "kind:weather", match_scope: "kind", note: "Overwrite",
    }));
    expect(reused.status).toBe(404);
    expect(await reused.text()).toBe("");
    const stored = (await q(`SELECT subject_ref,note FROM content_responses WHERE family_id=$1 AND id='reused_response_id'`, [o.familyId])).rows[0];
    expect(stored).toMatchObject({ subject_ref: "hub:id_acl_hub", note: "Private note" });
  });

  it("does not resurrect a deleted response id under a different immutable identity", async () => {
    const o = await ownerOf("resp-id-tombstone");
    expect((await putResp(o.familyId, "deleted_response_id", o.token, mute())).status).toBe(200);
    expect((await delResp(o.familyId, "deleted_response_id", o.token)).status).toBe(204);
    const reused = await putResp(o.familyId, "deleted_response_id", o.token, mute({
      subject_ref: "kind:traffic", match_scope: "kind",
    }));
    expect(reused.status).toBe(409);
    expect((await reused.json()).type).toBe("response-id-conflict");
  });

  // Editing a rule's identity would let a member widen their own personal rule to the whole
  // family, or re-attribute someone else's. Editable fields are the display strings only.
  it("rejects a re-PUT that widens a personal rule to family-wide", async () => {
    const o = await ownerOf("resp-5");
    await putResp(o.familyId, "r_5", o.token, mute());
    const res = await putResp(o.familyId, "r_5", o.token, mute({ audience_scope: "family", label: "Renamed" }));
    expect(res.status).toBe(409);
    expect((await res.json()).type).toBe("response-id-conflict");
    const stored = (await q(
      `SELECT audience_scope,user_id,label FROM content_responses WHERE family_id=$1 AND id='r_5'`,
      [o.familyId],
    )).rows[0];
    expect(stored).toEqual({ audience_scope: "personal", user_id: o.userId, label: "Weather cards" });
  });

  it("keeps immutable identity when same-actor same-id mutes race", async () => {
    const o = await ownerOf("resp-same-id-race");
    const base = {
      kind: "mute" as const,
      matchScope: "kind" as const,
      audienceScope: "personal" as const,
      userId: o.userId,
      createdBy: o.userId,
      sublabel: null,
      note: null,
    };
    const [weather, traffic] = await Promise.all([
      responseRepo.upsertResponse(o.familyId, "same_id_race", {
        ...base, subjectRef: "kind:weather", label: "Weather",
      }),
      responseRepo.upsertResponse(o.familyId, "same_id_race", {
        ...base, subjectRef: "kind:traffic", label: "Traffic",
      }),
    ]);

    expect([weather, traffic].filter(Boolean)).toHaveLength(1);
    const stored = (await q(
      `SELECT subject_ref,label FROM content_responses WHERE family_id=$1 AND id='same_id_race'`,
      [o.familyId],
    )).rows[0];
    expect([
      { subject_ref: "kind:weather", label: "Weather" },
      { subject_ref: "kind:traffic", label: "Traffic" },
    ]).toContainEqual(stored);
  });

  it("does not let another member overwrite a response id or forge its byline", async () => {
    const o = await ownerOf("resp-owner-lock");
    const m = await memberOf("resp-owner-lock-b", o.familyId);
    await putResp(o.familyId, "r_owned", o.token, mute({ label: "Original label" }));

    const forged = await putResp(o.familyId, "r_owned", m.token, mute({ label: "Forged label" }));
    // A personal rule owned by somebody else is deliberately indistinguishable from absence.
    expect(forged.status).toBe(404);
    expect(await forged.text()).toBe("");
    const row = (await q(
      `SELECT created_by, label FROM content_responses WHERE family_id=$1 AND id='r_owned'`,
      [o.familyId],
    )).rows[0];
    expect(row).toEqual({ created_by: o.userId, label: "Original label" });
  });

  it("rejects a done row that is not family/subject shaped", async () => {
    const o = await ownerOf("resp-6");
    const res = await putResp(o.familyId, "r_6", o.token, {
      kind: "done", subject_ref: "kind:weather", match_scope: "kind",
      audience_scope: "personal", label: "nope",
    });
    expect(res.status).toBe(422);
  });

  it("rejects a class ref carrying a subject match scope, and vice versa", async () => {
    const o = await ownerOf("resp-7");
    expect((await putResp(o.familyId, "r_7a", o.token, mute({ subject_ref: "kind:weather", match_scope: "subject" }))).status).toBe(422);
    expect((await putResp(o.familyId, "r_7b", o.token, mute({ subject_ref: "card:c1", match_scope: "kind" }))).status).toBe(422);
  });

  it("rejects a malformed body", async () => {
    const o = await ownerOf("resp-8");
    expect((await putResp(o.familyId, "r_8a", o.token, { kind: "mute" })).status).toBe(422);
    expect((await putResp(o.familyId, "r_8b", o.token, mute({ match_scope: "vibes" }))).status).toBe(422);
  });

  it("returns the same 404 for an absent card and a restricted card the caller cannot see", async () => {
    const o = await ownerOf("resp-visible-card-owner");
    const m = await memberOf("resp-visible-card-member", o.familyId);
    await q(
      `INSERT INTO briefing_cards(id,family_id,title,provenance,visibility,audience)
       VALUES ('secret_card',$1,'Secret','{}','restricted',ARRAY[$2]::text[])`,
      [o.familyId, o.userId],
    );
    await q(`UPDATE briefing_cards SET subject_ref='card:secret_card' WHERE family_id=$1 AND id='secret_card'`, [o.familyId]);

    const absent = await putResp(o.familyId, "r_absent_card", m.token, done("card:no_such_card"));
    const hidden = await putResp(o.familyId, "r_hidden_card", m.token, done("card:secret_card"));

    expect(absent.status).toBe(404);
    expect(hidden.status).toBe(404);
    expect((await q(`SELECT 1 FROM content_responses WHERE family_id=$1 AND id IN ('r_absent_card','r_hidden_card')`, [o.familyId])).rowCount).toBe(0);
    expect((await q(`SELECT deleted_at FROM briefing_cards WHERE family_id=$1 AND id='secret_card'`, [o.familyId])).rows[0].deleted_at).toBeNull();
  });

  it("requires a live block at the exact visible hub and section named by the subject", async () => {
    const o = await ownerOf("resp-visible-block-owner");
    const m = await memberOf("resp-visible-block-member", o.familyId);
    await q(`INSERT INTO hubs(id,family_id,type,title,visibility,created_by) VALUES ('response_hub',$1,'other','Response hub','restricted',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO sections(id,family_id,hub_id,title) VALUES ('response_section',$1,'response_hub','Tasks')`, [o.familyId]);
    await q(`INSERT INTO blocks(id,family_id,section_id,type,body_md,provenance,subject_ref) VALUES ('response_block',$1,'response_section','text','Call the caterer','{}','hub:response_hub/section:response_section/block:response_block')`, [o.familyId]);

    expect((await putResp(
      o.familyId, "r_hidden_block", m.token,
      done("hub:response_hub/section:response_section/block:response_block"),
    )).status).toBe(404);

    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES ($1,'response_hub',$2,'viewer')`, [o.familyId, m.userId]);
    expect((await putResp(
      o.familyId, "r_wrong_section", m.token,
      done("hub:response_hub/section:not_the_section/block:response_block"),
    )).status).toBe(404);

    const visible = await putResp(
      o.familyId, "r_visible_block", m.token,
      done("hub:response_hub/section:response_section/block:response_block", { note: "Menu confirmed" }),
    );
    expect(visible.status).toBe(200);
    expect((await visible.json()).created_at).toBeTruthy();
    expect((await q(`SELECT deleted_at FROM blocks WHERE family_id=$1 AND id='response_block'`, [o.familyId])).rows[0].deleted_at).not.toBeNull();

    // Simulate the process dying after the completion transaction committed but before its
    // oplog record/HTTP response did: an exact retry with a new op id must still converge.
    const retried = await putResp(
      o.familyId, "r_visible_block", m.token,
      done("hub:response_hub/section:response_section/block:response_block", { note: "Menu confirmed" }),
      "late-retry-op",
    );
    expect(retried.status).toBe(200);
    expect((await retried.json()).id).toBe("r_visible_block");
  });

  it("concurrent Done writes converge on one family completion", async () => {
    const o = await ownerOf("resp-done-race-owner");
    const m = await memberOf("resp-done-race-member", o.familyId);
    await q(`INSERT INTO hubs(id,family_id,type,title,created_by) VALUES ('race_hub',$1,'other','Race',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO sections(id,family_id,hub_id,title) VALUES ('race_section',$1,'race_hub','Tasks')`, [o.familyId]);
    await q(`INSERT INTO blocks(id,family_id,section_id,type,body_md,provenance,subject_ref) VALUES ('race_block',$1,'race_section','text','One task','{}','hub:race_hub/section:race_section/block:race_block')`, [o.familyId]);
    const subject = "hub:race_hub/section:race_section/block:race_block";

    const [a, b] = await Promise.all([
      putResp(o.familyId, "race_done_a", o.token, done(subject)),
      putResp(o.familyId, "race_done_b", m.token, done(subject)),
    ]);

    expect([a.status, b.status].filter((s) => s === 200)).toHaveLength(1);
    const loser = [a, b].find((r) => r.status !== 200)!;
    expect(loser.status).toBe(409);
    const conflict = await loser.json();
    expect(conflict.type).toBe("subject-already-done");
    expect(conflict.response.subject_ref).toBe(subject);
    expect(conflict.response.note).toBeNull();
    expect(conflict.response.version).toBeTypeOf("number");
    const rows = await q(
      `SELECT id FROM content_responses WHERE family_id=$1 AND kind='done' AND subject_ref=$2 AND deleted_at IS NULL`,
      [o.familyId, subject],
    );
    expect(rows.rowCount).toBe(1);
    expect(conflict.response.id).toBe(rows.rows[0].id);
  });

  it("soft-deletes, and a re-delete is idempotent", async () => {
    const o = await ownerOf("resp-9");
    await putResp(o.familyId, "r_9", o.token, mute());
    expect((await delResp(o.familyId, "r_9", o.token)).status).toBe(204);
    expect((await delResp(o.familyId, "r_9", o.token)).status).toBe(204);
    const row = await q(`SELECT deleted_at FROM content_responses WHERE family_id=$1 AND id=$2`, [o.familyId, "r_9"]);
    expect(row.rows[0].deleted_at).not.toBeNull();
  });

  it("deleting an id that never existed is still 204", async () => {
    const o = await ownerOf("resp-10");
    expect((await delResp(o.familyId, "r_nope", o.token)).status).toBe(204);
  });

  it("does not delete a durable Done record", async () => {
    const o = await ownerOf("resp-durable-done");
    await q(`INSERT INTO briefing_cards(id,family_id,title,provenance,subject_ref) VALUES ('durable_card',$1,'Done task','{}','card:durable_card')`, [o.familyId]);
    expect((await putResp(o.familyId, "durable_done", o.token, done("card:durable_card"))).status).toBe(200);

    const removed = await delResp(o.familyId, "durable_done", o.token);
    expect(removed.status).toBe(409);
    expect((await removed.json()).type).toBe("done-is-durable");
    expect((await q(`SELECT deleted_at FROM content_responses WHERE family_id=$1 AND id='durable_done'`, [o.familyId])).rows[0].deleted_at).toBeNull();
  });

  it("does not let a revoked member delete a restricted concrete response by retained id", async () => {
    const o = await ownerOf("resp-delete-acl-owner");
    const m = await memberOf("resp-delete-acl-member", o.familyId);
    await q(`INSERT INTO hubs(id,family_id,type,title,visibility,created_by) VALUES ('delete_acl_hub',$1,'other','Private','restricted',$2)`, [o.familyId, o.userId]);
    await q(`INSERT INTO resource_visibility(family_id,hub_id,user_id,role) VALUES ($1,'delete_acl_hub',$2,'viewer')`, [o.familyId, m.userId]);
    const subject = "hub:delete_acl_hub";
    expect((await putResp(o.familyId, "restricted_mute", m.token, mute({
      subject_ref: subject, match_scope: "subject", audience_scope: "family",
    }))).status).toBe(200);
    await q(`DELETE FROM resource_visibility WHERE family_id=$1 AND hub_id='delete_acl_hub' AND user_id=$2`, [o.familyId, m.userId]);

    expect((await delResp(o.familyId, "restricted_mute", m.token)).status).toBe(404);
    expect((await q(`SELECT deleted_at FROM content_responses WHERE family_id=$1 AND id='restricted_mute'`, [o.familyId])).rows[0].deleted_at).toBeNull();
  });

  // Decided Q2: any adult removes a FAMILY rule. A personal rule is its owner's alone —
  // otherwise one member could un-mute content for another.
  it("any member may remove a family rule, but not another member's personal rule", async () => {
    const o = await ownerOf("resp-11");
    const m = await memberOf("resp-11-b", o.familyId);
    await putResp(o.familyId, "r_fam", o.token, mute({ audience_scope: "family" }));
    await putResp(o.familyId, "r_mine", o.token, mute());
    expect((await delResp(o.familyId, "r_fam", m.token)).status).toBe(204);
    expect((await delResp(o.familyId, "r_mine", m.token)).status).toBe(403);
  });

  it("refuses a caller outside the family", async () => {
    const o = await ownerOf("resp-12");
    const other = await ownerOf("resp-12-out");
    const res = await putResp(o.familyId, "r_x", other.token, mute());
    expect([403, 404]).toContain(res.status);
  });
});


// ADR 0064 — the READ side, which is what lets the authoring loop avoid producing similar
// content rather than merely having it rejected after the fact.
describe("GET /responses — what the authoring path reads before it writes", () => {
  it("returns family rules with their human-readable labels", async () => {
    const o = await ownerOf("resp-get-1");
    await putResp(o.familyId, "r_w", o.token, mute({
      audience_scope: "family", label: "Weather cards", sublabel: "From Morning briefing",
    }));
    const res = await app.request(`/families/${o.familyId}/responses`, {
      headers: { authorization: `Bearer ${o.token}` },
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    const row = body.responses.find((r: any) => r.id === "r_w");
    // The labels are the SEMANTIC signal — the server never reads them, the agent does.
    expect(row.label).toBe("Weather cards");
    expect(row.sublabel).toBe("From Morning briefing");
    expect(row.subject_ref).toBe("kind:weather");
    expect(row.match_scope).toBe("kind");
  });

  it("includes done records, so a resolved subject is not re-authored", async () => {
    const o = await ownerOf("resp-get-2");
    await q(`INSERT INTO briefing_cards(id,family_id,title,provenance,subject_ref) VALUES ('c_task',$1,'Verify emergency contact','{}','card:c_task')`, [o.familyId]);
    await putResp(o.familyId, "d1", o.token, {
      kind: "done", subject_ref: "card:c_task", match_scope: "subject",
      audience_scope: "family", label: "Verify emergency contact",
    });
    const body = await (await app.request(`/families/${o.familyId}/responses`, {
      headers: { authorization: `Bearer ${o.token}` },
    })).json();
    expect(body.responses.some((r: any) => r.kind === "done" && r.id === "d1")).toBe(true);
  });

  // Another member's personal rule is COUNTED, never disclosed: the author learns that
  // suppression exists without learning who muted what.
  it("counts another member's personal rules without exposing them", async () => {
    const o = await ownerOf("resp-get-3");
    const m = await memberOf("resp-get-3-b", o.familyId);
    await putResp(o.familyId, "r_theirs", m.token, mute({ audience_scope: "personal" }));
    const body = await (await app.request(`/families/${o.familyId}/responses`, {
      headers: { authorization: `Bearer ${o.token}` },
    })).json();
    expect(body.responses.some((r: any) => r.id === "r_theirs")).toBe(false);
    expect(body.personal_rules_not_visible).toBe(1);
  });

  it("shows a member their OWN personal rule", async () => {
    const o = await ownerOf("resp-get-4");
    await putResp(o.familyId, "r_mine", o.token, mute({ audience_scope: "personal" }));
    const body = await (await app.request(`/families/${o.familyId}/responses`, {
      headers: { authorization: `Bearer ${o.token}` },
    })).json();
    expect(body.responses.some((r: any) => r.id === "r_mine")).toBe(true);
    expect(body.personal_rules_not_visible).toBe(0);
  });
});
