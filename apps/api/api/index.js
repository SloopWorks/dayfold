var __defProp = Object.defineProperty;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __esm = (fn, res) => function __init() {
  return fn && (res = (0, fn[__getOwnPropNames(fn)[0]])(fn = 0)), res;
};
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};

// src/db.ts
import pg from "pg";
function q(text, params) {
  return pool.query(text, params);
}
var Pool, types, pool;
var init_db = __esm({
  "src/db.ts"() {
    "use strict";
    ({ Pool, types } = pg);
    types.setTypeParser(1184, (s) => s);
    types.setTypeParser(1114, (s) => s);
    pool = new Pool({
      connectionString: process.env.DATABASE_URL,
      max: process.env.VERCEL ? 1 : 10,
      // fail fast instead of hanging if the DB is unreachable (serverless).
      connectionTimeoutMillis: 1e4
    });
  }
});

// src/auth/tokens.ts
var tokens_exports = {};
__export(tokens_exports, {
  jwks: () => jwks,
  mintAccess: () => mintAccess,
  verifyAccess: () => verifyAccess
});
import { SignJWT, jwtVerify, importJWK } from "jose";
import { randomUUID } from "node:crypto";
async function mintAccess({ sub, cid }) {
  return new SignJWT({ cid }).setProtectedHeader({ alg: "EdDSA", kid: KID }).setSubject(sub).setIssuer(ISS).setAudience(AUD).setIssuedAt().setJti(randomUUID()).setExpirationTime(ACCESS_TTL).sign(await privKeyP);
}
async function verifyAccess(token) {
  const key = await pubKeyP;
  const { payload, protectedHeader } = await jwtVerify(token, key, {
    algorithms: ["EdDSA"],
    issuer: ISS,
    audience: AUD,
    clockTolerance: LEEWAY
  });
  if (!protectedHeader.kid || !ALLOWLIST.has(protectedHeader.kid)) throw new Error("bad kid");
  return { sub: String(payload.sub), cid: String(payload.cid), jti: String(payload.jti) };
}
async function jwks() {
  return { keys: [pubJwk] };
}
var AUTH_SIGNING_KEY, AUTH_ISS, AUTH_AUD, ISS, AUD, ACCESS_TTL, LEEWAY, privJwk, KID, ALLOWLIST, privKeyP, pubJwk, pubKeyP;
var init_tokens = __esm({
  "src/auth/tokens.ts"() {
    "use strict";
    AUTH_SIGNING_KEY = process.env.AUTH_SIGNING_KEY;
    AUTH_ISS = process.env.AUTH_ISS;
    AUTH_AUD = process.env.AUTH_AUD;
    if (!AUTH_SIGNING_KEY) throw new Error("Missing required env var: AUTH_SIGNING_KEY");
    if (!AUTH_ISS) throw new Error("Missing required env var: AUTH_ISS");
    if (!AUTH_AUD) throw new Error("Missing required env var: AUTH_AUD");
    ISS = AUTH_ISS;
    AUD = AUTH_AUD;
    ACCESS_TTL = "5m";
    LEEWAY = 30;
    privJwk = JSON.parse(AUTH_SIGNING_KEY);
    KID = privJwk.kid;
    ALLOWLIST = /* @__PURE__ */ new Set([KID]);
    privKeyP = importJWK({ ...privJwk, alg: "EdDSA" }, "EdDSA");
    pubJwk = (() => {
      const { d, ...pub } = privJwk;
      return { ...pub, alg: "EdDSA", use: "sig" };
    })();
    pubKeyP = importJWK(pubJwk, "EdDSA");
  }
});

// src/auth/identity.ts
var identity_exports = {};
__export(identity_exports, {
  StubVerifier: () => StubVerifier,
  createFamily: () => createFamily,
  findOrCreateUser: () => findOrCreateUser,
  mintCredentialFor: () => mintCredentialFor
});
import { randomBytes } from "node:crypto";
async function findOrCreateUser(idn) {
  const existing = await q(
    `SELECT user_id FROM user_identities WHERE provider=$1 AND provider_uid=$2`,
    [idn.provider, idn.provider_uid]
  );
  if (existing.rowCount === 1) return { userId: existing.rows[0].user_id };
  const userId = id("usr");
  const r = await q(
    // Orphan-users invariant: the CTE's INSERT INTO users always commits its row,
    // even when the outer INSERT INTO user_identities hits ON CONFLICT DO NOTHING
    // (i.e. a concurrent caller won the race). That orphan users row has no FK
    // referencing it and is harmless today. Any future schema work that adds
    // references to users.id (e.g. a soft-delete flag, audit log FK, etc.) must
    // either tolerate these orphans or add a cleanup sweep.
    `WITH u AS (INSERT INTO users(id) VALUES ($1) RETURNING id)
     INSERT INTO user_identities(id,user_id,provider,provider_uid,email_verified)
     VALUES ($2, $1, $3, $4, $5)
     ON CONFLICT (provider, provider_uid) DO NOTHING
     RETURNING user_id`,
    [userId, id("uid"), idn.provider, idn.provider_uid, !!idn.email_verified]
  );
  if (r.rowCount === 1) return { userId: r.rows[0].user_id };
  const w = await q(
    `SELECT user_id FROM user_identities WHERE provider=$1 AND provider_uid=$2`,
    [idn.provider, idn.provider_uid]
  );
  return { userId: w.rows[0].user_id };
}
async function createFamily(userId, name) {
  const familyId = id("fam");
  const c = await pool.connect();
  try {
    await c.query("BEGIN");
    await c.query(`INSERT INTO families(id,name,created_by) VALUES ($1,$2,$3)`, [familyId, name, userId]);
    await c.query(`INSERT INTO memberships(user_id,family_id,role,status) VALUES ($1,$2,'owner','active')`, [userId, familyId]);
    await c.query("COMMIT");
  } catch (e) {
    await c.query("ROLLBACK");
    throw e;
  } finally {
    c.release();
  }
  return { familyId };
}
async function mintCredentialFor(userId, familyId) {
  const credentialId = id("cred");
  await q(
    `INSERT INTO credentials(id,user_id,family_scope,kind,scopes) VALUES ($1,$2,$3,'app','{content:read,content:write}')`,
    [credentialId, userId, familyId]
  );
  return { credentialId };
}
var StubVerifier, id;
var init_identity = __esm({
  "src/auth/identity.ts"() {
    "use strict";
    init_db();
    StubVerifier = class {
      async verify(a) {
        const o = a;
        if (!o?.provider || !o?.provider_uid) throw new Error("bad stub identity");
        return { provider: o.provider, provider_uid: o.provider_uid, email_verified: !!o.email_verified };
      }
    };
    id = (p) => p + "_" + randomBytes(9).toString("hex");
  }
});

// src/auth/audit.ts
var audit_exports = {};
__export(audit_exports, {
  audit: () => audit
});
async function audit(event, opts = {}) {
  await q(
    `INSERT INTO audit_log(event, actor_user_id, family_id, detail) VALUES ($1,$2,$3,$4)`,
    [event, opts.actorUserId ?? null, opts.familyId ?? null, JSON.stringify(opts.detail ?? {})]
  );
}
var init_audit = __esm({
  "src/auth/audit.ts"() {
    "use strict";
    init_db();
  }
});

// src/auth/refresh.ts
var refresh_exports = {};
__export(refresh_exports, {
  hashToken: () => hashToken,
  issueRefresh: () => issueRefresh,
  rotate: () => rotate
});
import { randomBytes as randomBytes2, createHash as createHash2 } from "node:crypto";
async function issueRefresh(credentialId, client) {
  const opaque = randomBytes2(32).toString("base64url");
  const run = client ? client.query.bind(client) : q;
  await run(
    `INSERT INTO refresh_tokens(token_hash, credential_id, expires_at)
     VALUES ($1,$2, now() + ($3 || ' days')::interval)`,
    [hashToken(opaque), credentialId, String(ABS_TTL_DAYS)]
  );
  return opaque;
}
async function rotate(opaque) {
  const h = hashToken(opaque);
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const cas = await client.query(
      `UPDATE refresh_tokens SET consumed_at=now()
       WHERE token_hash=$1 AND consumed_at IS NULL AND expires_at > now()
       RETURNING credential_id`,
      [h]
    );
    if (cas.rowCount === 1) {
      const credentialId = cas.rows[0].credential_id;
      const nextOpaque = randomBytes2(32).toString("base64url");
      const nextHash = hashToken(nextOpaque);
      await client.query(
        `INSERT INTO refresh_tokens(token_hash, credential_id, expires_at)
         VALUES ($1,$2, now() + ($3 || ' days')::interval)`,
        [nextHash, credentialId, String(ABS_TTL_DAYS)]
      );
      await client.query(
        `UPDATE refresh_tokens SET superseded_by=$1 WHERE token_hash=$2`,
        [nextHash, h]
      );
      await client.query("COMMIT");
      return { refresh: nextOpaque };
    }
    await client.query("COMMIT");
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
  const row = await q(
    `SELECT credential_id, consumed_at, superseded_by FROM refresh_tokens WHERE token_hash=$1`,
    [h]
  );
  if (row.rowCount === 0) return null;
  const { credential_id: cid, consumed_at, superseded_by } = row.rows[0];
  if (!consumed_at) return null;
  const gc = await pool.connect();
  let graceResult = null;
  let graceCollision = false;
  let genuineReuse = false;
  try {
    await gc.query("BEGIN");
    await gc.query(`SELECT pg_advisory_xact_lock(hashtext($1))`, [cid]);
    const grace = await gc.query(
      `SELECT 1 FROM refresh_tokens prior
         JOIN refresh_tokens succ ON succ.token_hash = prior.superseded_by
        WHERE prior.token_hash=$1 AND prior.consumed_at > now() - interval '20 seconds'
          AND succ.consumed_at IS NULL`,
      [h]
    );
    if (grace.rowCount === 1 && superseded_by) {
      const cas2 = await gc.query(
        `UPDATE refresh_tokens SET consumed_at=now()
           WHERE token_hash=$1 AND consumed_at IS NULL AND expires_at > now() RETURNING credential_id`,
        [superseded_by]
      );
      if (cas2.rowCount === 1) {
        const nextOpaque = randomBytes2(32).toString("base64url");
        const nextHash = hashToken(nextOpaque);
        await gc.query(
          `INSERT INTO refresh_tokens(token_hash, credential_id, expires_at, graced_from)
           VALUES ($1,$2, now() + ($3 || ' days')::interval, $4)`,
          [nextHash, cid, String(ABS_TTL_DAYS), h]
        );
        await gc.query(
          `UPDATE refresh_tokens SET superseded_by=$1 WHERE token_hash=$2`,
          [nextHash, superseded_by]
        );
        graceResult = { refresh: nextOpaque, graced: true };
      } else {
        genuineReuse = true;
        await gc.query(
          `UPDATE credentials SET revoked_at=now() WHERE id=$1 AND revoked_at IS NULL`,
          [cid]
        );
      }
    } else if (!superseded_by) {
      genuineReuse = true;
      await gc.query(
        `UPDATE credentials SET revoked_at=now() WHERE id=$1 AND revoked_at IS NULL`,
        [cid]
      );
    } else {
      const collision = await gc.query(
        `SELECT 1 FROM refresh_tokens next_token
           JOIN refresh_tokens issued ON issued.token_hash = next_token.superseded_by
          WHERE next_token.token_hash=$1
            AND issued.graced_from=$2
            AND next_token.consumed_at > now() - interval '20 seconds'`,
        [superseded_by, h]
      );
      if (collision.rowCount === 1) {
        graceCollision = true;
      } else {
        genuineReuse = true;
        await gc.query(
          `UPDATE credentials SET revoked_at=now() WHERE id=$1 AND revoked_at IS NULL`,
          [cid]
        );
      }
    }
    await gc.query("COMMIT");
  } catch (e) {
    await gc.query("ROLLBACK");
    throw e;
  } finally {
    gc.release();
  }
  if (graceResult) return graceResult;
  if (graceCollision) return { reuse: true };
  const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
  await audit2("refresh.reuse_revoked", { detail: { credential_id: cid } });
  return { reuse: true };
}
var ABS_TTL_DAYS, hashToken;
var init_refresh = __esm({
  "src/auth/refresh.ts"() {
    "use strict";
    init_db();
    ABS_TTL_DAYS = 45;
    hashToken = (s) => createHash2("sha256").update(s, "utf8").digest("hex");
  }
});

// src/auth/ratelimit.ts
var ratelimit_exports = {};
__export(ratelimit_exports, {
  clientIp: () => clientIp,
  hit: () => hit,
  isLocked: () => isLocked,
  recordFailure: () => recordFailure,
  resetFailures: () => resetFailures
});
function clientIp(c) {
  return c.req.header("x-vercel-forwarded-for") || c.req.header("x-forwarded-for")?.split(",").pop()?.trim() || "unknown";
}
async function hit(key, windowSecs, cap) {
  const r = await q(
    `INSERT INTO rate_limits(key, window_start, count) VALUES ($1, ${winSql}, 1)
     ON CONFLICT (key, window_start) DO UPDATE SET count = rate_limits.count + 1
     RETURNING count`,
    [key, windowSecs]
  );
  const count = r.rows[0].count;
  return { ok: count <= cap, count };
}
async function isLocked(key) {
  const r = await q(`SELECT 1 FROM rate_limits WHERE key=$1 AND locked_until > now() LIMIT 1`, [key]);
  return (r.rowCount ?? 0) > 0;
}
async function recordFailure(key, windowSecs, threshold, lockSecs) {
  await q(
    `INSERT INTO rate_limits(key, window_start, count) VALUES ($1, ${winSql}, 1)
     ON CONFLICT (key, window_start) DO UPDATE SET
       count = rate_limits.count + 1,
       locked_until = CASE
         WHEN rate_limits.count + 1 >= $3
         THEN now() + ($4 || ' seconds')::interval
         ELSE rate_limits.locked_until
       END`,
    [key, windowSecs, threshold, String(lockSecs)]
  );
}
async function resetFailures(key) {
  await q(`DELETE FROM rate_limits WHERE key=$1`, [key]);
}
var winSql;
var init_ratelimit = __esm({
  "src/auth/ratelimit.ts"() {
    "use strict";
    init_db();
    winSql = `to_timestamp(floor(extract(epoch from now())/$2)*$2)`;
  }
});

// src/auth/device.ts
var device_exports = {};
__export(device_exports, {
  createAuthorization: () => createAuthorization,
  genDeviceCode: () => genDeviceCode,
  genUserCode: () => genUserCode,
  redeem: () => redeem
});
import { randomBytes as randomBytes3 } from "node:crypto";
function genUserCode() {
  const pick = () => ALPHABET[randomBytes3(1)[0] % ALPHABET.length];
  const block = () => Array.from({ length: 4 }, pick).join("");
  return `${block()}-${block()}`;
}
async function createAuthorization(client, ip, ua) {
  const device_code = genDeviceCode();
  for (let attempt = 0; ; attempt++) {
    const user_code = genUserCode();
    try {
      await q(
        `INSERT INTO device_authorizations(device_code,user_code,client,origin_ip,origin_ua,interval_s,expires_at)
         VALUES ($1,$2,$3,$4,$5,$6, now() + ($7||' seconds')::interval)`,
        [device_code, user_code, client, ip, ua, INTERVAL_S, String(EXPIRES_S)]
      );
      return { device_code, user_code };
    } catch (e) {
      if (e?.code === "23505" && attempt < 3) continue;
      throw e;
    }
  }
}
async function redeem(device_code, mintAccess2, issueRefresh2) {
  const row = (await q(`SELECT * FROM device_authorizations WHERE device_code=$1`, [device_code])).rows[0];
  if (!row) return { error: "expired_token" };
  const expired = new Date(row.expires_at).getTime() < Date.now();
  if (expired) {
    if (row.status === "pending") await q(`UPDATE device_authorizations SET status='expired' WHERE device_code=$1 AND status='pending'`, [device_code]);
    return { error: "expired_token" };
  }
  if (row.status === "denied") return { error: "access_denied" };
  if (row.status === "consumed") return { error: "expired_token" };
  if (row.status === "pending") {
    const upd = await q(
      `UPDATE device_authorizations SET last_polled_at=now()
       WHERE device_code=$1 AND status='pending'
         AND (last_polled_at IS NULL OR last_polled_at < now() - make_interval(secs => interval_s)) RETURNING 1`,
      [device_code]
    );
    return { error: (upd.rowCount ?? 0) === 1 ? "authorization_pending" : "slow_down" };
  }
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const cas = await client.query(
      `UPDATE device_authorizations SET status='consumed' WHERE device_code=$1 AND status='approved'
       RETURNING user_id, family_id, origin_ua`,
      [device_code]
    );
    if (cas.rowCount !== 1) {
      await client.query("COMMIT");
      return { error: "expired_token" };
    }
    const { user_id, family_id, origin_ua } = cas.rows[0];
    const cid = credId();
    await client.query(
      `INSERT INTO credentials(id,user_id,family_scope,kind,scopes,label)
       VALUES ($1,$2,$3,'cli','{content:read,content:write}', 'familyai-cli '||left(coalesce($4,''),64))`,
      [cid, user_id, family_id, origin_ua]
    );
    const refresh = await issueRefresh2(cid, client);
    await client.query(`UPDATE device_authorizations SET credential_id=$1 WHERE device_code=$2`, [cid, device_code]);
    await client.query("COMMIT");
    const access = await mintAccess2({ sub: user_id, cid });
    return { tokens: { access_token: access, refresh_token: refresh, token_type: "Bearer", expires_in: 300 } };
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
}
var ALPHABET, genDeviceCode, credId, EXPIRES_S, INTERVAL_S;
var init_device = __esm({
  "src/auth/device.ts"() {
    "use strict";
    init_db();
    ALPHABET = "23456789CFGHJMPQRVWX";
    genDeviceCode = () => randomBytes3(32).toString("base64url");
    credId = () => "cred_" + randomBytes3(9).toString("hex");
    EXPIRES_S = 600;
    INTERVAL_S = 5;
  }
});

// src/app.ts
init_db();
import { Hono } from "hono";
import { bodyLimit } from "hono/body-limit";

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
init_db();
var J = (v) => v == null ? null : JSON.stringify(v);
var SYNC_LIMIT = 200;
async function upsertCard(familyId, id2, b) {
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
      id2,
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
async function softDeleteCard(familyId, id2) {
  const r = await q(
    `UPDATE briefing_cards SET deleted_at=now()
     WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`,
    [familyId, id2]
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

// src/auth/middleware.ts
init_db();
function bearer(c) {
  const h = c.req.header("authorization") || "";
  return h.startsWith("Bearer ") ? h.slice(7) : void 0;
}
async function authorizeTenant(c, fid) {
  const token = bearer(c);
  if (!token) return { status: 401 };
  const legacySecret = process.env.HOUSEHOLD_SECRET || "";
  if (legacySecret && constantTimeEqual(token, legacySecret)) {
    try {
      const r = await q(
        `SELECT * FROM credentials WHERE id=$1 AND revoked_at IS NULL`,
        [process.env.HOUSEHOLD_CREDENTIAL_ID || ""]
      );
      const cred = r.rows[0];
      if (!cred) return { status: 401 };
      if (cred.family_scope !== fid) return { status: 404 };
      return { cred, userId: null, role: null, scopes: cred.scopes ?? [], legacy: true };
    } catch {
      return { status: 401 };
    }
  }
  let claims;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    claims = await verifyAccess2(token);
  } catch {
    return { status: 401 };
  }
  try {
    const r = await q(`SELECT * FROM credentials WHERE id=$1`, [claims.cid]);
    const cred = r.rows[0];
    if (!cred || cred.revoked_at) return { status: 401 };
    const m = await q(`SELECT role, status FROM memberships WHERE user_id=$1 AND family_id=$2`, [claims.sub, fid]);
    if (m.rowCount === 0) return { status: 404 };
    if (m.rows[0].status !== "active") return { status: 403 };
    if (cred.family_scope && cred.family_scope !== fid) return { status: 404 };
    return { cred, userId: claims.sub, role: m.rows[0].role, scopes: cred.scopes ?? [], legacy: false };
  } catch {
    return { status: 401 };
  }
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
function bearer2(c) {
  const h = c.req.header("authorization") || "";
  return h.startsWith("Bearer ") ? h.slice(7) : void 0;
}
function devAuthAllowed(_c) {
  if (process.env.ENABLE_DEV_AUTH !== "1") return false;
  const env = process.env.VERCEL_ENV;
  if (env === "production" || env === "preview") return false;
  return true;
}
app.post("/auth/dev-token", async (c) => {
  if (!devAuthAllowed(c)) return c.body(null, 404);
  if (bearer2(c) !== (process.env.DEV_AUTH_SECRET || "\0")) return c.body(null, 401);
  const body = await c.req.json().catch(() => null);
  const { StubVerifier: StubVerifier2, findOrCreateUser: findOrCreateUser2 } = await Promise.resolve().then(() => (init_identity(), identity_exports));
  const idn = await new StubVerifier2().verify(body).catch(() => null);
  if (!idn) return c.json({ type: "bad-identity" }, 400);
  const { userId } = await findOrCreateUser2(idn);
  console.warn(`[dev-auth] minted token for ${idn.provider}:${idn.provider_uid} user=${userId}`);
  const credentialId = "cred_" + Math.random().toString(16).slice(2);
  await q(
    `INSERT INTO credentials(id,user_id,kind,scopes) VALUES ($1,$2,'app','{content:read,content:write}')`,
    [credentialId, userId]
  );
  const { mintAccess: mintAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
  const { issueRefresh: issueRefresh2 } = await Promise.resolve().then(() => (init_refresh(), refresh_exports));
  const access = await mintAccess2({ sub: userId, cid: credentialId });
  const refresh = await issueRefresh2(credentialId);
  return c.json({ access, refresh });
});
app.post("/auth/refresh", async (c) => {
  const body = await c.req.json().catch(() => null);
  const { rotate: rotate2, hashToken: hashToken2 } = await Promise.resolve().then(() => (init_refresh(), refresh_exports));
  const out = await rotate2(body?.refresh || "");
  if (!out) return c.body(null, 401);
  if ("refresh" in out) {
    if (out.graced) {
      const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
      await audit2("refresh.grace_reissued", {});
    }
  } else {
    return c.body(null, 401);
  }
  const h = hashToken2(out.refresh);
  const row = await q(
    `SELECT rt.credential_id, c.user_id FROM refresh_tokens rt JOIN credentials c ON c.id=rt.credential_id WHERE rt.token_hash=$1 AND c.revoked_at IS NULL`,
    [h]
  ).catch(() => null);
  if (!row || row.rowCount === 0) return c.body(null, 401);
  const { credential_id: cid, user_id: sub } = row.rows[0];
  const { mintAccess: mintAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
  const access = await mintAccess2({ sub, cid });
  return c.json({ access, refresh: out.refresh });
});
app.post("/auth/signout", async (c) => {
  const t = bearer2(c);
  if (!t) return c.body(null, 401);
  let cid;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    cid = (await verifyAccess2(t)).cid;
  } catch {
    return c.body(null, 401);
  }
  await q(`UPDATE credentials SET revoked_at=now() WHERE id=$1`, [cid]);
  await q(`UPDATE refresh_tokens SET consumed_at=now() WHERE credential_id=$1 AND consumed_at IS NULL`, [cid]);
  return c.body(null, 204);
});
app.get("/auth/whoami", async (c) => {
  const t = bearer2(c);
  if (!t) return c.body(null, 401);
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    const payload = await verifyAccess2(t);
    const row = await q(`SELECT family_scope FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [payload.cid]);
    if (!row || row.rowCount === 0) return c.body(null, 401);
    return c.json({ family_id: row.rows[0].family_scope });
  } catch {
    return c.body(null, 401);
  }
});
app.post("/families", async (c) => {
  const t = bearer2(c);
  if (!t) return c.body(null, 401);
  let sub;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    sub = (await verifyAccess2(t)).sub;
  } catch {
    return c.body(null, 401);
  }
  const body = await c.req.json().catch(() => null);
  if (!body?.name || typeof body.name !== "string") return c.json({ type: "bad-name" }, 400);
  const { createFamily: createFamily2 } = await Promise.resolve().then(() => (init_identity(), identity_exports));
  const { familyId } = await createFamily2(sub, body.name);
  await q(`UPDATE credentials SET family_scope=$1 WHERE user_id=$2 AND family_scope IS NULL`, [familyId, sub]);
  return c.json({ familyId });
});
app.get("/.well-known/jwks.json", async (c) => {
  c.header("cache-control", "public, max-age=300");
  const { jwks: jwks2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
  return c.json(await jwks2());
});
app.put("/families/:fid/cards/:id", async (c) => {
  const fid = c.req.param("fid"), id2 = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!a.scopes.includes("content:write")) return c.json({ type: "forbidden" }, 403);
  const raw = await c.req.json().catch(() => null);
  if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
  let body = stripServerManaged(raw);
  body = stampProvenance(body, a.cred.id);
  const parsed = BriefingCardSchema.safeParse({ ...body, id: id2 });
  if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
  return c.json(await upsertCard(fid, id2, parsed.data), 200);
});
app.get("/families/:fid/cards", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  return c.json(await listCards(fid));
});
app.delete("/families/:fid/cards/:id", async (c) => {
  const fid = c.req.param("fid"), id2 = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!a.scopes.includes("content:write")) return c.json({ type: "forbidden" }, 403);
  return c.body(null, await softDeleteCard(fid, id2) ? 204 : 404);
});
app.post("/device/authorize", async (c) => {
  const { clientIp: clientIp2, hit: hit2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
  const ip = clientIp2(c);
  const rl = await hit2(`ip:authorize:${ip}`, 600, 10);
  if (!rl.ok) return c.body(null, 429);
  const body = await c.req.json().catch(() => ({}));
  const { createAuthorization: createAuthorization2 } = await Promise.resolve().then(() => (init_device(), device_exports));
  const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
  const { device_code, user_code } = await createAuthorization2(body?.client ?? "familyai-cli", ip, c.req.header("user-agent") ?? null);
  await audit2("device.authorize", { detail: { ip } });
  const base = `${new URL(c.req.url).origin}/device`;
  return c.json({ device_code, user_code, verification_uri: base, verification_uri_complete: `${base}?user_code=${user_code}`, expires_in: 600, interval: 5 });
});
app.post("/device/token", async (c) => {
  const { clientIp: clientIp2, hit: hit2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
  const ip = clientIp2(c);
  const rl = await hit2(`ip:token:${ip}`, 600, 600);
  if (!rl.ok) return c.body(null, 429);
  const body = await c.req.json().catch(() => null);
  if (!body?.device_code) return c.json({ error: "invalid_request" }, 400);
  const { redeem: redeem2 } = await Promise.resolve().then(() => (init_device(), device_exports));
  const { mintAccess: mintAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
  const { issueRefresh: issueRefresh2 } = await Promise.resolve().then(() => (init_refresh(), refresh_exports));
  const out = await redeem2(body.device_code, mintAccess2, issueRefresh2);
  if ("tokens" in out) {
    const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
    await audit2("device.token.redeemed", { detail: { device_code: body.device_code } });
    return c.json(out.tokens, 200);
  }
  return c.json({ error: out.error }, 400);
});
async function deviceOwnerGate(c, fid) {
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return { status: a.status };
  if (a.role !== "owner") return { status: 403 };
  if (a.cred.kind !== "app") return { status: 403 };
  return { sub: a.userId };
}
app.post("/families/:fid/device/approve", async (c) => {
  const fid = c.req.param("fid");
  const g = await deviceOwnerGate(c, fid);
  if ("status" in g) return c.body(null, g.status);
  const { isLocked: isLocked2, recordFailure: recordFailure2, resetFailures: resetFailures2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
  const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
  const lockKey = `account:approve:${g.sub}`;
  if (await isLocked2(lockKey)) {
    await audit2("device.lockout", { actorUserId: g.sub });
    return c.body(null, 429);
  }
  const body = await c.req.json().catch(() => null);
  if (!body?.user_code) return c.json({ type: "bad-request" }, 400);
  const r = await q(
    `UPDATE device_authorizations SET status='approved', user_id=$1, family_id=$2, approved_at=now()
     WHERE user_code=$3 AND status='pending' AND expires_at > now() RETURNING device_code`,
    [g.sub, fid, body.user_code]
  );
  if (r.rowCount !== 1) {
    await recordFailure2(lockKey, 900, 5, 900);
    return c.body(null, 404);
  }
  await resetFailures2(lockKey);
  const { clientIp: clientIp2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
  await audit2("device.approve", { actorUserId: g.sub, familyId: fid, detail: { ip: clientIp2(c) } });
  return c.body(null, 204);
});
app.post("/families/:fid/device/deny", async (c) => {
  const fid = c.req.param("fid");
  const g = await deviceOwnerGate(c, fid);
  if ("status" in g) return c.body(null, g.status);
  const body = await c.req.json().catch(() => null);
  if (!body?.user_code) return c.json({ type: "bad-request" }, 400);
  const r = await q(`UPDATE device_authorizations SET status='denied' WHERE user_code=$1 AND status='pending' AND expires_at > now() RETURNING device_code`, [body.user_code]);
  const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
  if (r.rowCount === 1) await audit2("device.deny", { actorUserId: g.sub, familyId: fid });
  return c.body(null, r.rowCount === 1 ? 204 : 404);
});
app.get("/families/:fid/sync", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
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
