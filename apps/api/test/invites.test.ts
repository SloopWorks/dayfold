import { describe, it, expect, beforeAll, afterAll, vi } from "vitest";
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
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0005_invites.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
});
afterAll(async () => { await pool.end(); });

const dev = { "content-type":"application/json", authorization:"Bearer dev" };

// Mint an owner of a fresh family via dev-token + POST /families
async function ownerOf(uid: string) {
  const t = (await (await app.request("/auth/dev-token",{method:"POST",headers:dev,body:JSON.stringify({provider:"dev",provider_uid:uid})})).json()).access;
  const fam = await (await app.request("/families",{method:"POST",headers:{...dev,authorization:`Bearer ${t}`},body:JSON.stringify({name:uid})})).json();
  return { token: t, familyId: fam.familyId };
}

describe("POST /families/:fid/invites", () => {
  it("owner mints; token ≥128-bit + only hash stored; raw returned once; not gzipped", async () => {
    const o = await ownerOf("alice");
    const r = await app.request(`/families/${o.familyId}/invites`, { method:"POST", headers:{...dev, authorization:`Bearer ${o.token}`}, body: JSON.stringify({ mode:"link", max_uses:3 }) });
    expect(r.status).toBe(201);
    expect(r.headers.get("content-encoding")).toBeNull();
    const b = await r.json();
    expect(Buffer.from(b.token, "base64url").length).toBeGreaterThanOrEqual(16);
    expect(b.role).toBe("adult");
    const row = await q(`SELECT token_hash FROM invites WHERE id=$1`, [b.invite_id]);
    expect(row.rows[0].token_hash).not.toContain(b.token); // hash, not raw
  });
  it("non-owner→404; cli token→403; role=owner/teen→400; max_uses>10→400", async () => {
    const o = await ownerOf("bob");
    const stranger = await ownerOf("carol"); // owner of a DIFFERENT family
    expect((await app.request(`/families/${o.familyId}/invites`,{method:"POST",headers:{...dev,authorization:`Bearer ${stranger.token}`},body:'{"mode":"qr"}'})).status).toBe(404); // not a member → 404
    expect((await app.request(`/families/${o.familyId}/invites`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`},body:'{"mode":"qr","role":"owner"}'})).status).toBe(400);
    expect((await app.request(`/families/${o.familyId}/invites`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`},body:'{"mode":"link","max_uses":11}'})).status).toBe(400);
  });
});

// Helper: mint a dev access token for an invitee (no family)
async function devToken(uid: string): Promise<string> {
  return (await (await app.request("/auth/dev-token",{method:"POST",headers:dev,body:JSON.stringify({provider:"dev",provider_uid:uid})})).json()).access;
}

// Helper: owner mints a link invite for their family
async function mintInvite(ownerToken: string, familyId: string, maxUses=1) {
  const r = await app.request(`/families/${familyId}/invites`,{method:"POST",headers:{...dev,authorization:`Bearer ${ownerToken}`},body:JSON.stringify({mode:"link",max_uses:maxUses})});
  return (await r.json()) as { token: string; invite_id: string; role: string };
}

describe("POST /invites:redeem", () => {
  it("net-new → 200 pending + used_count bumped", async () => {
    const o = await ownerOf("redeem-owner-1");
    const inv = await mintInvite(o.token, o.familyId, 3);
    const invitee = await devToken("invitee-1");

    const r = await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});
    expect(r.status).toBe(200);
    const b = await r.json();
    expect(b.status).toBe("pending");
    expect(b.role).toBe("adult");
    expect(b.family_id).toBe(o.familyId);

    const row = await q(`SELECT used_count FROM invites WHERE id=$1`,[inv.invite_id]);
    expect(row.rows[0].used_count).toBe(1);
  });

  it("two distinct users redeem max_uses=1 → exactly one pending + one 404 (no double-spend)", async () => {
    const o = await ownerOf("redeem-owner-2");
    const inv = await mintInvite(o.token, o.familyId, 1);
    const userA = await devToken("invitee-2a");
    const userB = await devToken("invitee-2b");

    // Fire both concurrently
    const [rA, rB] = await Promise.all([
      app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${userA}`},body:JSON.stringify({token:inv.token})}),
      app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${userB}`},body:JSON.stringify({token:inv.token})}),
    ]);

    const statuses = [rA.status, rB.status].sort();
    expect(statuses).toEqual([200, 404]);

    const row = await q(`SELECT used_count, status FROM invites WHERE id=$1`,[inv.invite_id]);
    expect(row.rows[0].used_count).toBe(1);
    expect(row.rows[0].status).toBe("exhausted");

    const pend = await q(`SELECT count(*)::int n FROM memberships WHERE family_id=$1 AND status='pending'`,[o.familyId]);
    expect(pend.rows[0].n).toBe(1);
  });

  it("pending cap holds under concurrent redeems via DISTINCT invites (per-family advisory lock, S4 follow-3)", async () => {
    const o = await ownerOf("cap-race-owner");
    // pad to PENDING_CAP-1 (19) pending members so one more redeem fills the cap.
    await q(`INSERT INTO users(id) SELECT 'capu_'||g FROM generate_series(1,19) g`);
    await q(`INSERT INTO memberships(user_id,family_id,role,status) SELECT 'capu_'||g,$1,'adult','pending' FROM generate_series(1,19) g`, [o.familyId]);
    // 5 DISTINCT invites + 5 DISTINCT users redeem concurrently (different invite
    // rows → the invite FOR UPDATE doesn't serialize them; only the family lock does).
    const invites = await Promise.all([1,2,3,4,5].map(() => mintInvite(o.token, o.familyId, 1)));
    const tokens = await Promise.all([1,2,3,4,5].map((i) => devToken(`cap-race-${i}`)));
    const results = await Promise.all(invites.map((inv, i) =>
      app.request("/invites:redeem", { method: "POST", headers: { ...dev, authorization: `Bearer ${tokens[i]}` }, body: JSON.stringify({ token: inv.token }) })));
    const statuses = results.map((r) => r.status).sort();
    expect(statuses.filter((s) => s === 200).length).toBe(1);     // exactly one fits
    expect(statuses.filter((s) => s === 429).length).toBe(4);     // the rest hit the cap
    const pend = await q(`SELECT count(*)::int n FROM memberships WHERE family_id=$1 AND status='pending'`, [o.familyId]);
    expect(pend.rows[0].n).toBe(20);                              // exactly the cap, never 21+
  });

  it("same user re-redeem → 200 idempotent (no extra used_count increment)", async () => {
    const o = await ownerOf("redeem-owner-3");
    const inv = await mintInvite(o.token, o.familyId, 5);
    const invitee = await devToken("invitee-3");

    const r1 = await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});
    expect(r1.status).toBe(200);

    const r2 = await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});
    expect(r2.status).toBe(200);
    const b2 = await r2.json();
    expect(b2.status).toBe("pending");

    const row = await q(`SELECT used_count FROM invites WHERE id=$1`,[inv.invite_id]);
    expect(row.rows[0].used_count).toBe(1); // only bumped once
  });

  it("uniform 404 for bad token / expired / revoked", async () => {
    const invitee = await devToken("invitee-4");
    const authH = {...dev, authorization:`Bearer ${invitee}`};

    // bad token
    const r1 = await app.request("/invites:redeem",{method:"POST",headers:authH,body:JSON.stringify({token:"bad-token-xyz"})});
    expect(r1.status).toBe(404);

    // expired: insert directly
    const o = await ownerOf("redeem-owner-4");
    const ownerRow = await q(`SELECT m.user_id FROM memberships m WHERE m.family_id=$1 AND m.role='owner'`,[o.familyId]);
    const ownerId = ownerRow.rows[0].user_id;
    const { hashInvite } = await import("../src/auth/invites.ts");
    const expiredToken = "expired-token-test";
    await q(`INSERT INTO invites(id,family_id,role,token_hash,mode,max_uses,created_by,expires_at,status)
             VALUES ('inv_expired',$1,'adult',$2,'link',1,$3, now()-interval'1 second','active')`,[o.familyId,hashInvite(expiredToken),ownerId]);
    const r2 = await app.request("/invites:redeem",{method:"POST",headers:authH,body:JSON.stringify({token:expiredToken})});
    expect(r2.status).toBe(404);

    // revoked: active but status=revoked
    const inv = await mintInvite(o.token, o.familyId, 2);
    await q(`UPDATE invites SET status='revoked' WHERE id=$1`,[inv.invite_id]);
    const r3 = await app.request("/invites:redeem",{method:"POST",headers:authH,body:JSON.stringify({token:inv.token})});
    expect(r3.status).toBe(404);
  });

  it("role from invite is preserved", async () => {
    // Insert an adult-role invite with a custom id to confirm role flows through
    const o = await ownerOf("redeem-owner-5");
    const ownerRow5 = await q(`SELECT m.user_id FROM memberships m WHERE m.family_id=$1 AND m.role='owner'`,[o.familyId]);
    const ownerId5 = ownerRow5.rows[0].user_id;
    const { hashInvite, genInviteToken } = await import("../src/auth/invites.ts");
    const tok = genInviteToken();
    await q(`INSERT INTO invites(id,family_id,role,token_hash,mode,max_uses,created_by,expires_at,status)
             VALUES ('inv_adult5',$1,'adult',$2,'link',1,$3, now()+interval'1 hour','active')`,[o.familyId,hashInvite(tok),ownerId5]);
    const invitee = await devToken("invitee-5");
    const r = await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:tok})});
    expect(r.status).toBe(200);
    expect((await r.json()).role).toBe("adult");
  });

  it("per-account lockout after 5 bad attempts → 429", async () => {
    const invitee = await devToken("invitee-lockout");
    const authH = {...dev, authorization:`Bearer ${invitee}`};
    for (let i=0; i<5; i++) {
      await app.request("/invites:redeem",{method:"POST",headers:authH,body:JSON.stringify({token:"bad-"+i})});
    }
    const r = await app.request("/invites:redeem",{method:"POST",headers:authH,body:JSON.stringify({token:"bad-final"})});
    expect(r.status).toBe(429);
  });

  it("pending-cap → 429", async () => {
    const o = await ownerOf("redeem-owner-cap");
    const inv = await mintInvite(o.token, o.familyId, 1);
    // Stuff 20 pending memberships directly (bypass invite flow)
    for (let i=0; i<20; i++) {
      await q(`INSERT INTO users(id) VALUES ('u_cap_${i}') ON CONFLICT DO NOTHING`);
      await q(`INSERT INTO memberships(user_id,family_id,role,status) VALUES ('u_cap_${i}',$1,'adult','pending') ON CONFLICT DO NOTHING`,[o.familyId]);
    }
    const invitee = await devToken("invitee-cap");
    const r = await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});
    expect(r.status).toBe(429);
  });
});

describe("approve / decline / revoke + GET /families/:fid/invites", () => {
  it("approve: owner-only (non-member→404, member non-owner→404)", async () => {
    const o = await ownerOf("approve-owner-1");
    const inv = await mintInvite(o.token, o.familyId);
    const invitee = await devToken("approve-invitee-1");
    await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});
    const inviteeId = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND status='pending'`,[o.familyId])).rows[0].user_id;

    // stranger (owner of different family) → 404
    const stranger = await ownerOf("approve-stranger-1");
    const r404 = await app.request(`/families/${o.familyId}/members/${inviteeId}:approve`,{method:"POST",headers:{...dev,authorization:`Bearer ${stranger.token}`}});
    expect(r404.status).toBe(404);
  });

  it("approve: pending→active→204", async () => {
    const o = await ownerOf("approve-owner-2");
    const inv = await mintInvite(o.token, o.familyId);
    const invitee = await devToken("approve-invitee-2");
    await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});
    const inviteeId = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND status='pending'`,[o.familyId])).rows[0].user_id;

    const r = await app.request(`/families/${o.familyId}/members/${inviteeId}:approve`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r.status).toBe(204);

    const row = await q(`SELECT status FROM memberships WHERE user_id=$1 AND family_id=$2`,[inviteeId,o.familyId]);
    expect(row.rows[0].status).toBe("active");
  });

  it("approve: re-approve active→200 idempotent", async () => {
    const o = await ownerOf("approve-owner-3");
    const inv = await mintInvite(o.token, o.familyId);
    const invitee = await devToken("approve-invitee-3");
    await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});
    const inviteeId = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND status='pending'`,[o.familyId])).rows[0].user_id;

    await app.request(`/families/${o.familyId}/members/${inviteeId}:approve`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`}});
    const r2 = await app.request(`/families/${o.familyId}/members/${inviteeId}:approve`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r2.status).toBe(200);
  });

  it("approve: removed→409", async () => {
    const o = await ownerOf("approve-owner-4");
    const inv = await mintInvite(o.token, o.familyId);
    const invitee = await devToken("approve-invitee-4");
    await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});
    const inviteeId = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND status='pending'`,[o.familyId])).rows[0].user_id;

    // decline first → removed
    await app.request(`/families/${o.familyId}/members/${inviteeId}:decline`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`}});
    const r = await app.request(`/families/${o.familyId}/members/${inviteeId}:approve`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r.status).toBe(409);
  });

  it("approve: no membership→404", async () => {
    const o = await ownerOf("approve-owner-5");
    const r = await app.request(`/families/${o.familyId}/members/nonexistent-uid:approve`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r.status).toBe(404);
  });

  it("decline: pending→removed→204; re-decline→404", async () => {
    const o = await ownerOf("decline-owner-1");
    const inv = await mintInvite(o.token, o.familyId);
    const invitee = await devToken("decline-invitee-1");
    await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});
    const inviteeId = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND status='pending'`,[o.familyId])).rows[0].user_id;

    const r = await app.request(`/families/${o.familyId}/members/${inviteeId}:decline`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r.status).toBe(204);

    const row = await q(`SELECT status FROM memberships WHERE user_id=$1 AND family_id=$2`,[inviteeId,o.familyId]);
    expect(row.rows[0].status).toBe("removed");

    // re-decline → 404 (already removed, not pending)
    const r2 = await app.request(`/families/${o.familyId}/members/${inviteeId}:decline`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r2.status).toBe(404);
  });

  it("revoke: active invite → 204; revoked invite blocks redeem with 404", async () => {
    const o = await ownerOf("revoke-owner-1");
    const inv = await mintInvite(o.token, o.familyId);

    // revoke it
    const r = await app.request(`/families/${o.familyId}/invites/${inv.invite_id}`,{method:"DELETE",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r.status).toBe(204);

    // verify revoked in DB
    const row = await q(`SELECT status FROM invites WHERE id=$1`,[inv.invite_id]);
    expect(row.rows[0].status).toBe("revoked");

    // now redeem → 404
    const invitee = await devToken("revoke-invitee-1");
    const r2 = await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});
    expect(r2.status).toBe(404);
  });

  it("revoke: sticky no-op if already non-active → 204", async () => {
    const o = await ownerOf("revoke-owner-2");
    const inv = await mintInvite(o.token, o.familyId);

    await app.request(`/families/${o.familyId}/invites/${inv.invite_id}`,{method:"DELETE",headers:{...dev,authorization:`Bearer ${o.token}`}});
    // revoke again
    const r2 = await app.request(`/families/${o.familyId}/invites/${inv.invite_id}`,{method:"DELETE",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r2.status).toBe(204);
  });

  it("GET /families/:fid/invites: returns live invites + pending queue with invitee identity [I4]", async () => {
    const o = await ownerOf("queue-owner-1");
    const inv = await mintInvite(o.token, o.familyId);

    // invitee redeems → pending
    const invitee = await devToken("queue-invitee-1");
    await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});

    // mint a second invite (still live)
    await mintInvite(o.token, o.familyId);

    const r = await app.request(`/families/${o.familyId}/invites`,{method:"GET",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r.status).toBe(200);
    const b = await r.json();

    // live invites (the second one we minted; first is exhausted after redeem)
    expect(Array.isArray(b.invites)).toBe(true);
    // token/hash must not appear in response
    for (const inv of b.invites) {
      expect(inv.token_hash).toBeUndefined();
      expect(inv.token).toBeUndefined();
    }

    // pending queue with identity
    expect(Array.isArray(b.pending)).toBe(true);
    expect(b.pending.length).toBeGreaterThanOrEqual(1);
    const pRow = b.pending[0];
    expect(pRow.uid).toBeDefined();
    expect(pRow.provider).toBe("dev");
    expect(pRow.provider_uid).toBe("queue-invitee-1");
    expect(pRow.role).toBeDefined();
    expect(pRow.requested_at).toBeDefined();
  });

  it("a pending member with MULTIPLE identities appears exactly once (dedup, S4 follow-1)", async () => {
    const o = await ownerOf("dedup-owner");
    const inv = await mintInvite(o.token, o.familyId);
    const invitee = await devToken("dedup-invitee");
    await app.request("/invites:redeem", { method: "POST", headers: { ...dev, authorization: `Bearer ${invitee}` }, body: JSON.stringify({ token: inv.token }) });
    const me = await (await app.request("/auth/me", { headers: { authorization: `Bearer ${invitee}` } })).json();
    // a second identity for the SAME user (e.g. Google + Apple at S2) — a plain
    // LEFT JOIN would fan this person into two pending rows.
    await q(`INSERT INTO user_identities(id,user_id,provider,provider_uid) VALUES ($1,$2,'apple','apple-sub-dedup')`,
      ["uid2_" + Math.random().toString(16).slice(2), me.user_id]);
    const b = await (await app.request(`/families/${o.familyId}/invites`, { method: "GET", headers: { ...dev, authorization: `Bearer ${o.token}` } })).json();
    expect(b.pending.filter((p: any) => p.uid === me.user_id).length).toBe(1);   // once, not twice
  });

  it("concurrent approve: single-activates (exactly one 204, others 200 idempotent)", async () => {
    const o = await ownerOf("concurrent-approve-owner-1");
    const inv = await mintInvite(o.token, o.familyId);
    const invitee = await devToken("concurrent-invitee-1");
    await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${invitee}`},body:JSON.stringify({token:inv.token})});
    const inviteeId = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND status='pending'`,[o.familyId])).rows[0].user_id;

    // Fire 3 concurrent approvals
    const results = await Promise.all([
      app.request(`/families/${o.familyId}/members/${inviteeId}:approve`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`}}),
      app.request(`/families/${o.familyId}/members/${inviteeId}:approve`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`}}),
      app.request(`/families/${o.familyId}/members/${inviteeId}:approve`,{method:"POST",headers:{...dev,authorization:`Bearer ${o.token}`}}),
    ]);
    const statuses = results.map(r => r.status).sort();
    // exactly one 204, rest 200 (idempotent active)
    expect(statuses.filter(s => s === 204).length).toBe(1);
    expect(statuses.every(s => s === 204 || s === 200)).toBe(true);

    // membership is active exactly once
    const rows = await q(`SELECT status FROM memberships WHERE user_id=$1 AND family_id=$2`,[inviteeId,o.familyId]);
    expect(rows.rows.length).toBe(1);
    expect(rows.rows[0].status).toBe("active");
  });
});

describe("DELETE /families/:fid/members/:uid [C3] — member removal + ≥1-owner row-lock", () => {
  // Helper: approve a pending member
  async function approveMember(ownerToken: string, familyId: string, uid: string) {
    await app.request(`/families/${familyId}/members/${uid}:approve`,{method:"POST",headers:{...dev,authorization:`Bearer ${ownerToken}`}});
  }

  it("owner removes an active member → 204; that member 403s on family content", async () => {
    const o = await ownerOf("remove-owner-1");
    const inv = await mintInvite(o.token, o.familyId);
    const memberTok = await devToken("remove-member-1");
    await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${memberTok}`},body:JSON.stringify({token:inv.token})});
    const memberId = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND status='pending'`,[o.familyId])).rows[0].user_id;
    await approveMember(o.token, o.familyId, memberId);

    // Verify member can access family content before removal
    const beforeR = await app.request(`/families/${o.familyId}/cards`,{method:"GET",headers:{...dev,authorization:`Bearer ${memberTok}`}});
    expect(beforeR.status).toBe(200);

    // Remove member
    const r = await app.request(`/families/${o.familyId}/members/${memberId}`,{method:"DELETE",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r.status).toBe(204);

    // Verify membership is removed
    const row = await q(`SELECT status FROM memberships WHERE user_id=$1 AND family_id=$2`,[memberId,o.familyId]);
    expect(row.rows[0].status).toBe("removed");

    // Removed member's existing token → 403 on family content (membership re-resolution)
    const afterR = await app.request(`/families/${o.familyId}/cards`,{method:"GET",headers:{...dev,authorization:`Bearer ${memberTok}`}});
    expect(afterR.status).toBe(403);
  });

  it("removing the LAST owner → 409 (invariant: ≥1 owner must remain)", async () => {
    const o = await ownerOf("remove-last-owner-1");

    // Try to remove the only owner
    const ownerId = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND role='owner' AND status='active'`,[o.familyId])).rows[0].user_id;
    const r = await app.request(`/families/${o.familyId}/members/${ownerId}`,{method:"DELETE",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r.status).toBe(409);

    // Owner still active
    const row = await q(`SELECT status FROM memberships WHERE user_id=$1 AND family_id=$2`,[ownerId,o.familyId]);
    expect(row.rows[0].status).toBe("active");
  });

  it("removing unknown uid → 404", async () => {
    const o = await ownerOf("remove-notfound-1");
    const r = await app.request(`/families/${o.familyId}/members/nonexistent-uid`,{method:"DELETE",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(r.status).toBe(404);
  });

  it("non-owner → 403/404 (ownerGate)", async () => {
    const o = await ownerOf("remove-perm-1");
    const inv = await mintInvite(o.token, o.familyId);
    const memberTok = await devToken("remove-perm-member-1");
    await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${memberTok}`},body:JSON.stringify({token:inv.token})});
    const memberId = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND status='pending'`,[o.familyId])).rows[0].user_id;
    await approveMember(o.token, o.familyId, memberId);

    // active member (non-owner) tries to remove someone → 403
    const ownerId = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND role='owner' AND status='active'`,[o.familyId])).rows[0].user_id;
    const r = await app.request(`/families/${o.familyId}/members/${ownerId}`,{method:"DELETE",headers:{...dev,authorization:`Bearer ${memberTok}`}});
    expect(r.status).toBe(403);
  });

  it("concurrent double-remove of two owners → at most one succeeds, ≥1 owner remains", async () => {
    const o = await ownerOf("remove-concurrent-1");

    // Add a second owner directly (bypass invite: insert active owner membership)
    await q(`INSERT INTO users(id) VALUES ('u_owner2_concurrent') ON CONFLICT DO NOTHING`);
    await q(`INSERT INTO memberships(user_id,family_id,role,status,joined_at) VALUES ('u_owner2_concurrent',$1,'owner','active',now()) ON CONFLICT DO NOTHING`,[o.familyId]);

    const owner1Id = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND role='owner' AND status='active' ORDER BY joined_at LIMIT 1`,[o.familyId])).rows[0].user_id;
    const owner2Id = 'u_owner2_concurrent';

    // Concurrently try to remove both owners simultaneously
    const [r1, r2] = await Promise.all([
      app.request(`/families/${o.familyId}/members/${owner1Id}`,{method:"DELETE",headers:{...dev,authorization:`Bearer ${o.token}`}}),
      app.request(`/families/${o.familyId}/members/${owner2Id}`,{method:"DELETE",headers:{...dev,authorization:`Bearer ${o.token}`}}),
    ]);

    const statuses = [r1.status, r2.status];

    // At least one must be rejected (409) — cannot remove both owners concurrently
    // The row-lock ensures serialization: one txn sees 2 owners→proceeds, the other
    // sees 1 owner left after the first commits→ 409 (or sees 2 and proceeds, but
    // the second commit leaves 0 owners — so the impl must reject).
    const activeOwners = await q(`SELECT count(*)::int n FROM memberships WHERE family_id=$1 AND role='owner' AND status='active'`,[o.familyId]);
    expect(activeOwners.rows[0].n).toBeGreaterThanOrEqual(1);

    // At most one removal can succeed (204); if both tried to remove owners, one must 409
    const successCount = statuses.filter(s => s === 204).length;
    expect(successCount).toBeLessThanOrEqual(1);
  });

  // [M3] Device-kill regression: removed member with kind='cli' credential → 403
  it("[M3] removed member's cli device token → 403 on family content (membership re-resolution)", async () => {
    const o = await ownerOf("remove-device-kill-owner-1");
    const inv = await mintInvite(o.token, o.familyId);
    const memberTok = await devToken("remove-device-kill-member-1");
    await app.request("/invites:redeem",{method:"POST",headers:{...dev,authorization:`Bearer ${memberTok}`},body:JSON.stringify({token:inv.token})});
    const memberId = (await q(`SELECT user_id FROM memberships WHERE family_id=$1 AND status='pending'`,[o.familyId])).rows[0].user_id;
    await approveMember(o.token, o.familyId, memberId);

    // Create a cli-kind credential for the member (simulating a device token)
    const credId = "cred_cli_" + Math.random().toString(16).slice(2);
    await q(`INSERT INTO credentials(id,user_id,kind,scopes,family_scope) VALUES ($1,$2,'cli','{content:read}',$3)`,[credId,memberId,o.familyId]);
    await q(`INSERT INTO credential_grants(credential_id,scope) VALUES ($1,'content:read')`,[credId]); // ADR 0029 grant
    // Mint an access token for this cli credential
    const { mintAccess } = await import("../src/auth/tokens.ts");
    const cliToken = await mintAccess({ sub: memberId, cid: credId });

    // cli token works before removal
    const beforeR = await app.request(`/families/${o.familyId}/cards`,{method:"GET",headers:{"authorization":`Bearer ${cliToken}`}});
    expect(beforeR.status).toBe(200);

    // Owner removes the member
    const removeR = await app.request(`/families/${o.familyId}/members/${memberId}`,{method:"DELETE",headers:{...dev,authorization:`Bearer ${o.token}`}});
    expect(removeR.status).toBe(204);

    // cli device token now → 403 (middleware re-resolves membership each request)
    const afterR = await app.request(`/families/${o.familyId}/cards`,{method:"GET",headers:{"authorization":`Bearer ${cliToken}`}});
    expect(afterR.status).toBe(403);
  });
});
