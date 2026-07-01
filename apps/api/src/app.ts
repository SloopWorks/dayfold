// M0 content API (feed surface): household-token auth → tenant-explicit
// card routes + keyset sync. Hono (runs on Vercel + locally + app.request()-testable).
import { Hono } from "hono";
import { bodyLimit } from "hono/body-limit";
import { q, pool } from "./db.ts";
import { stripServerManaged, stampProvenance, constantTimeEqual } from "./security.ts";
import { BriefingCardSchema } from "./generated/content.ts";
import { crossValidateCard, blockPayloadIssues, hubTimelineIssues } from "./content-validation.ts";
import { validateHubMedia, validateCardMedia, validateBlockPayloadMedia, normalizedAccent } from "./media-validation.ts";
import * as repo from "./repo.ts";
// Auth imports are lazy (dynamic) so that api.test.ts (no AUTH_* env) can still
// load app.ts without triggering the module-level env-guard throws in tokens.ts.
import { authorizeTenant } from "./auth/middleware.ts";
import { requireScope, grantScopes, resolveGrants, scopeAllows, grantedHubIds } from "./auth/scope.ts";
import { cardVisible } from "./content/visibility.ts";
import { isMemberWrite, memberDeleteForbidden, ifMatchFails, blockState, hubWriteGate } from "./content/write-guard.ts";
import { findOp, recordOp } from "./content/oplog.ts";
import { CONTENT_TOMBSTONE_RETENTION_DAYS } from "./auth/sweep.ts";
import * as hubs from "./content/hubs.ts";
import { HubSchema, SectionSchema, BlockSchema } from "./generated/content.ts";

export const app = new Hono();

// Liveness — no auth, no DB (isolates routing/cold-start from the DB path).
app.get("/health", (c) => c.json({ ok: true, surface: "m0" }));

// Retention sweep, run by Vercel Cron (vercel.json `crons`). Vercel attaches
// `Authorization: Bearer $CRON_SECRET` to cron requests — verify it constant-time so
// the endpoint can't be triggered by anyone (abuse/DoS floor). Unconfigured ⇒ 404
// (invisible). Idempotent; only deletes safely-dead auth ephemera (sweep.ts).
app.get("/cron/sweep", async (c) => {
  const secret = process.env.CRON_SECRET || "";
  if (!secret) return c.body(null, 404);
  if (!constantTimeEqual(bearer(c) ?? "", secret)) return c.body(null, 401);
  const { sweep } = await import("./auth/sweep.ts");
  return c.json(await sweep());
});

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

// Content scope is gated by `requireScope` (ADR 0029, src/auth/scope.ts): resolved
// per request from `credential_grants`, never from the token or `a.scopes`.

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
    `INSERT INTO credentials(id,user_id,kind,scopes) VALUES ($1,$2,'app','{content:read,content:write,content:delete}')`,
    [credentialId, userId],
  );
  // content:delete (W4): members may delete content — the route's author-gate restricts
  // each to their OWN authored blocks (operator-ratified 2026-06-29; ADR 0038 §W4).
  await grantScopes(credentialId, ["content:read", "content:write", "content:delete"]);   // ADR 0029 grant rows
  const { mintAccess } = await import("./auth/tokens.ts");
  const { issueRefresh } = await import("./auth/refresh.ts");
  const access = await mintAccess({ sub: userId, cid: credentialId });
  const refresh = await issueRefresh(credentialId);
  return c.json({ access, refresh });
});

// S2 (ADR 0023/0027): real sign-in. Verify a Firebase ID token (Google/Apple),
// then mint OUR EdDSA access + rotating refresh — identical right-half to dev-token.
// Always on (prod included); identity proof is the Firebase signature, not a dev gate.
app.post("/auth/firebase", async (c) => {
  const body = await c.req.json().catch(() => null);
  const idToken = body?.idToken;
  if (!idToken || typeof idToken !== "string") return c.json({ type: "missing-id-token" }, 400);
  const projectId = process.env.FIREBASE_PROJECT_ID;
  if (!projectId) return c.json({ type: "auth-unconfigured" }, 503);
  const { FirebaseVerifier, findOrCreateUser, mintCredentialFor } = await import("./auth/identity.ts");
  // Emulator mode skips signature verification — NEVER honor it in prod/preview,
  // even if the host env var leaks in (defense in depth; mirrors the dev-token gate).
  const env = process.env.VERCEL_ENV;
  const emulator = !!process.env.FIREBASE_AUTH_EMULATOR_HOST && env !== "production" && env !== "preview";
  const verifier = new FirebaseVerifier({ projectId, emulator });
  const idn = await verifier.verify(idToken).catch((e) => {
    console.warn(`[auth/firebase] verify failed: ${e?.message}`);
    return null;
  });
  if (!idn) return c.json({ type: "bad-identity" }, 401);
  const { userId } = await findOrCreateUser(idn);
  const { credentialId } = await mintCredentialFor(userId);
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
  const grants = await resolveGrants(cid);   // ADR 0029: the calling credential's resolved scope (not from the token)
  return c.json({ family_id, families: r.rows, grants });
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

// ── AUTH-S6-D Phase 2 deep-link association files (App Links / Universal Links) ──
// Served on the API origin (no new domain — ADR 0011 §6). The phone verifies these
// to open `/device?user_code=…` straight into the app. Cert/app identifiers come
// from env so the operator can add the RELEASE Android fingerprint + the real Apple
// app id without a code change; the Android debug fingerprint is the dogfood default.
const ANDROID_DEBUG_SHA256 =
  "15:A0:66:E7:BF:25:07:CB:0E:4D:5C:24:DE:FC:C9:75:06:EE:FF:19:B1:06:CB:7F:76:DF:C0:E9:23:00:87:6B";

app.get("/.well-known/assetlinks.json", (c) => {
  // ANDROID_CERT_SHA256 = comma-separated fingerprints (release[,debug]); falls
  // back to the debug cert for local/dogfood builds.
  const fps = (process.env.ANDROID_CERT_SHA256 || ANDROID_DEBUG_SHA256)
    .split(",").map((s) => s.trim()).filter(Boolean);
  c.header("cache-control", "public, max-age=300");
  return c.json([{
    relation: ["delegate_permission/common.handle_all_urls"],
    target: { namespace: "android_app", package_name: "com.sloopworks.dayfold", sha256_cert_fingerprints: fps },
  }]);
});

app.get("/.well-known/apple-app-site-association", (c) => {
  // APPLE_APP_ID = "<TeamID>.<bundleId>" — placeholder until the iOS host ships.
  const appID = process.env.APPLE_APP_ID || "TEAMID.com.sloopworks.dayfold";
  c.header("content-type", "application/json");   // AASA must be application/json, no extension
  c.header("cache-control", "public, max-age=300");
  return c.json({ applinks: { apps: [], details: [{ appID, paths: ["/device", "/device?*"] }] } });
});

// Browser landing for the verification URI (no app / unverified link / desktop):
// tells the human to open Dayfold or type the code. The QR/link carries user_code.
app.get("/device", (c) => {
  const code = (c.req.query("user_code") || "").replace(/[^A-Za-z0-9-]/g, "").slice(0, 9);
  c.header("content-type", "text/html; charset=utf-8");
  return c.html(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Approve a device · Dayfold</title>
<div style="font-family:system-ui,sans-serif;max-width:30rem;margin:14vh auto;padding:0 1.5rem;color:#271814">
  <div style="font-weight:700;letter-spacing:.04em;color:#8C726B;font-size:.8rem">DAYFOLD</div>
  <h1 style="font-size:1.6rem;margin:.4rem 0 .8rem">Approve this device</h1>
  <p style="line-height:1.5;color:#5A423C">Open the Dayfold app on your phone to review and approve this sign-in.
  If it doesn't open automatically, go to <b>Connect a device</b> and enter this code:</p>
  ${code ? `<div style="font-family:ui-monospace,monospace;font-size:1.8rem;font-weight:700;letter-spacing:.12em;background:#FCEBE6;border-radius:.8rem;padding:1rem;text-align:center;margin:1rem 0">${code}</div>` : ""}
  <p style="color:#8C726B;font-size:.9rem">Only approve a device you started signing in on yourself.</p>
</div>`);
});

app.put("/families/:fid/cards/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  { const e = idError(id); if (e) return c.json(e, 422); }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, "content", "write"))) return c.json({ type: "forbidden" }, 403);
  // Visibility-on-write (ADR 0038/0030): a member cannot overwrite (or probe the
  // existence of) a restricted card they can't see — invisible existing card → uniform
  // 404. A new id or a family/own card → visible → proceed.
  {
    const cur = await q(`SELECT visibility, audience FROM briefing_cards WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [fid, id]);
    if (cur.rowCount && !cardVisible(cur.rows[0], { userId: a.userId, legacy: a.legacy })) return c.body(null, 404);
  }
  const raw = await c.req.json().catch(() => null);
  if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
  // ADR 0030: visibility + author-stamped audience are authoring fields OUTSIDE the
  // typed-card schema — extract + validate them, then strip before the strict parse.
  if (raw.visibility !== undefined && raw.visibility !== "family" && raw.visibility !== "restricted")
    return c.json({ type: "validation", issues: [{ path: ["visibility"], message: "family|restricted" }] }, 422);
  const visibility = raw.visibility === "restricted" ? "restricted" : "family";
  let audience: string[] | undefined;
  if (visibility === "restricted") {
    if (raw.audience !== undefined && (!Array.isArray(raw.audience) || raw.audience.some((x: any) => typeof x !== "string")))
      return c.json({ type: "validation", issues: [{ path: ["audience"], message: "string[] of user ids" }] }, 422);
    audience = Array.isArray(raw.audience) ? raw.audience : [];
  }
  const { visibility: _v, audience: _a, ...rest } = raw;          // strip before strict schema parse
  let body: any = stripServerManaged(rest);          // mass-assignment: drop server fields
  body = stampProvenance(body, a.cred.id);           // un-forgeable provenance
  const parsed = BriefingCardSchema.safeParse({ ...body, id }); // path id wins
  if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
  // CL-2: type↔payload cross-check (zod validates the two independently).
  const cross = crossValidateCard(parsed.data as any);
  if (cross.length) return c.json({ type: "validation", issues: cross }, 422);
  // ADR 0036: hardened image-URL + curated-icon + accent validation on card.media
  // (host allowlist / SVG / curated-icon are NOT expressible in zod).
  const media = (parsed.data as any).media;
  const mediaIssues = validateCardMedia(media);
  if (mediaIssues.length) return c.json({ type: "validation", issues: mediaIssues }, 422);
  if (media?.accentColor) media.accentColor = normalizedAccent(media.accentColor);  // lowercase on write
  return c.json(await repo.upsertCard(fid, id, { ...parsed.data, visibility, audience }), 200);
});

app.get("/families/:fid/cards", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, "content", "read"))) return c.json({ type: "forbidden" }, 403);
  return c.json(await repo.listCards(fid, { userId: a.userId, legacy: a.legacy }));
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
  { const e = idError(id); if (e) return c.json(e, 422); }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, "content", "write"))) return c.json({ type: "forbidden" }, 403);
  return c.body(null, (await repo.softDeleteCard(fid, id)) ? 204 : 404);
});

// ---- Hub content API (ADR 0006 hubs · ADR 0029 scope · ADR 0030 visibility) ----
// Hub ids are resource-scope keys; constrain the charset so a ':' can't ambiguate a
// grant string (`hub:<id>:read`). Sections/blocks are reachable ONLY via the hub
// tree (visibility-gated there), so they inherit the hub's visibility.
// Content resource ids become DB primary keys (path-supplied). Validate them
// uniformly across cards/hubs/sections/blocks — the hub route already guarded
// this; the others didn't. Returns a 422 body when malformed, else null.
const RESOURCE_ID = /^[A-Za-z0-9_-]{1,128}$/;
const idError = (id: string) =>
  RESOURCE_ID.test(id) ? null : { type: "validation", issues: [{ path: ["id"], message: "id must match [A-Za-z0-9_-]{1,128}" }] };

app.get("/families/:fid/hubs", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const grants = await resolveGrants(a.cred.id);
  const hubGrantIds = grantedHubIds(grants, "read");          // null = global content:read
  if (hubGrantIds !== null && hubGrantIds.length === 0) return c.json({ type: "forbidden" }, 403);
  return c.json(await hubs.listHubs(fid, { userId: a.userId, legacy: a.legacy }, hubGrantIds));
});

app.get("/families/:fid/hubs/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, `hub:${id}`, "read"))) return c.body(null, 404); // uniform 404 on scope-miss
  const hub = await hubs.getHub(fid, id);
  const caller = { userId: a.userId, legacy: a.legacy };
  if (!hub) return c.body(null, 404);
  const allow = await hubs.allowListFor(fid, id);
  if (!hubs.hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
  return c.json(hub);
});

app.get("/families/:fid/hubs/:id/tree", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, `hub:${id}`, "read"))) return c.body(null, 404);
  const hub = await hubs.getHub(fid, id);
  const caller = { userId: a.userId, legacy: a.legacy };
  if (!hub) return c.body(null, 404);
  const allow = await hubs.allowListFor(fid, id);
  if (!hubs.hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
  return c.json(await hubs.getHubTree(fid, id));   // sections/blocks inherit hub visibility (gated above)
});

// "Who can see this hub" (ADR 0030) — the roster + permitted flag for the sheet.
// Same gate as /tree: caller must be able to SEE the hub (else uniform 404), so a
// non-permitted member can't enumerate a restricted hub's audience.
app.get("/families/:fid/hubs/:id/audience", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, `hub:${id}`, "read"))) return c.body(null, 404);
  const hub = await hubs.getHub(fid, id);
  const caller = { userId: a.userId, legacy: a.legacy };
  if (!hub) return c.body(null, 404);
  const allow = await hubs.allowListFor(fid, id);
  if (!hubs.hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
  return c.json({ visibility: hub.visibility, members: await hubs.hubAudience(fid, id) });
});

app.put("/families/:fid/hubs/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  { const e = idError(id); if (e) return c.json(e, 422); }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, `hub:${id}`, "write"))) return c.json({ type: "forbidden" }, 403);
  const raw = await c.req.json().catch(() => null);
  if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
  // visibility + allow-list authoring (outside the strict hub schema).
  if (raw.visibility !== undefined && raw.visibility !== "family" && raw.visibility !== "restricted")
    return c.json({ type: "validation", issues: [{ path: ["visibility"], message: "family|restricted" }] }, 422);
  const visibility = raw.visibility === "restricted" ? "restricted" : "family";
  let audience: string[] | undefined;
  if (visibility === "restricted") {
    if (raw.audience !== undefined && (!Array.isArray(raw.audience) || raw.audience.some((x: any) => typeof x !== "string")))
      return c.json({ type: "validation", issues: [{ path: ["audience"], message: "string[] of user ids" }] }, 422);
    audience = Array.isArray(raw.audience) ? raw.audience : [];
  }
  const { visibility: _v, audience: _a, ...rest } = raw;
  const parsed = HubSchema.safeParse({ ...rest, id });
  if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
  // ADR 0036: hardened image-URL + curated-icon + accent validation.
  const hubMediaIssues = validateHubMedia((parsed.data as any).media);
  if (hubMediaIssues.length) return c.json({ type: "validation", issues: hubMediaIssues }, 422);
  { const m = (parsed.data as any).media; if (m?.accentColor) m.accentColor = normalizedAccent(m.accentColor); }
  const timelineIssues = hubTimelineIssues(parsed.data as any);
  if (timelineIssues.length) return c.json({ type: "validation", issues: timelineIssues }, 422);
  const caller = { userId: a.userId, legacy: a.legacy };
  const existing = await hubs.getHub(fid, id);
  if (existing) {
    const allow = await hubs.allowListFor(fid, id);
    const permitted = () => !!caller.userId && allow.has(caller.userId);
    // Visibility-on-write (ADR 0038): a restricted hub the caller can't see is a uniform
    // 404 (no existence oracle) — takes precedence over the 403 author-gate below.
    if (!hubs.hubVisible(existing, caller, permitted)) return c.body(null, 404);
    // ADR 0030 §6: only the author / an already-permitted member / legacy may rewrite
    // an existing hub. A fresh hub's author is the caller → allowed.
    if (!caller.legacy && existing.created_by && existing.created_by !== caller.userId && !permitted())
      return c.json({ type: "forbidden" }, 403);
  }
  return c.json(await hubs.upsertHub(fid, id, parsed.data, caller, visibility, audience), 200);
});

app.post("/families/:fid/hubs/:id/archive", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, `hub:${id}`, "write"))) return c.json({ type: "forbidden" }, 403);
  return c.body(null, (await hubs.archiveHub(fid, id)) ? 204 : 404);
});

app.delete("/families/:fid/hubs/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  // validate BEFORE building the `hub:${id}` scope string — a ':' in the id could
  // otherwise ambiguate the grant check (same reason the hub PUT guards the id).
  { const e = idError(id); if (e) return c.json(e, 422); }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, `hub:${id}`, "write"))) return c.json({ type: "forbidden" }, 403);
  return c.body(null, (await hubs.softDeleteHub(fid, id)) ? 204 : 404);
});

app.put("/families/:fid/sections/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  { const e = idError(id); if (e) return c.json(e, 422); }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const raw = await c.req.json().catch(() => null);
  if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
  const hubId = typeof raw.hubId === "string" ? raw.hubId : null;
  if (!hubId) return c.json({ type: "validation", issues: [{ path: ["hubId"], message: "required" }] }, 422);
  // Visibility-on-write (ADR 0038): restricted-invisible hub → 404 (no oracle); absent
  // parent hub → 409 give-up (§6.2, matches the existing parent-must-exist contract);
  // 403 only visible-but-scope-denied.
  const gate = await hubWriteGate(fid, hubId, { userId: a.userId, legacy: a.legacy, cred: a.cred });
  if (gate === "invisible") return c.body(null, 404);
  if (gate === "denied") return c.json({ type: "forbidden" }, 403);
  if (gate === "absent") return c.json({ type: "conflict", detail: "parent hub missing or deleted" }, 409);
  const { hubId: _h, ...rest } = raw;
  const parsed = SectionSchema.safeParse({ ...rest, id });
  if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
  // If-Match optimistic concurrency: stale base version → 412 (ADR 0038 §6.2).
  const ifMatch = c.req.header("if-match");
  if (ifMatch) {
    const cur = await q(`SELECT version FROM sections WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [fid, id]);
    if (ifMatchFails(ifMatch, cur.rowCount ? Number(cur.rows[0].version) : null)) return c.body(null, 412);
  }
  const row = await hubs.upsertSection(fid, id, hubId, parsed.data);
  return row ? c.json(row, 200) : c.json({ type: "conflict", detail: "parent hub missing or deleted" }, 409);
});

app.put("/families/:fid/blocks/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  { const e = idError(id); if (e) return c.json(e, 422); }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const raw = await c.req.json().catch(() => null);
  if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
  const sectionId = typeof raw.sectionId === "string" ? raw.sectionId : null;
  if (!sectionId) return c.json({ type: "validation", issues: [{ path: ["sectionId"], message: "required" }] }, 422);
  const caller = { userId: a.userId, legacy: a.legacy, cred: a.cred };
  // Resolve the owning hub (via the live section); 409 if the parent is gone → the
  // outbox sender gives up (distinct from 412 stale / 410 tombstone — ADR 0038 §6.2).
  const hubId = await hubs.liveHubOfSection(fid, sectionId);
  if (!hubId) return c.json({ type: "conflict", detail: "parent section missing or deleted" }, 409);
  // Visibility-on-write (ADR 0038): a restricted hub the caller can't see → uniform 404
  // (no existence oracle); 403 only when the hub is VISIBLE but scope denies the write.
  // (`absent` can't happen here — liveHubOfSection only resolves a live hub — but map it
  // to the same parent-gone 409 defensively against a delete race.)
  const gate = await hubWriteGate(fid, hubId, caller);
  if (gate === "invisible") return c.body(null, 404);
  if (gate === "denied") return c.json({ type: "forbidden" }, 403);
  if (gate === "absent") return c.json({ type: "conflict", detail: "parent section missing or deleted" }, 409);
  const { sectionId: _s, ...rest } = raw;
  const body = stampProvenance(rest, a.cred.id);     // un-forgeable provenance
  const parsed = BlockSchema.safeParse({ ...body, id });
  if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
  // BlockSchema.payload is z.any() (codegen stub) — validate the payload here (ADR 0035;
  // gated to plaintext-M0: a ciphertext payload is opaque, ADR 0038 §6.2).
  const payloadIssues = blockPayloadIssues(parsed.data);
  if (payloadIssues.length) return c.json({ type: "validation", issues: payloadIssues }, 422);
  // ADR 0036: block image enrichment rides the payload (link/document thumbnailUrl,
  // contact avatarUrl + accentColor). Same hardened rule.
  const blockMediaIssues = validateBlockPayloadMedia((parsed.data as any).type, (parsed.data as any).payload);
  if (blockMediaIssues.length) return c.json({ type: "validation", issues: blockMediaIssues }, 422);
  { const p = (parsed.data as any).payload; if (p?.accentColor) p.accentColor = normalizedAccent(p.accentColor); }
  // op_id idempotency (ADR 0039 §6.5): a retried/echoed op short-circuits to the recorded
  // result before the 410/412 gates (else a member's own retry would 412 on its echo).
  const opId = c.req.header("idempotency-key");
  if (opId) {
    const prior = await findOp(fid, opId);
    if (prior) {
      const existing = prior.result_ref ? await hubs.getBlock(fid, prior.result_ref) : null;
      return existing ? c.json(existing, 200) : c.body(null, 410); // applied then deleted → gone
    }
  }
  const member = isMemberWrite(a);
  const st = await blockState(fid, id);
  // 410-on-tombstone: a member write never resurrects a soft-deleted block (ADR 0038 §6.3).
  if (member && st.deleted) return c.body(null, 410);
  // If-Match optimistic concurrency: stale base version → 412 re-merge-retry (ADR 0038 §6.2).
  if (ifMatchFails(c.req.header("if-match"), st.deleted ? null : st.version)) return c.body(null, 412);
  const row = await hubs.upsertBlock(fid, id, sectionId, parsed.data, { allowResurrect: !member, createdBy: a.userId });
  if (!row) {
    // member path + lost the resurrection race → the block was tombstoned → 410; else the
    // parent section vanished between resolution and write → 409 give-up.
    return member && st.exists ? c.body(null, 410) : c.json({ type: "conflict", detail: "parent section missing or deleted" }, 409);
  }
  if (opId) await recordOp(fid, opId, "block", id, Number(row.version));
  return c.json(row, 200);
});

// W4 — soft-delete a block (ADR 0038). Authz layers (no existence oracle): block-absent
// → 404; hub the caller can't see → 404; lacks `content:delete` (carved out of write so a
// stolen write token can't mass-delete) → 403; member deleting content they didn't author
// → 403 (loop-authored is undeletable by members; owner is NOT a delete override, ADR 0030
// §7). op_id idempotency makes a drained/retried delete safe (a re-delete is 204, not 404).
app.delete("/families/:fid/blocks/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  { const e = idError(id); if (e) return c.json(e, 422); }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const caller = { userId: a.userId, legacy: a.legacy };
  const opId = c.req.header("idempotency-key");
  // Idempotent replay: the delete already applied under this op_id → 204 (before the
  // 404-on-tombstone below, so a drained/retried delete converges instead of 404ing).
  if (opId && (await findOp(fid, opId))) return c.body(null, 204);
  const blk = await hubs.blockForDelete(fid, id);
  if (!blk) return c.body(null, 404);                                  // never existed
  // Visibility gate FIRST (no oracle): a block in a hub the caller can't see → 404,
  // regardless of scope. Resolve the parent hub via the (possibly tombstoned) section.
  const hubId = await hubs.liveHubOfSection(fid, blk.section_id);
  if (!hubId) return c.body(null, 404);                                // parent gone → unreachable
  const hub = await hubs.getHub(fid, hubId);
  if (!hub) return c.body(null, 404);
  const allow = await hubs.allowListFor(fid, hubId);
  if (!hubs.hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
  // content:delete is its OWN scope (not implied by content:write).
  if (!(await requireScope(a.cred.id, "content", "delete"))) return c.json({ type: "forbidden" }, 403);
  // Author-gate: a member may delete only what they authored. Loop/CLI authoring (legacy
  // or non-app credential) is exempt — it's the operator/loop, not a family member.
  if (memberDeleteForbidden(a, blk.created_by)) return c.json({ type: "forbidden" }, 403);
  if (blk.deleted) { if (opId) await recordOp(fid, opId, "block", id, null); return c.body(null, 204); } // already gone = idempotent
  const ok = await hubs.softDeleteBlock(fid, id);
  if (opId) await recordOp(fid, opId, "block", id, null);
  return c.body(null, ok ? 204 : 404);
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

// Consent preview for the approval screen (RFC 8628 §5.4): the owner's app looks
// up a pending user_code BEFORE approving, so it can render what it's authorizing.
// Session-auth (not family-scoped — the family isn't chosen yet). Shares the
// `account:approve:<sub>` lockout with approve (lookup+approve = one abuse surface),
// uniform 404 on miss/expired, never leaks device_code/user_id/credential.
app.get("/device/pending", async (c) => {
  const t = bearer(c); if (!t) return c.body(null, 401);
  let sub: string, cid: string;
  try { const { verifyAccess } = await import("./auth/tokens.ts"); ({ sub, cid } = await verifyAccess(t)); }
  catch { return c.body(null, 401); }
  const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!self || self.rowCount === 0) return c.body(null, 401);
  const { isLocked, recordFailure } = await import("./auth/ratelimit.ts");
  const { audit } = await import("./auth/audit.ts");
  const lockKey = `account:approve:${sub}`;
  if (await isLocked(lockKey)) { await audit("device.lockout", { actorUserId: sub }); return c.body(null, 429); }
  const userCode = c.req.query("user_code");
  if (!userCode) return c.json({ type: "bad-request" }, 400);
  const r = await q(
    `SELECT user_code, client, origin_ip, origin_ua, created_at, expires_at
       FROM device_authorizations WHERE user_code=$1 AND status='pending' AND expires_at > now()`,
    [userCode],
  );
  if (r.rowCount !== 1) { await recordFailure(lockKey, 900, 5, 900); return c.json({ type: "not-found" }, 404); } // uniform
  const row = r.rows[0];
  const { classifyOrigin } = await import("./auth/origin.ts");
  await audit("device.lookup", { actorUserId: sub, detail: { user_code: userCode } });
  return c.json({
    user_code: row.user_code, client: row.client,
    origin_ip: row.origin_ip, origin_ua: row.origin_ua,
    origin_kind: classifyOrigin(row.origin_ip),
    created_at: row.created_at, expires_at: row.expires_at,
  });
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
    const r = await q(`UPDATE memberships SET status='active', joined_at=now() WHERE user_id=$1 AND family_id=$2 AND status='pending' RETURNING 1`, [uid, fid]);  // role unused — only rowCount matters (S4 follow-2)
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
  // ONE row per pending membership. A multi-identity user (e.g. Google + Apple at
  // S2) would fan out across a plain LEFT JOIN — pick a single deterministic
  // identity (earliest) via LATERAL so the approval queue shows each person once.
  const pending = await q(
    `SELECT m.user_id AS uid, u.display_name, ui.provider, ui.provider_uid, ui.email_verified,
            m.role, m.invite_id, m.created_at AS requested_at
       FROM memberships m JOIN users u ON u.id=m.user_id
       LEFT JOIN LATERAL (
         SELECT provider, provider_uid, email_verified
           FROM user_identities WHERE user_id=m.user_id ORDER BY created_at, id LIMIT 1
       ) ui ON true
      WHERE m.family_id=$1 AND m.status='pending' ORDER BY m.created_at`, [fid]);
  return c.json({ invites: invites.rows, pending: pending.rows });        // [I4] identity in the queue
});

app.get("/families/:fid/sync", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!(await requireScope(a.cred.id, "content", "read"))) return c.json({ type: "forbidden" }, 403);
  const caller = { userId: a.userId, legacy: a.legacy };
  const raw = c.req.query("since") ?? "";

  // [F4] cursor decode:
  //   3-part base64(updated_at|type|id), type ∈ {card,hub}, valid ts → merged mode (resume)
  //   2-part base64(updated_at|id), valid ts → full merged resync from -∞
  //     (old clients sent cards-only 2-part cursors; promote to merged so they are never
  //      pinned in cards-only mode — fixes sticky-cards-only bug in Hub-Sync PR1)
  //   Genuinely malformed (wrong part count other than 2/3, unparseable/NaN timestamp,
  //     unknown type in 3-part) → 400 (not a silent rescan).
  let su = "", st = "", si = "";
  if (raw) {
    const parts = Buffer.from(raw, "base64").toString().split("|");
    if (parts.length === 2) {
      // 2-part legacy cursor: validate timestamp, then fall through to merged mode from -∞.
      // Accept "-infinity" (Postgres timestamptz literal) as a valid start marker.
      const validTs = parts[0] === "-infinity" || !Number.isNaN(Date.parse(parts[0]));
      if (!validTs) return problem(c, 400, "bad-cursor");
      // su/st/si stay "" → merged keyset scans from beginning, returns cards + hubs + sections + blocks.
    } else if (parts.length === 3) {
      // 3-part merged cursor: validate ts + type. Unknown type → full resync (not an error).
      const validTs = !Number.isNaN(Date.parse(parts[0]));
      if (!validTs) return problem(c, 400, "bad-cursor");
      if (["card", "hub", "section", "block"].includes(parts[1])) {
        [su, st, si] = parts;
      }
      // else: unknown type → su/st/si stay "" → full resync
    } else {
      // Wrong part count → malformed.
      return problem(c, 400, "bad-cursor");
    }
  }

  // ADR 0040 §3 — stale-cursor full-resync directive. If the caller resumes from a 3-part
  // cursor older than the tombstone-retention floor, a delete it needed may already be GC'd
  // (sweep.ts purges content tombstones past the SAME floor) → an incremental resume could
  // leave a ghost. Reset the scan to -∞ + flag `full_resync` so the client wipes its cache
  // and rebuilds. `su` is the cursor's updated_at; empty (initial / 2-part legacy) is already
  // a from-∞ scan and is NOT a directive.
  let fullResync = false;
  if (su) {
    const cursorAgeMs = Date.now() - Date.parse(su);
    if (cursorAgeMs > CONTENT_TOMBSTONE_RETENTION_DAYS * 24 * 3600 * 1000) {
      fullResync = true;
      su = ""; st = ""; si = "";              // rebuild from the beginning; has_more paginates it
    }
  }

  const rows = await repo.syncContent(fid, su, st, si);

  // ADR 0030 (round-1 P0-1): visibility is applied to the PAYLOAD, but the cursor +
  // has_more are computed from the RAW fetched keyset window — so a page that is
  // entirely restricted-invisible still advances the cursor (no stall) and never
  // discloses existence by count. A row not visible to the caller is emitted as a
  // TOMBSTONE (so a card/hub that flipped to restricted is dropped from the cache).

  // Prefetch allow-lists for restricted hubs on this page in a single query.
  // This covers:
  //   - hub rows with visibility=restricted
  //   - parent hubs of section/block rows with hub_visibility=restricted
  const restrictedHubIds = Array.from(new Set([
    ...rows
      .filter((r: any) => r.type === "hub" && r.payload?.visibility === "restricted" && !r.deleted_at)
      .map((r: any) => r.id),
    ...rows
      .filter((r: any) => (r.type === "section" || r.type === "block") && r.hub_visibility === "restricted")
      .map((r: any) => r.hub_id as string),
  ].filter(Boolean)));
  const allowSets = new Map<string, Set<string>>();
  if (restrictedHubIds.length > 0) {
    const { q: dbq } = await import("./db.ts");
    const rv = await dbq(
      `SELECT hub_id, user_id FROM resource_visibility WHERE family_id=$1 AND hub_id = ANY($2)`,
      [fid, restrictedHubIds],
    );
    for (const row of rv.rows) {
      if (!allowSets.has(row.hub_id)) allowSets.set(row.hub_id, new Set());
      allowSets.get(row.hub_id)!.add(row.user_id);
    }
  }

  const changes = { cards: [] as any[], hubs: [] as any[], sections: [] as any[], blocks: [] as any[] };
  const tombstones: { type: string; id: string }[] = [];
  for (const r of rows) {
    let visible: boolean;
    if (r.deleted_at) {
      visible = false;
    } else if (r.type === "card") {
      visible = cardVisible(r.payload, caller);
    } else if (r.type === "hub") {
      visible = hubs.hubVisible(r.payload, caller, (hid) => !!(caller.userId && allowSets.get(hid)?.has(caller.userId)));
    } else {
      // section or block: visibility = parent hub's visibility
      const parentHub = { id: r.hub_id, visibility: r.hub_visibility, created_by: r.hub_created_by };
      visible = hubs.hubVisible(parentHub, caller, (hid) => !!(caller.userId && allowSets.get(hid)?.has(caller.userId)));
    }
    if (visible) {
      if (r.type === "card") changes.cards.push(r.payload);
      else if (r.type === "hub") changes.hubs.push(r.payload);
      else if (r.type === "section") changes.sections.push(r.payload);
      else changes.blocks.push(r.payload);
    } else {
      tombstones.push({ type: r.type, id: r.id });
    }
  }

  const last = rows[rows.length - 1];
  // [F3] cursor carries the EXACT Postgres timestamptz string — no JS Date ms-truncation.
  const next_cursor = last
    ? Buffer.from(`${last.updated_at}|${last.type}|${last.id}`).toString("base64")
    : raw;
  return c.json({ changes, tombstones, next_cursor, has_more: rows.length >= repo.SYNC_LIMIT, full_resync: fullResync });
});
