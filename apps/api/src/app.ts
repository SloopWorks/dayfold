// M0 content API (feed surface): household-token auth → tenant-explicit
// card routes + keyset sync. Hono (runs on Vercel + locally + app.request()-testable).
import { Hono } from "hono";
import { bodyLimit } from "hono/body-limit";
import { q, pool } from "./db.ts";
import { stripServerManaged, stampProvenance } from "./security.ts";
import { BriefingCardSchema } from "./generated/content.ts";
import { crossValidateCard } from "./content-validation.ts";
import * as repo from "./repo.ts";
// Auth imports are lazy (dynamic) so that api.test.ts (no AUTH_* env) can still
// load app.ts without triggering the module-level env-guard throws in tokens.ts.
import { authorizeTenant } from "./auth/middleware.ts";

export const app = new Hono();

// Liveness — no auth, no DB (isolates routing/cold-start from the DB path).
app.get("/health", (c) => c.json({ ok: true, surface: "m0" }));

// [F9] RFC 9457 problem+json error helper.
function problem(c: any, status: number, type: string, detail?: string) {
  return c.body(JSON.stringify({ type, title: type, status, ...(detail ? { detail } : {}) }),
    status, { "content-type": "application/problem+json" });
}

// [F8] body-size cap (cost-DoS floor for the <$50/mo cap). Raw 1 MB.
app.use("*", bodyLimit({ maxSize: 1024 * 1024, onError: (c) => problem(c, 413, "payload-too-large") }));

function bearer(c: any): string | undefined {
  const h = c.req.header("authorization") || "";
  return h.startsWith("Bearer ") ? h.slice(7) : undefined;
}

function devAuthAllowed(_c: any): boolean {
  if (process.env.ENABLE_DEV_AUTH !== "1") return false;
  const env = process.env.VERCEL_ENV;
  if (env === "production" || env === "preview") return false;
  return true;
}

// Gated, local-only: mint a real token from a dev identity (kills local hardcoding).
app.post("/auth/dev-token", async (c) => {
  if (!devAuthAllowed(c)) return c.body(null, 404);                 // invisible in prod/preview
  if (bearer(c) !== (process.env.DEV_AUTH_SECRET || "\0")) return c.body(null, 401);
  const body = await c.req.json().catch(() => null);
  const { StubVerifier, findOrCreateUser } = await import("./auth/identity.ts");
  const idn = await new StubVerifier().verify(body).catch(() => null);
  if (!idn) return c.json({ type: "bad-identity" }, 400);
  const { userId } = await findOrCreateUser(idn);
  console.warn(`[dev-auth] minted token for ${idn.provider}:${idn.provider_uid} user=${userId}`);
  // Insert a null-family-scope app credential for this user
  const credentialId = "cred_" + Math.random().toString(16).slice(2);
  await q(
    `INSERT INTO credentials(id,user_id,kind,scopes) VALUES ($1,$2,'app','{content:read,content:write}')`,
    [credentialId, userId],
  );
  const { mintAccess } = await import("./auth/tokens.ts");
  const { issueRefresh } = await import("./auth/refresh.ts");
  const access = await mintAccess({ sub: userId, cid: credentialId });
  const refresh = await issueRefresh(credentialId);
  return c.json({ access, refresh });
});

app.post("/auth/refresh", async (c) => {
  const body = await c.req.json().catch(() => null);
  const { rotate, hashToken } = await import("./auth/refresh.ts");
  const out = await rotate(body?.refresh || "");
  if (!out) return c.body(null, 401);
  if ("refresh" in out) {
    if ((out as any).graced) {
      const { audit } = await import("./auth/audit.ts");
      await audit("refresh.grace_reissued", {});
    }
    // fall through to the existing access re-mint (works for graced rows too)
  } else { // { reuse: true }
    return c.body(null, 401);
  }
  // Look up the credential for the new refresh token to re-mint access.
  // rotate() already inserted the new token row; query by hash of nextOpaque.
  const h = hashToken(out.refresh);
  const row = await q(
    `SELECT rt.credential_id, c.user_id FROM refresh_tokens rt JOIN credentials c ON c.id=rt.credential_id WHERE rt.token_hash=$1 AND c.revoked_at IS NULL`,
    [h],
  ).catch(() => null);
  if (!row || row.rowCount === 0) return c.body(null, 401);
  const { credential_id: cid, user_id: sub } = row.rows[0];
  const { mintAccess } = await import("./auth/tokens.ts");
  const access = await mintAccess({ sub, cid });
  return c.json({ access, refresh: out.refresh });
});

app.post("/auth/signout", async (c) => {
  const t = bearer(c); if (!t) return c.body(null, 401);
  let cid: string;
  try {
    const { verifyAccess } = await import("./auth/tokens.ts");
    cid = (await verifyAccess(t)).cid;
  } catch { return c.body(null, 401); }
  await q(`UPDATE credentials SET revoked_at=now() WHERE id=$1`, [cid]);
  await q(`UPDATE refresh_tokens SET consumed_at=now() WHERE credential_id=$1 AND consumed_at IS NULL`, [cid]);
  return c.body(null, 204);
});

app.get("/auth/whoami", async (c) => {
  const t = bearer(c); if (!t) return c.body(null, 401);
  let sub: string, cid: string;
  try { const { verifyAccess } = await import("./auth/tokens.ts"); ({ sub, cid } = await verifyAccess(t)); }
  catch { return c.body(null, 401); }
  // Fail-closed: verify cred row exists and is not revoked (same as original S3 whoami).
  const credRow = await q(
    `SELECT family_scope FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!credRow || credRow.rowCount === 0) return c.body(null, 401);
  const family_id: string | null = credRow.rows[0].family_scope ?? null;
  const r = await q(
    `SELECT m.family_id, f.name, m.role, m.status FROM memberships m JOIN families f ON f.id=m.family_id
     WHERE m.user_id=$1 AND m.status IN ('active','pending') ORDER BY m.created_at`, [sub]);
  return c.json({ family_id, families: r.rows });
});

// Profile — the caller's own display name. (Memberships live in /auth/whoami.)
app.get("/auth/me", async (c) => {
  const t = bearer(c); if (!t) return c.body(null, 401);
  let sub: string, cid: string;
  try { const { verifyAccess } = await import("./auth/tokens.ts"); ({ sub, cid } = await verifyAccess(t)); }
  catch { return c.body(null, 401); }
  const cred = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!cred || cred.rowCount === 0) return c.body(null, 401);
  const u = (await q(`SELECT id, display_name FROM users WHERE id=$1 AND deleted_at IS NULL`, [sub])).rows[0];
  if (!u) return c.body(null, 401);
  return c.json({ user_id: u.id, display_name: u.display_name });
});

// Update the caller's own display name (1–80 chars after trim).
app.patch("/auth/me", async (c) => {
  const t = bearer(c); if (!t) return c.body(null, 401);
  let sub: string, cid: string;
  try { const { verifyAccess } = await import("./auth/tokens.ts"); ({ sub, cid } = await verifyAccess(t)); }
  catch { return c.body(null, 401); }
  const cred = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!cred || cred.rowCount === 0) return c.body(null, 401);
  const body = await c.req.json().catch(() => null);
  const name = typeof body?.display_name === "string" ? body.display_name.trim() : null;
  if (!name || name.length < 1 || name.length > 80) return c.json({ type: "bad-display-name" }, 400);
  const r = await q(
    `UPDATE users SET display_name=$1, updated_at=now() WHERE id=$2 AND deleted_at IS NULL RETURNING display_name`, [name, sub]);
  if (r.rowCount === 0) return c.body(null, 401);
  return c.json({ display_name: r.rows[0].display_name });
});

// Data export (guardrail #4 — honor data-export on request). The caller's own
// data only; NO secrets (refresh hashes, token hashes) ever leave. Read-only.
app.get("/auth/me/export", async (c) => {
  const t = bearer(c); if (!t) return c.body(null, 401);
  let sub: string, cid: string;
  try { const { verifyAccess } = await import("./auth/tokens.ts"); ({ sub, cid } = await verifyAccess(t)); }
  catch { return c.body(null, 401); }
  const cred = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!cred || cred.rowCount === 0) return c.body(null, 401);
  const user = (await q(`SELECT id, display_name, created_at FROM users WHERE id=$1 AND deleted_at IS NULL`, [sub])).rows[0];
  if (!user) return c.body(null, 401);
  const identities = (await q(`SELECT provider, email_verified, created_at FROM user_identities WHERE user_id=$1 ORDER BY created_at`, [sub])).rows;
  const memberships = (await q(
    `SELECT m.family_id, f.name AS family_name, m.role, m.status, m.joined_at
       FROM memberships m JOIN families f ON f.id=m.family_id WHERE m.user_id=$1 ORDER BY m.created_at`, [sub])).rows;
  const credentials = (await q(
    `SELECT kind, scopes, label, last_used_at, created_at FROM credentials WHERE user_id=$1 AND revoked_at IS NULL ORDER BY created_at`, [sub])).rows;
  return c.json({ exported_at: new Date().toISOString(), user, identities, memberships, credentials });
});

// Connected devices & apps (S6) — the caller's own active credentials (app
// sessions + CLI grants) with last-used metadata; `current` flags this session.
// No secrets. Read-only.
app.get("/auth/me/credentials", async (c) => {
  const t = bearer(c); if (!t) return c.body(null, 401);
  let sub: string, cid: string;
  try { const { verifyAccess } = await import("./auth/tokens.ts"); ({ sub, cid } = await verifyAccess(t)); }
  catch { return c.body(null, 401); }
  const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!self || self.rowCount === 0) return c.body(null, 401);
  const rows = (await q(
    `SELECT id, kind, label, scopes, family_scope, last_used_at, last_used_ip, created_at
       FROM credentials WHERE user_id=$1 AND revoked_at IS NULL ORDER BY last_used_at DESC NULLS LAST, created_at DESC`, [sub])).rows;
  return c.json({ credentials: rows.map((r: any) => ({ ...r, current: r.id === cid })) });
});

// Revoke one of the caller's own credentials (a session or CLI grant). Effective
// within one request — the per-request not-revoked check gates every token tied
// to it. Revoking the current credential signs this device out on its next call.
app.delete("/auth/me/credentials/:id", async (c) => {
  const t = bearer(c); if (!t) return c.body(null, 401);
  let sub: string, cid: string;
  try { const { verifyAccess } = await import("./auth/tokens.ts"); ({ sub, cid } = await verifyAccess(t)); }
  catch { return c.body(null, 401); }
  const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!self || self.rowCount === 0) return c.body(null, 401);
  const target = c.req.param("id");
  // own credentials only — never another user's (anti-IDOR)
  const r = await q(`UPDATE credentials SET revoked_at=now() WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL RETURNING 1`, [target, sub]);
  if (r.rowCount === 0) return c.body(null, 404);   // not yours / already revoked / unknown
  (await import("./auth/audit.ts")).audit("credential.revoke", { actorUserId: sub, detail: { credential_id: target } });
  return c.body(null, 204);
});

// Soft-delete the caller's account (operator-chosen: soft). Guarded by the
// ADR-0011 last-owner invariant: a SOLE active owner of a family that still has
// OTHER active members must transfer ownership first (→ 409 transfer-required;
// promote a member via /members/:uid:promote). Otherwise: mark users.deleted_at,
// drop the user's memberships to 'removed', and revoke ALL their credentials
// (every session/CLI dies on its next request via the not-revoked gate). A purge
// job hard-deletes later. Apple revokeToken folds in at S2 (Apple not built yet).
app.delete("/auth/me", async (c) => {
  const t = bearer(c); if (!t) return c.body(null, 401);
  let sub: string, cid: string;
  try { const { verifyAccess } = await import("./auth/tokens.ts"); ({ sub, cid } = await verifyAccess(t)); }
  catch { return c.body(null, 401); }
  const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!self || self.rowCount === 0) return c.body(null, 401);
  // families where the caller is the SOLE active owner AND others still belong
  const blocked = await q(
    `SELECT m.family_id, f.name FROM memberships m JOIN families f ON f.id=m.family_id
      WHERE m.user_id=$1 AND m.role='owner' AND m.status='active'
        AND (SELECT count(*) FROM memberships o WHERE o.family_id=m.family_id AND o.role='owner' AND o.status='active' AND o.user_id<>$1)=0
        AND (SELECT count(*) FROM memberships x WHERE x.family_id=m.family_id AND x.status='active' AND x.user_id<>$1)>0`,
    [sub]);
  if (blocked.rowCount && blocked.rowCount > 0)
    return c.json({ type: "transfer-required", families: blocked.rows }, 409);
  await q(`UPDATE users SET deleted_at=now() WHERE id=$1 AND deleted_at IS NULL`, [sub]);
  await q(`UPDATE memberships SET status='removed', updated_at=now() WHERE user_id=$1 AND status<>'removed'`, [sub]);
  await q(`UPDATE credentials SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`, [sub]);
  (await import("./auth/audit.ts")).audit("account.soft_delete", { actorUserId: sub });
  return c.body(null, 204);
});

app.post("/families", async (c) => {
  const t = bearer(c); if (!t) return c.body(null, 401);
  let sub: string;
  try {
    const { verifyAccess } = await import("./auth/tokens.ts");
    sub = (await verifyAccess(t)).sub;
  } catch { return c.body(null, 401); }
  const body = await c.req.json().catch(() => null);
  if (!body?.name || typeof body.name !== "string") return c.json({ type: "bad-name" }, 400);
  const { createFamily } = await import("./auth/identity.ts");
  const { familyId } = await createFamily(sub, body.name);
  return c.json({ familyId });
});

app.get("/.well-known/jwks.json", async (c) => {
  c.header("cache-control", "public, max-age=300");
  const { jwks } = await import("./auth/tokens.ts");
  return c.json(await jwks());
});

app.put("/families/:fid/cards/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!a.scopes.includes("content:write")) return c.json({ type: "forbidden" }, 403);
  const raw = await c.req.json().catch(() => null);
  if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
  let body: any = stripServerManaged(raw);          // mass-assignment: drop server fields
  body = stampProvenance(body, a.cred.id);           // un-forgeable provenance
  const parsed = BriefingCardSchema.safeParse({ ...body, id }); // path id wins
  if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
  // CL-2: type↔payload cross-check (zod validates the two independently).
  const cross = crossValidateCard(parsed.data as any);
  if (cross.length) return c.json({ type: "validation", issues: cross }, 422);
  return c.json(await repo.upsertCard(fid, id, parsed.data), 200);
});

app.get("/families/:fid/cards", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  return c.json(await repo.listCards(fid));
});

// Active member roster (member-gated — every member can see who's in the family).
// Pending members live in GET /invites (owner-gated approval queue), not here.
app.get("/families/:fid/members", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const rows = await q(
    `SELECT m.user_id AS uid, u.display_name, m.role, m.status, m.joined_at
       FROM memberships m JOIN users u ON u.id = m.user_id
      WHERE m.family_id = $1 AND m.status = 'active'
      ORDER BY (m.role = 'owner') DESC, m.joined_at`,
    [fid],
  );
  return c.json({ members: rows.rows });
});

app.delete("/families/:fid/cards/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!a.scopes.includes("content:write")) return c.json({ type: "forbidden" }, 403);
  return c.body(null, (await repo.softDeleteCard(fid, id)) ? 204 : 404);
});

app.post("/device/authorize", async (c) => {
  const { clientIp, hit } = await import("./auth/ratelimit.ts");
  const ip = clientIp(c);
  const rl = await hit(`ip:authorize:${ip}`, 600, 10);
  if (!rl.ok) return c.body(null, 429);
  const body = await c.req.json().catch(() => ({})); // permissive [E2EE hook]
  const { createAuthorization } = await import("./auth/device.ts");
  const { audit } = await import("./auth/audit.ts");
  const { device_code, user_code } = await createAuthorization(body?.client ?? "dayfold-cli", ip, c.req.header("user-agent") ?? null);
  await audit("device.authorize", { detail: { ip } });
  const base = `${new URL(c.req.url).origin}/device`;
  return c.json({ device_code, user_code, verification_uri: base, verification_uri_complete: `${base}?user_code=${user_code}`, expires_in: 600, interval: 5 });
});

app.post("/device/token", async (c) => {
  const { clientIp, hit } = await import("./auth/ratelimit.ts");
  const ip = clientIp(c);
  const rl = await hit(`ip:token:${ip}`, 600, 600);   // [I-2] anti-DoS, generous
  if (!rl.ok) return c.body(null, 429);
  const body = await c.req.json().catch(() => null);
  if (!body?.device_code) return c.json({ error: "invalid_request" }, 400);
  const { redeem } = await import("./auth/device.ts");
  const { mintAccess } = await import("./auth/tokens.ts");
  const { issueRefresh } = await import("./auth/refresh.ts");
  const out = await redeem(body.device_code, mintAccess, issueRefresh);
  if ("tokens" in out) {
    const { audit } = await import("./auth/audit.ts");
    await audit("device.token.redeemed", { detail: { device_code: body.device_code } });
    return c.json(out.tokens, 200);
  }
  return c.json({ error: out.error }, 400);
});

async function ownerGate(c: any, fid: string) {
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return { status: a.status };
  if (a.role !== "owner") return { status: 403 };
  if (a.cred.kind !== "app") return { status: 403 }; // [C2] reject cli/content-only
  return { sub: a.userId as string };
}

app.post("/families/:fid/device/approve", async (c) => {
  const fid = c.req.param("fid");
  const g = await ownerGate(c, fid);
  if ("status" in g) return c.body(null, g.status);
  const { isLocked, recordFailure, resetFailures } = await import("./auth/ratelimit.ts");
  const { audit } = await import("./auth/audit.ts");
  const lockKey = `account:approve:${g.sub}`;
  if (await isLocked(lockKey)) { await audit("device.lockout", { actorUserId: g.sub }); return c.body(null, 429); }
  const body = await c.req.json().catch(() => null);
  if (!body?.user_code) return c.json({ type: "bad-request" }, 400);
  const r = await q(
    `UPDATE device_authorizations SET status='approved', user_id=$1, family_id=$2, approved_at=now()
     WHERE user_code=$3 AND status='pending' AND expires_at > now() RETURNING device_code`,
    [g.sub, fid, body.user_code],
  );
  if (r.rowCount !== 1) { await recordFailure(lockKey, 900, 5, 900); return c.body(null, 404); } // uniform
  await resetFailures(lockKey);
  const { clientIp } = await import("./auth/ratelimit.ts");
  await audit("device.approve", { actorUserId: g.sub, familyId: fid, detail: { ip: clientIp(c) } });
  return c.body(null, 204);
});

app.post("/families/:fid/device/deny", async (c) => {
  const fid = c.req.param("fid");
  const g = await ownerGate(c, fid);
  if ("status" in g) return c.body(null, g.status);
  const body = await c.req.json().catch(() => null);
  if (!body?.user_code) return c.json({ type: "bad-request" }, 400);
  const r = await q(`UPDATE device_authorizations SET status='denied' WHERE user_code=$1 AND status='pending' AND expires_at > now() RETURNING device_code`, [body.user_code]);
  const { audit } = await import("./auth/audit.ts");
  if (r.rowCount === 1) await audit("device.deny", { actorUserId: g.sub, familyId: fid });
  return c.body(null, r.rowCount === 1 ? 204 : 404);
});

app.post("/families/:fid/invites", async (c) => {
  const fid = c.req.param("fid");
  const g = await ownerGate(c, fid);
  if ("status" in g) return c.body(null, g.status);
  const body = await c.req.json().catch(() => null);
  const mode = body?.mode;
  if (mode !== "qr" && mode !== "link") return c.json({ type: "bad-mode" }, 400);
  const role = body?.role ?? "adult";
  if (role !== "adult") return c.json({ type: "bad-role" }, 400);          // never owner/teen
  const maxUses = mode === "qr" ? 1 : Math.trunc(body?.max_uses ?? 1);
  if (maxUses < 1 || maxUses > 10) return c.json({ type: "bad-max-uses" }, 400);
  const { clientIp, hit } = await import("./auth/ratelimit.ts");
  if (!(await hit(`owner:mint:${g.sub}`, 600, 20)).ok) return c.body(null, 429);
  // live-invite + pending caps (expires_at-filtered) [I3]
  const caps = await q(
    `SELECT (SELECT count(*) FROM invites WHERE family_id=$1 AND status='active' AND expires_at>now()) AS inv,
            (SELECT count(*) FROM memberships WHERE family_id=$1 AND status='pending') AS pend`, [fid]);
  if (Number(caps.rows[0].inv) >= 10 || Number(caps.rows[0].pend) >= 20) return c.body(null, 429);
  const { createInvite } = await import("./auth/invites.ts");
  const { inviteId, token } = await createInvite(fid, g.sub, mode, role, maxUses);
  const { audit } = await import("./auth/audit.ts");
  await audit("invite.mint", { actorUserId: g.sub, familyId: fid, detail: { mode, role, max_uses: maxUses } });
  const expires = await q(`SELECT expires_at FROM invites WHERE id=$1`, [inviteId]);
  c.header("cache-control", "no-store, no-transform");                    // BREACH: raw token
  return c.json({ invite_id: inviteId, token, url: `${new URL(c.req.url).origin}/invite/${token}`, role, mode, expires_at: expires.rows[0].expires_at }, 201);
});

app.post("/invites:redeem", async (c) => {
  const t = bearer(c); if (!t) return c.body(null, 401);
  let sub: string;
  try { const { verifyAccess } = await import("./auth/tokens.ts"); sub = (await verifyAccess(t)).sub; }
  catch { return c.body(null, 401); }
  const { isLocked, recordFailure, resetFailures } = await import("./auth/ratelimit.ts");
  const key = `account:redeem:${sub}`;
  if (await isLocked(key)) return c.body(null, 429);
  const body = await c.req.json().catch(() => null);
  if (!body?.token) return c.json({ type: "bad-request" }, 400);
  const { redeem } = await import("./auth/invites.ts");
  const out = await redeem(body.token, sub);
  const { audit } = await import("./auth/audit.ts");
  if ("notfound" in out) { await recordFailure(key, 900, 5, 900); return c.body(null, 404); }
  if ("capfull" in out) return c.body(null, 429);
  await resetFailures(key);
  if ("conflict" in out) {
    if (out.conflict === "pending") return c.json({ status: "pending" }, 200);
    return c.json({ type: out.conflict === "active" ? "already-member" : "removed" }, 409);
  }
  const fam = await q(`SELECT name FROM families WHERE id=$1`, [out.family_id]);
  await audit("invite.redeem", { actorUserId: sub, familyId: out.family_id });
  return c.json({ family_id: out.family_id, family_name: fam.rows[0]?.name, role: out.role, status: "pending" }, 200);
});

// Member action routes: POST /families/:fid/members/<uid>:approve  and  :<uid>:decline
// Hono treats ':uid:approve' as a single param name that greedily matches anything,
// so we use a single wildcard route and dispatch on the ':action' suffix.
app.post("/families/:fid/members/*", async (c) => {
  const fid = c.req.param("fid");
  // Extract the trailing segment after /members/ from the raw URL path
  const pathname = new URL(c.req.url).pathname;
  const membersPrefix = `/families/${fid}/members/`;
  const seg: string = pathname.startsWith(membersPrefix) ? pathname.slice(membersPrefix.length) : "";
  const colonIdx = seg.lastIndexOf(":");
  if (colonIdx === -1) return c.body(null, 404);
  const uid = seg.slice(0, colonIdx);
  const action = seg.slice(colonIdx + 1);
  if (action !== "approve" && action !== "decline" && action !== "promote") return c.body(null, 404);
  const g = await ownerGate(c, fid); if ("status" in g) return c.body(null, g.status);
  if (action === "promote") {
    // Transfer/share ownership: promote an active member to owner so a sole owner
    // can delete/leave (ADR 0011 last-owner invariant). Idempotent.
    const r = await q(`UPDATE memberships SET role='owner', updated_at=now() WHERE user_id=$1 AND family_id=$2 AND status='active' AND role<>'owner' RETURNING 1`, [uid, fid]);
    if (r.rowCount === 1) { (await import("./auth/audit.ts")).audit("member.promote", { actorUserId: g.sub, familyId: fid, detail:{ uid } }); return c.body(null, 204); }
    const cur = await q(`SELECT role, status FROM memberships WHERE user_id=$1 AND family_id=$2`, [uid, fid]);
    if (cur.rowCount === 0 || cur.rows[0].status !== "active") return c.body(null, 404);
    return c.body(null, 200);   // already an owner
  } else if (action === "approve") {
    const r = await q(`UPDATE memberships SET status='active', joined_at=now() WHERE user_id=$1 AND family_id=$2 AND status='pending' RETURNING role`, [uid, fid]);
    if (r.rowCount === 1) { (await import("./auth/audit.ts")).audit("invite.approve", { actorUserId: g.sub, familyId: fid, detail:{ uid } }); return c.body(null, 204); }
    const cur = await q(`SELECT status FROM memberships WHERE user_id=$1 AND family_id=$2`, [uid, fid]);
    if (cur.rowCount === 0) return c.body(null, 404);
    return c.body(null, cur.rows[0].status === "active" ? 200 : 409);
  } else {
    const r = await q(`UPDATE memberships SET status='removed' WHERE user_id=$1 AND family_id=$2 AND status='pending' RETURNING 1`, [uid, fid]);
    if (r.rowCount === 1) (await import("./auth/audit.ts")).audit("invite.decline", { actorUserId: g.sub, familyId: fid, detail:{ uid } });
    return c.body(null, r.rowCount === 1 ? 204 : 404);
  }
});

// [C3] Remove a member — ≥1-owner invariant enforced via row-lock (FOR UPDATE locks
// the active-owner rows, preventing concurrent double-remove that would leave 0 owners).
app.delete("/families/:fid/members/:uid", async (c) => {
  const fid = c.req.param("fid"), uid = c.req.param("uid");
  const g = await ownerGate(c, fid); if ("status" in g) return c.body(null, g.status);
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const owners = await client.query(
      `SELECT user_id FROM memberships WHERE family_id=$1 AND role='owner' AND status='active' FOR UPDATE`,
      [fid]
    );
    const targetIsOwner = owners.rows.some((r: any) => r.user_id === uid);
    if (targetIsOwner && (owners.rowCount ?? 0) < 2) {
      await client.query("ROLLBACK");
      return c.body(null, 409); // last-owner — refuse removal
    }
    const r = await client.query(
      `UPDATE memberships SET status='removed' WHERE user_id=$1 AND family_id=$2 AND status IN ('active','pending') RETURNING 1`,
      [uid, fid]
    );
    await client.query("COMMIT");
    if ((r.rowCount ?? 0) !== 1) return c.body(null, 404);
  } catch (e) { await client.query("ROLLBACK"); throw e; } finally { client.release(); }
  (await import("./auth/audit.ts")).audit("member.remove", { actorUserId: g.sub, familyId: fid, detail: { uid } });
  return c.body(null, 204);
});

app.delete("/families/:fid/invites/:id", async (c) => {
  const fid = c.req.param("fid"), iid = c.req.param("id");
  const g = await ownerGate(c, fid); if ("status" in g) return c.body(null, g.status);
  const r = await q(`UPDATE invites SET status='revoked' WHERE id=$1 AND family_id=$2 AND status='active' RETURNING 1`, [iid, fid]);
  if (r.rowCount === 1) (await import("./auth/audit.ts")).audit("invite.revoke", { actorUserId: g.sub, familyId: fid, detail:{ invite_id: iid } });
  return c.body(null, 204);                                               // sticky: no-op if already non-active
});

app.get("/families/:fid/invites", async (c) => {
  const fid = c.req.param("fid");
  const g = await ownerGate(c, fid); if ("status" in g) return c.body(null, g.status);
  const invites = await q(`SELECT id, role, mode, max_uses, used_count, expires_at, created_at FROM invites WHERE family_id=$1 AND status='active' AND expires_at>now() ORDER BY created_at DESC`, [fid]);
  const pending = await q(
    `SELECT m.user_id AS uid, u.display_name, ui.provider, ui.provider_uid, ui.email_verified,
            m.role, m.invite_id, m.created_at AS requested_at
       FROM memberships m JOIN users u ON u.id=m.user_id
       LEFT JOIN user_identities ui ON ui.user_id=m.user_id
      WHERE m.family_id=$1 AND m.status='pending' ORDER BY m.created_at`, [fid]);
  return c.json({ invites: invites.rows, pending: pending.rows });        // [I4] identity in the queue
});

app.get("/families/:fid/sync", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const cursor = c.req.query("since");
  let su: string | null = null, si: string | null = null;
  if (cursor) {
    // [F4] validate the cursor; malformed → 400 (not a silent full-table re-scan).
    const parts = Buffer.from(cursor, "base64").toString().split("|");
    if (parts.length !== 2 || Number.isNaN(Date.parse(parts[0]))) return problem(c, 400, "bad-cursor");
    su = parts[0]; si = parts[1];
  }
  const rows = await repo.syncCards(fid, su, si);
  const live = rows.filter((r: any) => !r.deleted_at);
  const tombstones = rows.filter((r: any) => r.deleted_at).map((r: any) => ({ type: "card", id: r.id }));
  const last = rows[rows.length - 1];
  // [F3] cursor carries the EXACT Postgres timestamptz string (db.ts type parser
  // returns it raw) — no JS Date ms-truncation, no skipped rows.
  const next = last ? Buffer.from(`${last.updated_at}|${last.id}`).toString("base64") : cursor;
  return c.json({ changes: { cards: live }, tombstones, next_cursor: next, has_more: rows.length >= repo.SYNC_LIMIT });
});
