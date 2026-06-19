// M0 content API (feed surface): household-token auth → tenant-explicit
// card routes + keyset sync. Hono (runs on Vercel + locally + app.request()-testable).
import { Hono } from "hono";
import { bodyLimit } from "hono/body-limit";
import { q } from "./db.ts";
import { stripServerManaged, stampProvenance } from "./security.ts";
import { BriefingCardSchema } from "./generated/content.ts";
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
  if ("reuse" in out) return c.body(null, 401);
  // Look up the credential for the new refresh token to re-mint access.
  // rotate() already inserted the new token row; query by hash of nextOpaque.
  const h = hashToken(out.refresh);
  const row = await q(
    `SELECT rt.credential_id, c.user_id FROM refresh_tokens rt JOIN credentials c ON c.id=rt.credential_id WHERE rt.token_hash=$1`,
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
  // bind the caller's credential to this family so the JWT can write content
  await q(`UPDATE credentials SET family_scope=$1 WHERE user_id=$2 AND family_scope IS NULL`, [familyId, sub]);
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
  return c.json(await repo.upsertCard(fid, id, parsed.data), 200);
});

app.get("/families/:fid/cards", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  return c.json(await repo.listCards(fid));
});

app.delete("/families/:fid/cards/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!a.scopes.includes("content:write")) return c.json({ type: "forbidden" }, 403);
  return c.body(null, (await repo.softDeleteCard(fid, id)) ? 204 : 404);
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
