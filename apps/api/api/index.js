// src/app.ts
import { Hono } from "hono";
import { bodyLimit } from "hono/body-limit";

// src/db.ts
import pg from "pg";
var { Pool, types } = pg;
types.setTypeParser(1184, (s) => s);
types.setTypeParser(1114, (s) => s);
var pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: process.env.VERCEL ? 1 : 10,
  // fail fast instead of hanging if the DB is unreachable (serverless).
  connectionTimeoutMillis: 1e4
});
function q(text, params) {
  return pool.query(text, params);
}

// src/security.ts
import { createHash, timingSafeEqual } from "node:crypto";
function constantTimeEqual(presented, secret) {
  const a = createHash("sha256").update(presented, "utf8").digest();
  const b = createHash("sha256").update(secret, "utf8").digest();
  return timingSafeEqual(a, b);
}
var SERVER_MANAGED_CONTENT_FIELDS = [
  "family_id",
  "version",
  "created_at",
  "updated_at",
  "deleted_at",
  "body_ref",
  // M1 object-storage spill key — never client-set at M0
  "provenance"
  // defense-in-depth: rebuilt server-side by stampProvenance
];
function stripServerManaged(body) {
  const out = { ...body };
  for (const k of SERVER_MANAGED_CONTENT_FIELDS) delete out[k];
  return out;
}
function stampProvenance(body, credentialId) {
  const raw = body.provenance;
  const isPlain = raw != null && typeof raw === "object" && !Array.isArray(raw);
  const src = isPlain ? raw : {};
  const provenance = { credential_id: credentialId };
  if (typeof src.source === "string") provenance.source = src.source;
  if (typeof src.at === "string") provenance.at = src.at;
  return { ...body, provenance };
}

// src/generated/content.ts
import { z } from "zod";
var ProvenanceSchema = z.object({ "source": z.string().describe("claude | email | user | <url>"), "at": z.any(), "credential_id": z.string().describe("which credential pushed this (audit)").optional() }).strict();
var TriggerSchema = z.any().superRefine((x, ctx) => {
  const schemas = [z.object({ "geo": z.object({ "place_ref": z.string().optional(), "lat": z.number().optional(), "lng": z.number().optional(), "radius_m": z.number().int().default(150), "label": z.string().optional() }) }).strict(), z.object({ "when": z.object({ "at": z.any().optional(), "window": z.record(z.string(), z.any()).optional(), "relative": z.string().optional(), "recurring": z.string().optional(), "alert_offset": z.string().optional() }) }).strict(), z.object({ "activity": z.object({ "kind": z.enum(["walking", "running", "biking", "driving"]).optional() }) }).strict().describe("schema slot; matching DEFERRED")];
  const { errors, failed } = schemas.reduce(
    ({ errors: errors2, failed: failed2 }, schema) => ((result) => result.error ? {
      errors: [...errors2, ...result.error.issues],
      failed: failed2 + 1
    } : { errors: errors2, failed: failed2 })(
      schema.safeParse(x)
    ),
    { errors: [], failed: 0 }
  );
  const passed = schemas.length - failed;
  if (passed !== 1) {
    ctx.addIssue(errors.length ? {
      path: [],
      code: "invalid_union",
      errors: [errors],
      message: "Invalid input: Should pass single schema. Passed " + passed
    } : {
      path: [],
      code: "custom",
      errors: [errors],
      message: "Invalid input: Should pass single schema. Passed " + passed
    });
  }
}).describe("ADR 0014 \u2014 matched ON-DEVICE; live position never leaves.");
var ActionSchema = z.object({ "label": z.string(), "action_id": z.string(), "params": z.record(z.string(), z.any()).optional() }).strict().describe("ADR 0016 RESERVED (bounded-now: buttons + structured asks; not built at MVP).");
var LinkPayloadSchema = z.object({ "url": z.string().url(), "label": z.string().optional(), "source": z.string().optional() }).strict();
var ChecklistPayloadSchema = z.object({ "items": z.array(z.object({ "text": z.string(), "done": z.boolean().default(false), "due": z.any().optional(), "assignee": z.string().optional() }).strict()) }).strict();
var DocumentPayloadSchema = z.object({ "ref": z.string().describe("url | fileRef (links+small refs at MVP)"), "label": z.string().optional(), "kind": z.string().optional() }).strict();
var MilestonePayloadSchema = z.object({ "date": z.any(), "label": z.string() }).strict();
var ContactPayloadSchema = z.object({ "name": z.string(), "role": z.string().optional(), "phone": z.string().optional(), "email": z.string().optional() }).strict();
var LocationPayloadSchema = z.object({ "label": z.string(), "address": z.string().optional(), "mapUrl": z.string().optional() }).strict();
var BudgetPayloadSchema = z.object({ "items": z.array(z.object({ "label": z.string(), "amount": z.number(), "paid": z.boolean().default(false) }).strict()) }).strict();
var BlockSchema = z.object({ "id": z.any(), "type": z.enum(["text", "markdown", "link", "checklist", "document", "milestone", "contact", "location", "budget"]), "ord": z.number().int().default(0), "version": z.any().optional(), "body_md": z.string().max(1048576).describe("long-form markdown (text/markdown blocks); inline \u22641MB at M0, else spill to body_ref (06, M1)").optional(), "body_ref": z.string().describe("object-storage KEY when spilled (M1); never a URL; XOR with body_md").optional(), "payload": z.any().superRefine((x, ctx) => {
  const schemas = [z.any(), z.any(), z.any(), z.any(), z.any(), z.any(), z.any()];
  const { errors, failed } = schemas.reduce(
    ({ errors: errors2, failed: failed2 }, schema) => ((result) => result.error ? {
      errors: [...errors2, ...result.error.issues],
      failed: failed2 + 1
    } : { errors: errors2, failed: failed2 })(
      schema.safeParse(x)
    ),
    { errors: [], failed: 0 }
  );
  const passed = schemas.length - failed;
  if (passed !== 1) {
    ctx.addIssue(errors.length ? {
      path: [],
      code: "invalid_union",
      errors: [errors],
      message: "Invalid input: Should pass single schema. Passed " + passed
    } : {
      path: [],
      code: "custom",
      errors: [errors],
      message: "Invalid input: Should pass single schema. Passed " + passed
    });
  }
}).describe("structured fields for non-markdown block types; variant by `type` (see $comment)").optional(), "triggers": z.array(z.any()).optional(), "actions": z.array(z.any()).optional(), "provenance": z.any() }).strict().and(z.any());
var SectionSchema = z.object({ "id": z.any(), "title": z.string().describe("[CONTENT/E2E-hole]").optional(), "ord": z.number().int().default(0), "version": z.any().optional(), "blocks": z.array(z.any()).optional() }).strict();
var HubSchema = z.object({ "id": z.any(), "type": z.string().describe("bounded template-catalog key (ADR 0004/0006): vacation|starting-college|move|party-event|new-baby|medical|school-year \u2014 app-validated"), "title": z.string().describe("[CONTENT/E2E-hole]"), "status": z.enum(["planning", "active", "archived"]).default("active"), "start_at": z.any().optional(), "end_at": z.any().optional(), "countdown_to": z.any().optional(), "version": z.any().optional(), "sections": z.array(z.any()).optional() }).strict();
var BriefingCardSchema = z.object({ "id": z.any(), "kind": z.enum(["action", "info", "weather", "countdown"]).default("info"), "title": z.string().max(4096), "body_md": z.string().max(1048576).describe("limited inline markdown only (1MB cap, F8)").optional(), "target": z.object({ "hubId": z.string().optional(), "sectionId": z.string().optional(), "blockId": z.string().optional() }).strict().describe("deep-link into a hub (resolved client-side vs local cache, nearest-ancestor)").optional(), "triggers": z.array(z.any()).optional(), "actions": z.array(z.any()).optional(), "not_before": z.any().optional(), "expires_at": z.any().optional(), "version": z.any().optional(), "provenance": z.any() }).strict().describe("the 'Now' surface");
var PlaceSchema = z.object({ "id": z.any(), "label": z.string(), "kind": z.enum(["home", "school", "store", "other"]).describe("category (drives the place icon in the UI; design alignment)").default("other"), "lat": z.number(), "lng": z.number(), "radius_m": z.number().int().default(150), "version": z.any().optional() }).strict().describe("ADR 0014 reusable named place; family content (encrypted at rest, never live position)");
var SyncResponseSchema = z.object({ "changes": z.object({ "hubs": z.array(z.any()).optional(), "sections": z.array(z.any()).optional(), "blocks": z.array(z.any()).optional(), "cards": z.array(z.any()).optional(), "places": z.array(z.any()).optional() }), "tombstones": z.array(z.object({ "type": z.enum(["hub", "section", "block", "card", "place"]), "id": z.string() }).strict()), "next_cursor": z.string().optional(), "has_more": z.boolean() }).strict().describe("GET /families/{fid}/sync (03 \xA7sync)");

// src/repo.ts
var J = (v) => v == null ? null : JSON.stringify(v);
var SYNC_LIMIT = 200;
async function upsertCard(familyId, id, b) {
  const r = await q(
    `INSERT INTO briefing_cards
       (id, family_id, kind, title, body_md, target_hub_id, target_section_id,
        target_block_id, provenance, triggers, actions, not_before, expires_at, version)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       kind=EXCLUDED.kind, title=EXCLUDED.title, body_md=EXCLUDED.body_md,
       target_hub_id=EXCLUDED.target_hub_id, target_section_id=EXCLUDED.target_section_id,
       target_block_id=EXCLUDED.target_block_id, provenance=EXCLUDED.provenance,
       triggers=EXCLUDED.triggers, actions=EXCLUDED.actions,
       not_before=EXCLUDED.not_before, expires_at=EXCLUDED.expires_at,
       version=briefing_cards.version + 1, deleted_at=NULL
     RETURNING *`,
    [
      id,
      familyId,
      b.kind ?? "info",
      b.title,
      b.body_md ?? null,
      b.target?.hubId ?? null,
      b.target?.sectionId ?? null,
      b.target?.blockId ?? null,
      J(b.provenance),
      J(b.triggers),
      J(b.actions),
      b.not_before ?? null,
      b.expires_at ?? null
    ]
  );
  return r.rows[0];
}
async function listCards(familyId) {
  const r = await q(
    `SELECT * FROM briefing_cards WHERE family_id=$1 AND deleted_at IS NULL
     ORDER BY not_before NULLS LAST, id`,
    [familyId]
  );
  return r.rows;
}
async function softDeleteCard(familyId, id) {
  const r = await q(
    `UPDATE briefing_cards SET deleted_at=now()
     WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`,
    [familyId, id]
  );
  return (r.rowCount ?? 0) > 0;
}
async function syncCards(familyId, su, si, limit = SYNC_LIMIT) {
  const r = await q(
    `SELECT * FROM briefing_cards WHERE family_id=$1 AND (updated_at, id) > ($2::timestamptz, $3)
     ORDER BY updated_at, id LIMIT $4`,
    [familyId, su ?? "-infinity", si ?? "", limit]
  );
  return r.rows;
}

// src/app.ts
var app = new Hono();
app.get("/health", (c) => c.json({ ok: true, surface: "m0" }));
function problem(c, status, type, detail) {
  return c.body(
    JSON.stringify({ type, title: type, status, ...detail ? { detail } : {} }),
    status,
    { "content-type": "application/problem+json" }
  );
}
app.use("*", bodyLimit({ maxSize: 1024 * 1024, onError: (c) => problem(c, 413, "payload-too-large") }));
function bearer(c) {
  const h = c.req.header("authorization") || "";
  return h.startsWith("Bearer ") ? h.slice(7) : void 0;
}
async function auth(c, fid) {
  const token = bearer(c);
  const secret = process.env.HOUSEHOLD_SECRET || "";
  if (!token || !secret || !constantTimeEqual(token, secret)) return { status: 401 };
  const credId = process.env.HOUSEHOLD_CREDENTIAL_ID || "";
  let cred;
  try {
    const r = await q(`SELECT * FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [credId]);
    cred = r.rows[0];
  } catch {
    return { status: 401 };
  }
  if (!cred) return { status: 401 };
  if (cred.family_scope !== fid) return { status: 404 };
  return { cred, scopes: cred.scopes ?? [] };
}
var can = (a, s) => (a.scopes ?? []).includes(s);
app.put("/families/:fid/cards/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await auth(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!can(a, "content:write")) return c.json({ type: "forbidden" }, 403);
  const raw = await c.req.json().catch(() => null);
  if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
  let body = stripServerManaged(raw);
  body = stampProvenance(body, a.cred.id);
  const parsed = BriefingCardSchema.safeParse({ ...body, id });
  if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
  return c.json(await upsertCard(fid, id, parsed.data), 200);
});
app.get("/families/:fid/cards", async (c) => {
  const fid = c.req.param("fid");
  const a = await auth(c, fid);
  if ("status" in a) return c.body(null, a.status);
  return c.json(await listCards(fid));
});
app.delete("/families/:fid/cards/:id", async (c) => {
  const fid = c.req.param("fid"), id = c.req.param("id");
  const a = await auth(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!can(a, "content:write")) return c.json({ type: "forbidden" }, 403);
  return c.body(null, await softDeleteCard(fid, id) ? 204 : 404);
});
app.get("/families/:fid/sync", async (c) => {
  const fid = c.req.param("fid");
  const a = await auth(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const cursor = c.req.query("since");
  let su = null, si = null;
  if (cursor) {
    const parts = Buffer.from(cursor, "base64").toString().split("|");
    if (parts.length !== 2 || Number.isNaN(Date.parse(parts[0]))) return problem(c, 400, "bad-cursor");
    su = parts[0];
    si = parts[1];
  }
  const rows = await syncCards(fid, su, si);
  const live = rows.filter((r) => !r.deleted_at);
  const tombstones = rows.filter((r) => r.deleted_at).map((r) => ({ type: "card", id: r.id }));
  const last = rows[rows.length - 1];
  const next = last ? Buffer.from(`${last.updated_at}|${last.id}`).toString("base64") : cursor;
  return c.json({ changes: { cards: live }, tombstones, next_cursor: next, has_more: rows.length >= SYNC_LIMIT });
});

// src/vercel-entry.ts
async function handler(req, res) {
  const method = req.method ?? "GET";
  let body;
  if (method !== "GET" && method !== "HEAD") {
    if (req.body !== void 0 && req.body !== null) {
      body = Buffer.from(typeof req.body === "string" ? req.body : JSON.stringify(req.body));
    } else {
      const chunks = [];
      for await (const c of req) chunks.push(c);
      body = chunks.length ? Buffer.concat(chunks) : void 0;
    }
  }
  const headers = new Headers();
  for (const [k, v] of Object.entries(req.headers)) {
    if (Array.isArray(v)) v.forEach((x) => headers.append(k, x));
    else if (v != null) headers.set(k, v);
  }
  const url = `https://${req.headers.host}${req.url ?? "/"}`;
  const response = await app.fetch(new Request(url, { method, headers, body }));
  res.statusCode = response.status;
  response.headers.forEach((v, k) => res.setHeader(k, v));
  res.end(Buffer.from(await response.arrayBuffer()));
}
export {
  handler as default
};
