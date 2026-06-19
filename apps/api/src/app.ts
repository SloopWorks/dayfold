// M0 content API (feed surface): household-token auth → tenant-explicit
// card routes + keyset sync. Hono (runs on Vercel + locally + app.request()-testable).
import { Hono } from "hono";
import { q } from "./db.ts";
import { constantTimeEqual, stripServerManaged, stampProvenance } from "./security.ts";
import { BriefingCardSchema } from "./generated/content.ts";
import * as repo from "./repo.ts";

export const app = new Hono();

function bearer(c: any): string | undefined {
  const h = c.req.header("authorization") || "";
  return h.startsWith("Bearer ") ? h.slice(7) : undefined;
}

// One auth+tenancy gate (M0 household token). Cross-tenant → 404; bad → 401.
async function auth(c: any, fid: string) {
  const token = bearer(c);
  const secret = process.env.HOUSEHOLD_SECRET || "";
  if (!token || !secret || !constantTimeEqual(token, secret)) return { status: 401 as const };
  const credId = process.env.HOUSEHOLD_CREDENTIAL_ID || "";
  const r = await q(`SELECT * FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [credId]);
  const cred = r.rows[0];
  if (!cred) return { status: 401 as const };
  if (cred.family_scope !== fid) return { status: 404 as const }; // identical body, no enumeration
  return { cred, scopes: (cred.scopes ?? []) as string[] };
}
const can = (a: any, s: string) => (a.scopes ?? []).includes(s);

app.put("/families/:fid/cards/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await auth(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!can(a, "content:write")) return c.json({ type: "forbidden" }, 403);
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
  const a = await auth(c, fid);
  if ("status" in a) return c.body(null, a.status);
  return c.json(await repo.listCards(fid));
});

app.delete("/families/:fid/cards/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await auth(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!can(a, "content:write")) return c.json({ type: "forbidden" }, 403);
  return c.body(null, (await repo.softDeleteCard(fid, id)) ? 204 : 404);
});

app.get("/families/:fid/sync", async (c) => {
  const fid = c.req.param("fid");
  const a = await auth(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const cursor = c.req.query("since");
  let su: string | null = null, si: string | null = null;
  if (cursor) { const [u, i] = Buffer.from(cursor, "base64").toString().split("|"); su = u; si = i; }
  const rows = await repo.syncCards(fid, su, si);
  const live = rows.filter((r: any) => !r.deleted_at);
  const tombstones = rows.filter((r: any) => r.deleted_at).map((r: any) => ({ type: "card", id: r.id }));
  const last = rows[rows.length - 1];
  const next = last
    ? Buffer.from(`${new Date(last.updated_at).toISOString()}|${last.id}`).toString("base64")
    : cursor;
  return c.json({ changes: { cards: live }, tombstones, next_cursor: next, has_more: rows.length >= 200 });
});
