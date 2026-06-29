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
var db_exports = {};
__export(db_exports, {
  pool: () => pool,
  q: () => q
});
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

// src/auth/scope.ts
var scope_exports = {};
__export(scope_exports, {
  grantScopes: () => grantScopes,
  grantedHubIds: () => grantedHubIds,
  requireScope: () => requireScope,
  resolveGrants: () => resolveGrants,
  scopeAllows: () => scopeAllows
});
async function resolveGrants(credId2) {
  const r = await q(`SELECT scope FROM credential_grants WHERE credential_id=$1`, [credId2]);
  return r.rows.map((x) => x.scope);
}
function scopeAllows(grants, resource, action) {
  if (grants.includes(`content:${action}`)) return true;
  if (resource !== "content" && grants.includes(`${resource}:${action}`)) return true;
  return false;
}
async function requireScope(credId2, resource, action) {
  return scopeAllows(await resolveGrants(credId2), resource, action);
}
function grantedHubIds(grants, action) {
  if (grants.includes(`content:${action}`)) return null;
  const prefix = "hub:", suffix = `:${action}`;
  return grants.filter((g) => g.startsWith(prefix) && g.endsWith(suffix) && g.length > prefix.length + suffix.length).map((g) => g.slice(prefix.length, g.length - suffix.length));
}
async function grantScopes(credId2, scopes, client) {
  const exec = client ? (t, p) => client.query(t, p) : (t, p) => q(t, p);
  for (const s of scopes) {
    await exec(`INSERT INTO credential_grants(credential_id, scope) VALUES ($1,$2) ON CONFLICT DO NOTHING`, [credId2, s]);
  }
}
var init_scope = __esm({
  "src/auth/scope.ts"() {
    "use strict";
    init_db();
  }
});

// src/auth/sweep.ts
var sweep_exports = {};
__export(sweep_exports, {
  sweep: () => sweep
});
async function sweep(graceMs = 24 * 3600 * 1e3) {
  const grace = new Date(Date.now() - graceMs).toISOString();
  const rate = (await q(
    `DELETE FROM rate_limits WHERE window_start < $1 AND (locked_until IS NULL OR locked_until < now())`,
    [grace]
  )).rowCount ?? 0;
  const devices = (await q(
    `DELETE FROM device_authorizations
       WHERE created_at < $1 AND (expires_at < now() OR status IN ('denied','expired','consumed'))`,
    [grace]
  )).rowCount ?? 0;
  const invites = (await q(
    `DELETE FROM invites i
       WHERE i.used_count = 0 AND i.status <> 'active' AND i.expires_at < $1
         AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.invite_id = i.id)`,
    [grace]
  )).rowCount ?? 0;
  const refresh = (await q(
    `DELETE FROM refresh_tokens WHERE expires_at < $1`,
    [grace]
  )).rowCount ?? 0;
  const opLogGrace = new Date(Date.now() - OP_LOG_TTL_MS).toISOString();
  const opLog = (await q(
    `DELETE FROM op_log WHERE created_at < $1`,
    [opLogGrace]
  )).rowCount ?? 0;
  return { rate_limits: rate, device_authorizations: devices, invites, refresh_tokens: refresh, op_log: opLog };
}
var OP_LOG_TTL_MS;
var init_sweep = __esm({
  "src/auth/sweep.ts"() {
    "use strict";
    init_db();
    OP_LOG_TTL_MS = 7 * 24 * 3600 * 1e3;
  }
});

// src/auth/identity.ts
var identity_exports = {};
__export(identity_exports, {
  FirebaseVerifier: () => FirebaseVerifier,
  StubVerifier: () => StubVerifier,
  createFamily: () => createFamily,
  findOrCreateUser: () => findOrCreateUser,
  mintCredentialFor: () => mintCredentialFor
});
import { randomBytes } from "node:crypto";
import { jwtVerify as jwtVerify2, decodeJwt, createRemoteJWKSet } from "jose";
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
async function mintCredentialFor(userId) {
  const credentialId = id("cred");
  await q(
    `INSERT INTO credentials(id,user_id,kind,scopes) VALUES ($1,$2,'app','{content:read,content:write,content:delete}')`,
    [credentialId, userId]
  );
  const { grantScopes: grantScopes2 } = await Promise.resolve().then(() => (init_scope(), scope_exports));
  await grantScopes2(credentialId, ["content:read", "content:write", "content:delete"]);
  return { credentialId };
}
var StubVerifier, FIREBASE_JWKS_URL, FirebaseVerifier, id;
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
    FIREBASE_JWKS_URL = process.env.FIREBASE_JWKS_URI || "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
    FirebaseVerifier = class {
      jwks;
      opts;
      // NB: no constructor parameter properties — Node's strip-only TS loader
      // (`node src/server.ts`) rejects them (ERR_UNSUPPORTED_TYPESCRIPT_SYNTAX),
      // even though esbuild (vitest + the Vercel bundle) accepts them.
      constructor(opts) {
        this.opts = opts;
        this.jwks = opts.jwks ?? createRemoteJWKSet(new URL(FIREBASE_JWKS_URL));
      }
      async verify(assertion) {
        const idToken = typeof assertion === "string" ? assertion : assertion?.idToken;
        if (!idToken) throw new Error("missing idToken");
        const iss = `https://securetoken.google.com/${this.opts.projectId}`;
        let payload;
        if (this.opts.emulator) {
          payload = decodeJwt(idToken);
          if (payload.iss !== iss) throw new Error("bad iss");
          if (payload.aud !== this.opts.projectId) throw new Error("bad aud");
          const exp = Number(payload.exp);
          if (!exp || exp * 1e3 < Date.now()) throw new Error("expired");
          if (!payload.sub) throw new Error("missing sub");
        } else {
          ({ payload } = await jwtVerify2(idToken, this.jwks, {
            algorithms: ["RS256"],
            issuer: iss,
            audience: this.opts.projectId
          }));
        }
        const fb = payload.firebase ?? {};
        const provider = fb.sign_in_provider;
        if (!provider || provider === "anonymous") throw new Error("unsupported provider");
        const ids = fb.identities?.[provider];
        const providerUid = Array.isArray(ids) && ids[0] ? String(ids[0]) : String(payload.sub);
        return { provider, provider_uid: providerUid, email_verified: !!payload.email_verified };
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
       VALUES ($1,$2,$3,'cli','{content:read,content:write,content:delete}', 'dayfold-cli '||left(coalesce($4,''),64))`,
      [cid, user_id, family_id, origin_ua]
    );
    const { grantScopes: grantScopes2 } = await Promise.resolve().then(() => (init_scope(), scope_exports));
    await grantScopes2(cid, ["content:read", "content:write", "content:delete"], client);
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

// src/auth/origin.ts
var origin_exports = {};
__export(origin_exports, {
  classifyOrigin: () => classifyOrigin
});
function ipv4ToInt(ip) {
  const m = ip.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (!m) return null;
  const o = m.slice(1, 5).map(Number);
  if (o.some((n) => n > 255)) return null;
  return (o[0] << 24 | o[1] << 16 | o[2] << 8 | o[3]) >>> 0;
}
function isPrivateOrReserved(n) {
  const inR = (cidr) => {
    const [addr, b] = cidr.split("/");
    const bits = Number(b);
    const mask = bits === 0 ? 0 : 4294967295 << 32 - bits >>> 0;
    return (n & mask) >>> 0 === (ipv4ToInt(addr) & mask) >>> 0;
  };
  return [
    "10.0.0.0/8",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "127.0.0.0/8",
    "169.254.0.0/16",
    "100.64.0.0/10",
    "0.0.0.0/8",
    "192.0.2.0/24",
    "198.51.100.0/24",
    "203.0.113.0/24",
    "240.0.0.0/4"
  ].some(inR);
}
function classifyOrigin(ip) {
  if (!ip || ip === "unknown") return "unknown";
  if (ip.includes(":")) return "unknown";
  const n = ipv4ToInt(ip);
  if (n === null) return "unknown";
  if (isPrivateOrReserved(n)) return "unknown";
  for (const [base, mask] of RANGES) if ((n & mask) >>> 0 === base) return "datacenter";
  return "residential";
}
var DATACENTER_CIDRS, RANGES;
var init_origin = __esm({
  "src/auth/origin.ts"() {
    "use strict";
    DATACENTER_CIDRS = [
      // AWS
      "3.0.0.0/9",
      "13.32.0.0/12",
      "15.177.0.0/16",
      "18.32.0.0/11",
      "35.152.0.0/13",
      "52.0.0.0/11",
      "54.144.0.0/12",
      "99.78.0.0/18",
      // Google Cloud
      "34.0.0.0/9",
      "35.184.0.0/13",
      "104.196.0.0/14",
      "130.211.0.0/16",
      "146.148.0.0/17",
      // Microsoft Azure
      "20.0.0.0/8",
      "40.64.0.0/10",
      "13.64.0.0/11",
      "104.40.0.0/13",
      "52.224.0.0/11",
      // DigitalOcean
      "159.65.0.0/16",
      "165.227.0.0/16",
      "167.71.0.0/16",
      "134.209.0.0/16",
      "138.197.0.0/16",
      "157.230.0.0/16",
      "64.225.0.0/16",
      "146.190.0.0/16",
      // Linode / Akamai
      "45.33.0.0/16",
      "45.56.0.0/16",
      "139.162.0.0/16",
      "172.104.0.0/15",
      "173.255.192.0/18",
      // Hetzner
      "5.9.0.0/16",
      "88.99.0.0/16",
      "116.202.0.0/16",
      "95.216.0.0/15",
      "78.46.0.0/15",
      // OVH
      "51.38.0.0/16",
      "51.68.0.0/16",
      "137.74.0.0/16",
      "145.239.0.0/16",
      "51.83.0.0/16",
      // Oracle Cloud
      "129.146.0.0/16",
      "132.145.0.0/16",
      "140.238.0.0/16",
      // Cloudflare
      "104.16.0.0/13",
      "172.64.0.0/13",
      "162.158.0.0/15",
      // Vultr
      "45.32.0.0/16",
      "45.63.0.0/16",
      "108.61.0.0/16",
      "149.28.0.0/16"
    ];
    RANGES = DATACENTER_CIDRS.map((cidr) => {
      const [addr, bitsStr] = cidr.split("/");
      const bits = Number(bitsStr);
      const mask = bits === 0 ? 0 : 4294967295 << 32 - bits >>> 0;
      return [(ipv4ToInt(addr) & mask) >>> 0, mask];
    });
  }
});

// src/auth/invites.ts
var invites_exports = {};
__export(invites_exports, {
  createInvite: () => createInvite,
  genInviteToken: () => genInviteToken,
  hashInvite: () => hashInvite,
  redeem: () => redeem2
});
import { randomBytes as randomBytes4, createHash as createHash3 } from "node:crypto";
async function createInvite(familyId, createdBy, mode, role, maxUses) {
  const token = genInviteToken();
  const inviteId = id2();
  const ttl = mode === "qr" ? "15 minutes" : "72 hours";
  await q(
    `INSERT INTO invites(id, family_id, role, token_hash, mode, max_uses, created_by, expires_at)
     VALUES ($1,$2,$3,$4,$5,$6,$7, now() + $8::interval)`,
    [inviteId, familyId, role, hashInvite(token), mode, maxUses, createdBy, ttl]
  );
  return { inviteId, token };
}
async function redeem2(token, sub) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const inv = await client.query(
      `SELECT id, family_id, role, used_count, max_uses FROM invites
       WHERE token_hash=$1 AND status='active' AND used_count<max_uses AND expires_at>now() FOR UPDATE`,
      [hashInvite(token)]
    );
    if (inv.rowCount !== 1) {
      await client.query("ROLLBACK");
      return { notfound: true };
    }
    const { id: invId, family_id, role } = inv.rows[0];
    await client.query(`SELECT pg_advisory_xact_lock(hashtext($1))`, [family_id]);
    const pend = await client.query(
      `SELECT count(*)::int n FROM memberships WHERE family_id=$1 AND status='pending'`,
      [family_id]
    );
    if (pend.rows[0].n >= PENDING_CAP) {
      await client.query("ROLLBACK");
      return { capfull: true };
    }
    const ins = await client.query(
      `INSERT INTO memberships(user_id, family_id, role, status, invite_id)
       VALUES ($1,$2,$3,'pending',$4) ON CONFLICT (user_id, family_id) DO NOTHING RETURNING 1`,
      [sub, family_id, role, invId]
    );
    if (ins.rowCount === 1) {
      await client.query(
        `UPDATE invites SET used_count=used_count+1,
           status = CASE WHEN used_count+1 >= max_uses THEN 'exhausted' ELSE 'active' END
         WHERE id=$1 AND status='active'`,
        [invId]
      );
      await client.query("COMMIT");
      return { ok: true, family_id, role };
    }
    const cur = await client.query(
      `SELECT status FROM memberships WHERE user_id=$1 AND family_id=$2`,
      [sub, family_id]
    );
    await client.query("COMMIT");
    return { conflict: cur.rows[0].status };
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
}
var hashInvite, genInviteToken, id2, PENDING_CAP;
var init_invites = __esm({
  "src/auth/invites.ts"() {
    "use strict";
    init_db();
    hashInvite = (t) => createHash3("sha256").update(t, "utf8").digest("hex");
    genInviteToken = () => randomBytes4(32).toString("base64url");
    id2 = () => "inv_" + randomBytes4(9).toString("hex");
    PENDING_CAP = 20;
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
var LinkPayloadSchema = z.object({ "url": z.string().url(), "label": z.string().optional(), "source": z.string().optional(), "thumbnailUrl": z.string().url().max(2048).describe("link preview image; https + allowlisted host (ADR 0036)").optional(), "thumbnailAlt": z.string().max(256).describe("a11y alt for thumbnailUrl").optional() }).strict();
var ChecklistPayloadSchema = z.object({ "items": z.array(z.object({ "id": z.any(), "text": z.string().describe("loop-authoritative at M0"), "done": z.boolean().describe("member-mutable (the done-triple)").default(false), "doneBy": z.string().describe("NEW (ADR 0038) \u2014 user id who toggled (display byline)").optional(), "doneAt": z.any().optional(), "ord": z.number().int().describe("NEW (ADR 0038) \u2014 order; loop-authoritative at M0 (\xA75.3); doneBy/doneAt/id are the new ADR-0038 fields").default(0), "due": z.any().optional(), "assignee": z.string().describe("loop-authoritative at M0").optional() }).strict()) }).strict();
var DocumentPayloadSchema = z.object({ "ref": z.string().describe("url | fileRef (links+small refs at MVP)"), "label": z.string().optional(), "kind": z.string().optional(), "thumbnailUrl": z.string().url().max(2048).describe("document preview image; https + allowlisted host (ADR 0036)").optional(), "thumbnailAlt": z.string().max(256).describe("a11y alt for thumbnailUrl").optional() }).strict();
var MilestonePayloadSchema = z.object({ "date": z.any(), "label": z.string() }).strict();
var ContactPayloadSchema = z.object({ "name": z.string(), "role": z.string().optional(), "phone": z.string().optional(), "email": z.string().optional(), "avatarUrl": z.string().url().max(2048).describe("contact avatar photo; https + allowlisted host (ADR 0036); falls back to initials").optional(), "accentColor": z.string().regex(new RegExp("^#[0-9a-fA-F]{6}$")).describe("decorative-only accent seed (ADR 0036)").optional() }).strict();
var LocationPayloadSchema = z.object({ "label": z.string(), "address": z.string().optional(), "mapUrl": z.string().optional() }).strict();
var BudgetPayloadSchema = z.object({ "items": z.array(z.object({ "label": z.string(), "amount": z.number(), "paid": z.boolean().default(false) }).strict()) }).strict();
var BlockSchema = z.object({ "id": z.any(), "type": z.enum(["text", "markdown", "link", "checklist", "document", "milestone", "contact", "location", "budget"]), "ord": z.number().int().default(0), "version": z.any().optional(), "body_md": z.string().max(1048576).describe("long-form markdown (text/markdown blocks); inline \u22641MB at M0, else spill to body_ref (06, M1)").optional(), "body_ref": z.string().describe("object-storage KEY when spilled (M1); never a URL; XOR with body_md").optional(), "payload": z.any().describe("structured fields for non-markdown block types; variant by `type` (see $comment)").optional(), "triggers": z.array(z.any()).optional(), "actions": z.array(z.any()).optional(), "provenance": z.any() }).strict().and(z.any());
var SectionSchema = z.object({ "id": z.any(), "title": z.string().describe("[CONTENT/E2E-hole]").optional(), "ord": z.number().int().default(0), "version": z.any().optional(), "blocks": z.array(z.any()).optional() }).strict();
var HubSchema = z.object({ "id": z.any(), "type": z.string().describe("bounded template-catalog key (ADR 0004/0006): vacation|starting-college|move|party-event|new-baby|medical|school-year \u2014 app-validated"), "title": z.string().describe("[CONTENT/E2E-hole]"), "status": z.enum(["planning", "active", "archived"]).default("active"), "start_at": z.any().optional(), "end_at": z.any().optional(), "countdown_to": z.any().optional(), "version": z.any().optional(), "sections": z.array(z.any()).optional(), "media": z.object({ "heroUrl": z.string().url().max(2048).describe("hero image (Hub detail header + list-row fallback). https + allowlisted host.").optional(), "thumbnailUrl": z.string().url().max(2048).describe("list-row 1:1 thumbnail; absent \u2192 falls back to heroUrl client-side.").optional(), "heroFit": z.enum(["cover", "contain"]).describe("cover=photo edge-to-edge crop; contain=logo letterboxed on accent tint.").optional(), "imageAlt": z.string().max(256).describe("a11y alt \u2192 contentDescription (else derived from title).").optional(), "icon": z.string().max(40).describe("curated icon NAME, server-validated vs the bundled set (ADR 0036); unknown \u2192 fallback tile.").optional(), "accentColor": z.string().regex(new RegExp("^#[0-9a-fA-F]{6}$")).describe("decorative-only accent seed (edge/tile/chip/scrim); never body text (WCAG 1.4.1). Lowercased on write.").optional() }).strict().describe("visual enrichment (ADR 0036; all optional, absent = unenriched/today's look). URLs are https + allowlisted-host (ADR 0036 shared validator); icon \u2208 curated set; accentColor is decorative-only.").optional() }).strict();
var BriefingCardSchema = z.object({ "id": z.any(), "kind": z.enum(["action", "info", "weather", "countdown"]).default("info"), "title": z.string().max(4096), "body_md": z.string().max(1048576).describe("limited inline markdown only (1MB cap, F8)").optional(), "target": z.object({ "hubId": z.string().optional(), "sectionId": z.string().optional(), "blockId": z.string().optional() }).strict().describe("deep-link into a hub (resolved client-side vs local cache, nearest-ancestor)").optional(), "triggers": z.array(z.any()).optional(), "actions": z.array(z.any()).optional(), "not_before": z.any().optional(), "expires_at": z.any().optional(), "version": z.any().optional(), "provenance": z.any(), "type": z.enum(["file", "link", "invite", "contact", "geo", "email"]).describe("content type (ADR 0022 D1) \u2014 drives the Now-card / detail layout. OPTIONAL for back-compat with kind-only M0 cards.").optional(), "media": z.object({ "icon": z.string().max(40).describe("curated icon NAME (server-validated); unknown \u2192 fallback.").optional(), "accentColor": z.string().regex(new RegExp("^#[0-9a-fA-F]{6}$")).describe("decorative-only accent seed; never body text. Lowercased on write.").optional(), "thumbnailUrl": z.string().url().max(2048).describe("optional leading thumbnail; https + allowlisted host.").optional(), "imageAlt": z.string().max(256).describe("a11y alt for thumbnailUrl.").optional(), "imageFit": z.enum(["cover", "contain"]).optional() }).strict().describe("card visual enrichment (ADR 0036; all optional). icon+accent on the kind chip + optional leading thumbnail. Same shared URL/host/icon/hex validation as Hub.media.").optional(), "hubRef": z.string().describe("parent Hub id \u2014 the adaptive supporting pane's 'PART OF THIS HUB' (ADR 0022; CL-10). Optional.").optional(), "relatedKicker": z.string().describe("section header for the RELATED rows (e.g. 'FROM THE SAME EMAIL'). CL-8.").optional(), "related": z.array(z.object({ "relation": z.string().describe("same-email | same-thread | same-hub | same-trip | attachment | contact-of"), "targetId": z.string(), "targetType": z.enum(["file", "link", "invite", "contact", "geo", "email"]), "title": z.string().optional(), "sub": z.string().optional() }).strict()).describe("cross-links to other cards in THIS family (CL-8). targetId resolves client-side vs the local cache; title/sub are author-denormalized so a row renders without resolving. Same-tenant only (rides authorizeTenant).").optional(), "privacy": z.object({ "storage": z.enum(["on_device", "in_browser", "location_local", "matched_on_device"]).optional() }).strict().describe("honesty chip (ADR 0014/0015) \u2014 a claim allowed ONLY where a real schema/API/client boundary enforces it.").optional(), "payload": z.any().superRefine((x, ctx) => {
  const schemas = [z.object({ "file": z.object({ "filename": z.string().optional(), "mime": z.string().optional(), "size": z.number().int().optional(), "pages": z.number().int().optional(), "source": z.string().optional(), "owner": z.string().optional(), "modified": z.string().datetime({ offset: true }).optional(), "sharedWith": z.array(z.string()).optional(), "docRef": z.string().describe("url | opaque storage ref").optional() }).strict() }).strict(), z.object({ "link": z.object({ "url": z.string().url().optional(), "domain": z.string().optional(), "title": z.string().optional(), "ogDesc": z.string().describe("author-stamped OG; server never fetches the URL (no SSRF)").optional(), "favicon": z.string().optional(), "kind": z.enum(["page", "form"]).optional(), "fieldCount": z.number().int().optional(), "closesAt": z.string().datetime({ offset: true }).optional(), "savedAt": z.string().datetime({ offset: true }).optional() }).strict() }).strict(), z.object({ "invite": z.object({ "eventName": z.string().optional(), "host": z.string().optional(), "startAt": z.string().datetime({ offset: true }).optional(), "place": z.string().optional(), "rsvpBy": z.string().datetime({ offset: true }).optional(), "rsvpState": z.enum(["yes", "no", "none"]).describe("display-of-state at M0 (no write path; ADR 0020/0016)").optional(), "guestCount": z.number().int().optional(), "confirmedCount": z.number().int().optional(), "notes": z.string().optional() }).strict() }).strict(), z.object({ "contact": z.object({ "name": z.string().optional(), "company": z.string().optional(), "role": z.string().optional(), "phone": z.string().optional(), "email": z.string().optional(), "address": z.string().optional(), "hours": z.string().optional(), "linkedEventId": z.string().optional(), "deliveryWindow": z.string().optional() }).strict() }).strict(), z.object({ "geo": z.object({ "label": z.string().optional(), "address": z.string().optional(), "lat": z.number().optional(), "lng": z.number().optional(), "etaMin": z.number().int().optional(), "distance": z.string().optional(), "travelMode": z.string().optional(), "parking": z.string().optional(), "leaveBy": z.string().datetime({ offset: true }).optional(), "linkedEventId": z.string().optional() }).strict() }).strict(), z.object({ "email": z.object({ "from": z.string().optional(), "fromAddr": z.string().optional(), "subject": z.string().optional(), "date": z.string().datetime({ offset: true }).optional(), "threadLen": z.number().int().optional(), "bodyExcerpt": z.string().describe("[E2E-ciphertext] authored over the operator's OWN mail (CLI/Claude) \u2014 never a server-side Gmail restricted-scope read (Guardrail 3)").optional(), "attachments": z.array(z.object({ "name": z.string().optional(), "mime": z.string().optional(), "size": z.number().int().optional() }).strict()).optional(), "labels": z.array(z.string()).optional() }).strict() }).strict()];
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
}).describe("[E2E-ciphertext at M1] typed content payload, variant selected by `type` (ADR 0022 D1). Inline oneOf (no internal $ref) so codegen emits TYPED variants, never z.any.").optional() }).strict().describe("the 'Now' surface");
var PlaceSchema = z.object({ "id": z.any(), "label": z.string(), "kind": z.enum(["home", "school", "store", "other"]).describe("category (drives the place icon in the UI; design alignment)").default("other"), "lat": z.number(), "lng": z.number(), "radius_m": z.number().int().default(150), "version": z.any().optional() }).strict().describe("ADR 0014 reusable named place; family content (encrypted at rest, never live position)");
var SyncResponseSchema = z.object({ "changes": z.object({ "hubs": z.array(z.any()).optional(), "sections": z.array(z.any()).optional(), "blocks": z.array(z.any()).optional(), "cards": z.array(z.any()).optional(), "places": z.array(z.any()).optional() }), "tombstones": z.array(z.object({ "type": z.enum(["hub", "section", "block", "card", "place"]), "id": z.string() }).strict()), "next_cursor": z.string().optional(), "has_more": z.boolean() }).strict().describe("GET /families/{fid}/sync (03 \xA7sync)");

// src/content-validation.ts
function crossValidateCard(card) {
  const hasType = card.type != null;
  const hasPayload = card.payload != null;
  if (!hasType && !hasPayload) return [];
  if (hasType !== hasPayload) {
    return [{
      path: [hasType ? "payload" : "type"],
      message: hasType ? "a typed card (`type` set) must carry a matching `payload`" : "`payload` requires a `type` discriminator"
    }];
  }
  const keys = Object.keys(card.payload);
  if (keys.length !== 1 || keys[0] !== card.type) {
    return [{
      path: ["payload"],
      message: `payload variant "${keys[0] ?? "(none)"}" does not match type "${String(card.type)}"`
    }];
  }
  return [];
}
function isEncryptedEnvelope(p) {
  if (typeof p !== "object" || p === null || Array.isArray(p)) return false;
  const o = p;
  return typeof o.ct === "string" && typeof o.nonce === "string" && typeof o.alg === "string";
}
function blockPayloadIssues(block) {
  const { type, payload, body_md } = block;
  if (payload == null) return [];
  if (isEncryptedEnvelope(payload)) return [];
  if (typeof payload !== "object" || Array.isArray(payload)) {
    return [{ path: ["payload"], message: "payload must be an object" }];
  }
  if (type === "text" || type === "markdown") return [];
  const p = payload;
  const has = (...keys) => keys.some((k) => p[k] != null);
  const arr = (k) => Array.isArray(p[k]) && p[k].length > 0;
  const hasBody = typeof body_md === "string" && body_md.trim().length > 0;
  const ok = type === "checklist" ? arr("items") : type === "budget" ? arr("items") || has("total", "spent") : type === "document" ? has("ref", "docRef") : type === "link" ? has("url") : type === "contact" ? has("name") : type === "location" ? has("label") : type === "milestone" ? has("date", "label") || hasBody : true;
  return ok ? [] : [{ path: ["payload"], message: `block ${String(type)}: payload present but missing its core field` }];
}

// src/media-validation.ts
var ALLOWED_IMAGE_HOSTS = /* @__PURE__ */ new Set(["upload.wikimedia.org"]);
var CURATED_ICONS = /* @__PURE__ */ new Set([
  "school",
  "luggage",
  "medical",
  "move",
  "party",
  "baby",
  "calendar",
  "location",
  "link",
  "document",
  "contact",
  "budget",
  "travel",
  "car",
  "food",
  "pet",
  "sport",
  "list"
]);
var MAX_URL_LEN = 2048;
var ACCENT_RE = /^#[0-9a-fA-F]{6}$/;
function imageUrlError(url) {
  if (typeof url !== "string") return "must be a string";
  if (url.length === 0 || url.length > MAX_URL_LEN) return "url length out of range";
  if (/[\x00-\x20\x7f\\]/.test(url)) return "url contains illegal characters";
  let u;
  try {
    u = new URL(url);
  } catch {
    return "url does not parse";
  }
  if (u.protocol !== "https:") return "scheme must be https";
  if (u.username !== "" || u.password !== "") return "userinfo not allowed";
  if (u.port !== "") return "explicit port not allowed";
  const host = u.hostname.replace(/\.$/, "");
  if (!ALLOWED_IMAGE_HOSTS.has(host)) return `host "${host}" is not on the image allowlist`;
  if (u.pathname.toLowerCase().endsWith(".svg")) return "SVG images are not allowed";
  return null;
}
function iconError(icon) {
  if (typeof icon !== "string") return "must be a string";
  return CURATED_ICONS.has(icon) ? null : `icon "${icon}" is not in the curated set`;
}
function accentHexError(hex) {
  if (typeof hex !== "string") return "must be a string";
  return ACCENT_RE.test(hex) ? null : "accentColor must match #RRGGBB";
}
function urlField(media, key, base, out) {
  if (media[key] == null) return;
  const e = imageUrlError(media[key]);
  if (e) out.push({ path: [...base, key], message: e });
}
function validateHubMedia(media) {
  if (media == null) return [];
  if (typeof media !== "object") return [{ path: ["media"], message: "must be an object" }];
  const m = media, out = [];
  urlField(m, "heroUrl", ["media"], out);
  urlField(m, "thumbnailUrl", ["media"], out);
  if (m.icon != null) {
    const e = iconError(m.icon);
    if (e) out.push({ path: ["media", "icon"], message: e });
  }
  if (m.accentColor != null) {
    const e = accentHexError(m.accentColor);
    if (e) out.push({ path: ["media", "accentColor"], message: e });
  }
  return out;
}
function validateCardMedia(media) {
  if (media == null) return [];
  if (typeof media !== "object") return [{ path: ["media"], message: "must be an object" }];
  const m = media, out = [];
  urlField(m, "thumbnailUrl", ["media"], out);
  if (m.icon != null) {
    const e = iconError(m.icon);
    if (e) out.push({ path: ["media", "icon"], message: e });
  }
  if (m.accentColor != null) {
    const e = accentHexError(m.accentColor);
    if (e) out.push({ path: ["media", "accentColor"], message: e });
  }
  return out;
}
function validateBlockPayloadMedia(type, payload) {
  if (payload == null || typeof payload !== "object") return [];
  const p = payload, out = [];
  if (type === "link" || type === "document") urlField(p, "thumbnailUrl", ["payload"], out);
  if (type === "contact") {
    urlField(p, "avatarUrl", ["payload"], out);
    if (p.accentColor != null) {
      const e = accentHexError(p.accentColor);
      if (e) out.push({ path: ["payload", "accentColor"], message: e });
    }
  }
  return out;
}
function normalizedAccent(hex) {
  return typeof hex === "string" ? hex.toLowerCase() : null;
}

// src/repo.ts
init_db();

// src/content/visibility.ts
function cardVisible(row, caller) {
  if (caller.legacy) return true;
  if (!row.visibility || row.visibility === "family") return true;
  return !!caller.userId && Array.isArray(row.audience) && row.audience.includes(caller.userId);
}
function cardVisibilityClause(caller, nextParamIndex) {
  if (caller.legacy) return { sql: "", params: [] };
  return {
    sql: ` AND (visibility = 'family' OR ($${nextParamIndex} = ANY(audience)))`,
    params: [caller.userId ?? "\0"]
    // non-null sentinel; a real user id never equals it
  };
}

// src/repo.ts
var J = (v) => v == null ? null : JSON.stringify(v);
var SYNC_LIMIT = 200;
async function upsertCard(familyId, id3, b) {
  const visibility = b.visibility === "restricted" ? "restricted" : "family";
  const audience = visibility === "restricted" && Array.isArray(b.audience) ? b.audience : null;
  const r = await q(
    `INSERT INTO briefing_cards
       (id, family_id, kind, title, body_md, target_hub_id, target_section_id,
        target_block_id, provenance, triggers, actions, not_before, expires_at,
        type, payload, privacy, hub_ref, related, related_kicker, visibility, audience, media, version)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       kind=EXCLUDED.kind, title=EXCLUDED.title, body_md=EXCLUDED.body_md,
       target_hub_id=EXCLUDED.target_hub_id, target_section_id=EXCLUDED.target_section_id,
       target_block_id=EXCLUDED.target_block_id, provenance=EXCLUDED.provenance,
       triggers=EXCLUDED.triggers, actions=EXCLUDED.actions,
       not_before=EXCLUDED.not_before, expires_at=EXCLUDED.expires_at,
       type=EXCLUDED.type, payload=EXCLUDED.payload, privacy=EXCLUDED.privacy,
       hub_ref=EXCLUDED.hub_ref, related=EXCLUDED.related, related_kicker=EXCLUDED.related_kicker,
       visibility=EXCLUDED.visibility, audience=EXCLUDED.audience, media=EXCLUDED.media,
       version=briefing_cards.version + 1, deleted_at=NULL
     RETURNING *`,
    [
      id3,
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
      b.expires_at ?? null,
      b.type ?? null,
      J(b.payload),
      J(b.privacy),
      b.hubRef ?? null,
      J(b.related),
      b.relatedKicker ?? null,
      visibility,
      audience,
      J(b.media)
    ]
  );
  return r.rows[0];
}
async function listCards(familyId, caller) {
  const vis = cardVisibilityClause(caller, 2);
  const r = await q(
    `SELECT * FROM briefing_cards WHERE family_id=$1 AND deleted_at IS NULL${vis.sql}
     ORDER BY not_before NULLS LAST, id`,
    [familyId, ...vis.params]
  );
  return r.rows;
}
async function softDeleteCard(familyId, id3) {
  const r = await q(
    `UPDATE briefing_cards SET deleted_at=now()
     WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`,
    [familyId, id3]
  );
  return (r.rowCount ?? 0) > 0;
}
async function syncContent(familyId, su, st, si, limit = SYNC_LIMIT) {
  const r = await q(
    `SELECT updated_at, type, id, family_id, deleted_at, payload,
            hub_id, hub_visibility, hub_created_by FROM (
       SELECT updated_at, 'card' AS type, id, family_id, deleted_at,
              to_jsonb(briefing_cards.*) AS payload,
              NULL::text AS hub_id, NULL::text AS hub_visibility, NULL::text AS hub_created_by
         FROM briefing_cards WHERE family_id=$1
       UNION ALL
       SELECT updated_at, 'hub' AS type, id, family_id, deleted_at,
              to_jsonb(hubs.*) AS payload,
              NULL::text AS hub_id, NULL::text AS hub_visibility, NULL::text AS hub_created_by
         FROM hubs WHERE family_id=$1
       UNION ALL
       SELECT s.updated_at, 'section' AS type, s.id, s.family_id, s.deleted_at,
              to_jsonb(s.*) AS payload,
              h.id AS hub_id, h.visibility AS hub_visibility, h.created_by AS hub_created_by
         FROM sections s JOIN hubs h ON h.family_id=s.family_id AND h.id=s.hub_id
        WHERE s.family_id=$1
       UNION ALL
       SELECT b.updated_at, 'block' AS type, b.id, b.family_id, b.deleted_at,
              to_jsonb(b.*) AS payload,
              h.id AS hub_id, h.visibility AS hub_visibility, h.created_by AS hub_created_by
         FROM blocks b
         JOIN sections s ON s.family_id=b.family_id AND s.id=b.section_id
         JOIN hubs h ON h.family_id=s.family_id AND h.id=s.hub_id
        WHERE b.family_id=$1
     ) merged
     WHERE (updated_at, type, id) > ($2::timestamptz, $3, $4)
     ORDER BY updated_at, type, id LIMIT $5`,
    [familyId, su === "" ? "-infinity" : su, st, si, limit]
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
init_scope();

// src/content/write-guard.ts
init_db();

// src/content/hubs.ts
init_db();
var J2 = (v) => v == null ? null : JSON.stringify(v);
function hubVisible(row, caller, allowListHas = () => false) {
  if (caller.legacy) return true;
  if (!row.visibility || row.visibility === "family") return true;
  if (caller.userId && row.created_by && caller.userId === row.created_by) return true;
  return !!caller.userId && !!row.id && allowListHas(row.id);
}
function hubVisibilityClause(caller, p) {
  if (caller.legacy) return { sql: "", params: [] };
  return {
    sql: ` AND (visibility='family' OR created_by=$${p}
              OR EXISTS (SELECT 1 FROM resource_visibility rv
                          WHERE rv.family_id=hubs.family_id AND rv.hub_id=hubs.id AND rv.user_id=$${p}))`,
    params: [caller.userId ?? "\0"]
  };
}
async function listHubs(familyId, caller, grantedHubIds2) {
  const vis = hubVisibilityClause(caller, 2);
  const params = [familyId, ...vis.params];
  let grantSql = "";
  if (grantedHubIds2 !== null) {
    grantSql = ` AND id = ANY($${params.length + 1})`;
    params.push(grantedHubIds2);
  }
  const r = await q(
    `SELECT * FROM hubs WHERE family_id=$1 AND deleted_at IS NULL${vis.sql}${grantSql}
      ORDER BY coalesce(start_at, created_at), id`,
    params
  );
  return r.rows;
}
async function getHub(familyId, id3) {
  const r = await q(`SELECT * FROM hubs WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, id3]);
  return r.rows[0] ?? null;
}
async function allowListFor(familyId, hubId) {
  const r = await q(`SELECT user_id FROM resource_visibility WHERE family_id=$1 AND hub_id=$2`, [familyId, hubId]);
  return new Set(r.rows.map((x) => x.user_id));
}
async function upsertHub(familyId, id3, b, caller, visibility, audience) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const r = await client.query(
      `INSERT INTO hubs (id, family_id, type, title, status, start_at, end_at, countdown_to, visibility, created_by, media, version)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,1)
       ON CONFLICT (family_id, id) DO UPDATE SET
         type=EXCLUDED.type, title=EXCLUDED.title, status=EXCLUDED.status,
         start_at=EXCLUDED.start_at, end_at=EXCLUDED.end_at, countdown_to=EXCLUDED.countdown_to,
         visibility=EXCLUDED.visibility, created_by=COALESCE(hubs.created_by, EXCLUDED.created_by),
         media=EXCLUDED.media,
         version=hubs.version + 1, deleted_at=NULL
       RETURNING *`,
      [
        id3,
        familyId,
        b.type,
        b.title,
        b.status ?? "active",
        b.start_at ?? null,
        b.end_at ?? null,
        b.countdown_to ?? null,
        visibility,
        caller.userId,
        J2(b.media)
      ]
    );
    await client.query(`DELETE FROM resource_visibility WHERE family_id=$1 AND hub_id=$2`, [familyId, id3]);
    if (visibility === "restricted") {
      for (const uid of audience ?? [])
        await client.query(`INSERT INTO resource_visibility(family_id,hub_id,user_id) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING`, [familyId, id3, uid]);
    }
    await client.query("COMMIT");
    return r.rows[0];
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
}
async function archiveHub(familyId, id3) {
  const r = await q(`UPDATE hubs SET status='archived' WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`, [familyId, id3]);
  return (r.rowCount ?? 0) > 0;
}
async function softDeleteHub(familyId, id3) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const h = await client.query(`UPDATE hubs SET deleted_at=now() WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`, [familyId, id3]);
    if ((h.rowCount ?? 0) === 0) {
      await client.query("ROLLBACK");
      return false;
    }
    await client.query(`UPDATE sections SET deleted_at=now() WHERE family_id=$1 AND hub_id=$2 AND deleted_at IS NULL`, [familyId, id3]);
    await client.query(
      `UPDATE blocks SET deleted_at=now()
        WHERE family_id=$1 AND deleted_at IS NULL
          AND section_id IN (SELECT id FROM sections WHERE family_id=$1 AND hub_id=$2)`,
      [familyId, id3]
    );
    await client.query("COMMIT");
    return true;
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
}
async function softDeleteBlock(familyId, id3) {
  const r = await q(`UPDATE blocks SET deleted_at=now() WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL RETURNING id`, [familyId, id3]);
  return (r.rowCount ?? 0) > 0;
}
async function blockForDelete(familyId, id3) {
  const r = await q(`SELECT section_id, created_by, deleted_at FROM blocks WHERE family_id=$1 AND id=$2`, [familyId, id3]);
  if (r.rowCount === 0) return null;
  const row = r.rows[0];
  return { section_id: row.section_id, created_by: row.created_by ?? null, deleted: row.deleted_at != null };
}
async function getHubTree(familyId, hubId) {
  const hub = await getHub(familyId, hubId);
  if (!hub) return null;
  const sections = (await q(`SELECT * FROM sections WHERE family_id=$1 AND hub_id=$2 AND deleted_at IS NULL ORDER BY ord, id`, [familyId, hubId])).rows;
  const blocks = (await q(
    `SELECT * FROM blocks WHERE family_id=$1 AND deleted_at IS NULL
       AND section_id IN (SELECT id FROM sections WHERE family_id=$1 AND hub_id=$2) ORDER BY ord, id`,
    [familyId, hubId]
  )).rows;
  return { hub, sections, blocks };
}
async function hubAudience(familyId, hubId) {
  const r = await q(
    `SELECT m.user_id AS uid, u.display_name, m.role,
            (h.visibility = 'family'
             OR m.user_id = h.created_by
             OR EXISTS (SELECT 1 FROM resource_visibility rv
                         WHERE rv.family_id=$1 AND rv.hub_id=$2 AND rv.user_id=m.user_id)) AS permitted
       FROM memberships m
       JOIN users u ON u.id = m.user_id
       JOIN hubs h ON h.family_id=$1 AND h.id=$2
      WHERE m.family_id=$1 AND m.status='active'
      ORDER BY (m.role='owner') DESC, u.display_name, m.user_id`,
    [familyId, hubId]
  );
  return r.rows;
}
async function liveHubOfSection(familyId, sectionId) {
  const r = await q(
    `SELECT s.hub_id FROM sections s JOIN hubs h ON h.family_id=s.family_id AND h.id=s.hub_id
      WHERE s.family_id=$1 AND s.id=$2 AND s.deleted_at IS NULL AND h.deleted_at IS NULL`,
    [familyId, sectionId]
  );
  return r.rows[0]?.hub_id ?? null;
}
async function upsertSection(familyId, id3, hubId, b) {
  const live = await q(`SELECT 1 FROM hubs WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, hubId]);
  if (live.rowCount === 0) return null;
  const r = await q(
    `INSERT INTO sections (id, family_id, hub_id, title, ord, version)
     VALUES ($1,$2,$3,$4,$5,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       hub_id=EXCLUDED.hub_id, title=EXCLUDED.title, ord=EXCLUDED.ord,
       version=sections.version + 1, deleted_at=NULL
     RETURNING *`,
    [id3, familyId, hubId, b.title ?? null, b.ord ?? 0]
  );
  return r.rows[0];
}
async function getBlock(familyId, id3) {
  const r = await q(`SELECT * FROM blocks WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, id3]);
  return r.rows[0] ?? null;
}
async function upsertBlock(familyId, id3, sectionId, b, opts = {}) {
  const allowResurrect = opts.allowResurrect !== false;
  const live = await q(`SELECT 1 FROM sections WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [familyId, sectionId]);
  if (live.rowCount === 0) return null;
  const r = await q(
    `INSERT INTO blocks (id, family_id, section_id, type, payload, body_md, body_ref, provenance, triggers, actions, ord, created_by, version)
     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,1)
     ON CONFLICT (family_id, id) DO UPDATE SET
       section_id=EXCLUDED.section_id, type=EXCLUDED.type, payload=EXCLUDED.payload,
       body_md=EXCLUDED.body_md, body_ref=EXCLUDED.body_ref, provenance=EXCLUDED.provenance,
       triggers=EXCLUDED.triggers, actions=EXCLUDED.actions, ord=EXCLUDED.ord,
       version=blocks.version + 1, deleted_at=NULL
       ${allowResurrect ? "" : "WHERE blocks.deleted_at IS NULL"}
     RETURNING *`,
    [
      id3,
      familyId,
      sectionId,
      b.type,
      J2(b.payload),
      b.body_md ?? null,
      b.body_ref ?? null,
      J2(b.provenance),
      J2(b.triggers),
      J2(b.actions),
      b.ord ?? 0,
      opts.createdBy ?? null
    ]
  );
  return r.rows[0] ?? null;
}

// src/content/write-guard.ts
init_scope();
function isMemberWrite(a) {
  return !a.legacy && a.cred?.kind === "app";
}
function ifMatchFails(header, liveVersion) {
  if (header == null || header.trim() === "") return false;
  const want = header.replace(/^W\//, "").replace(/^"|"$/g, "").trim();
  return String(liveVersion ?? "") !== want;
}
async function blockState(familyId, id3) {
  const r = await q(`SELECT version, deleted_at FROM blocks WHERE family_id=$1 AND id=$2`, [familyId, id3]);
  if (r.rowCount === 0) return { exists: false, deleted: false, version: null };
  const row = r.rows[0];
  return { exists: true, deleted: row.deleted_at != null, version: Number(row.version) };
}
async function hubWriteGate(familyId, hubId, caller) {
  const hub = await getHub(familyId, hubId);
  if (!hub) return "absent";
  const allow = await allowListFor(familyId, hubId);
  const visible = hubVisible(
    hub,
    { userId: caller.userId, legacy: caller.legacy },
    () => !!caller.userId && allow.has(caller.userId)
  );
  if (!visible) return "invisible";
  if (!await requireScope(caller.cred.id, `hub:${hubId}`, "write")) return "denied";
  return "ok";
}

// src/content/oplog.ts
init_db();
async function findOp(familyId, opId) {
  const r = await q(
    `SELECT result_kind, result_ref, result_version FROM op_log WHERE family_id=$1 AND op_id=$2`,
    [familyId, opId]
  );
  if (r.rowCount === 0) return null;
  const row = r.rows[0];
  return {
    result_kind: row.result_kind ?? null,
    result_ref: row.result_ref ?? null,
    result_version: row.result_version != null ? Number(row.result_version) : null
  };
}
async function recordOp(familyId, opId, kind, ref, version) {
  await q(
    `INSERT INTO op_log (family_id, op_id, result_kind, result_ref, result_version)
     VALUES ($1,$2,$3,$4,$5) ON CONFLICT (family_id, op_id) DO NOTHING`,
    [familyId, opId, kind, ref, version]
  );
}

// src/app.ts
var app = new Hono();
app.get("/health", (c) => c.json({ ok: true, surface: "m0" }));
app.get("/cron/sweep", async (c) => {
  const secret = process.env.CRON_SECRET || "";
  if (!secret) return c.body(null, 404);
  if (!constantTimeEqual(bearer2(c) ?? "", secret)) return c.body(null, 401);
  const { sweep: sweep2 } = await Promise.resolve().then(() => (init_sweep(), sweep_exports));
  return c.json(await sweep2());
});
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
    `INSERT INTO credentials(id,user_id,kind,scopes) VALUES ($1,$2,'app','{content:read,content:write,content:delete}')`,
    [credentialId, userId]
  );
  await grantScopes(credentialId, ["content:read", "content:write", "content:delete"]);
  const { mintAccess: mintAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
  const { issueRefresh: issueRefresh2 } = await Promise.resolve().then(() => (init_refresh(), refresh_exports));
  const access = await mintAccess2({ sub: userId, cid: credentialId });
  const refresh = await issueRefresh2(credentialId);
  return c.json({ access, refresh });
});
app.post("/auth/firebase", async (c) => {
  const body = await c.req.json().catch(() => null);
  const idToken = body?.idToken;
  if (!idToken || typeof idToken !== "string") return c.json({ type: "missing-id-token" }, 400);
  const projectId = process.env.FIREBASE_PROJECT_ID;
  if (!projectId) return c.json({ type: "auth-unconfigured" }, 503);
  const { FirebaseVerifier: FirebaseVerifier2, findOrCreateUser: findOrCreateUser2, mintCredentialFor: mintCredentialFor2 } = await Promise.resolve().then(() => (init_identity(), identity_exports));
  const env = process.env.VERCEL_ENV;
  const emulator = !!process.env.FIREBASE_AUTH_EMULATOR_HOST && env !== "production" && env !== "preview";
  const verifier = new FirebaseVerifier2({ projectId, emulator });
  const idn = await verifier.verify(idToken).catch((e) => {
    console.warn(`[auth/firebase] verify failed: ${e?.message}`);
    return null;
  });
  if (!idn) return c.json({ type: "bad-identity" }, 401);
  const { userId } = await findOrCreateUser2(idn);
  const { credentialId } = await mintCredentialFor2(userId);
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
  let sub, cid;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    ({ sub, cid } = await verifyAccess2(t));
  } catch {
    return c.body(null, 401);
  }
  const credRow = await q(
    `SELECT family_scope FROM credentials WHERE id=$1 AND revoked_at IS NULL`,
    [cid]
  );
  if (!credRow || credRow.rowCount === 0) return c.body(null, 401);
  const family_id = credRow.rows[0].family_scope ?? null;
  const r = await q(
    `SELECT m.family_id, f.name, m.role, m.status FROM memberships m JOIN families f ON f.id=m.family_id
     WHERE m.user_id=$1 AND m.status IN ('active','pending') ORDER BY m.created_at`,
    [sub]
  );
  const grants = await resolveGrants(cid);
  return c.json({ family_id, families: r.rows, grants });
});
app.get("/auth/me", async (c) => {
  const t = bearer2(c);
  if (!t) return c.body(null, 401);
  let sub, cid;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    ({ sub, cid } = await verifyAccess2(t));
  } catch {
    return c.body(null, 401);
  }
  const cred = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!cred || cred.rowCount === 0) return c.body(null, 401);
  const u = (await q(`SELECT id, display_name FROM users WHERE id=$1 AND deleted_at IS NULL`, [sub])).rows[0];
  if (!u) return c.body(null, 401);
  return c.json({ user_id: u.id, display_name: u.display_name });
});
app.patch("/auth/me", async (c) => {
  const t = bearer2(c);
  if (!t) return c.body(null, 401);
  let sub, cid;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    ({ sub, cid } = await verifyAccess2(t));
  } catch {
    return c.body(null, 401);
  }
  const cred = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!cred || cred.rowCount === 0) return c.body(null, 401);
  const body = await c.req.json().catch(() => null);
  const name = typeof body?.display_name === "string" ? body.display_name.trim() : null;
  if (!name || name.length < 1 || name.length > 80) return c.json({ type: "bad-display-name" }, 400);
  const r = await q(
    `UPDATE users SET display_name=$1, updated_at=now() WHERE id=$2 AND deleted_at IS NULL RETURNING display_name`,
    [name, sub]
  );
  if (r.rowCount === 0) return c.body(null, 401);
  return c.json({ display_name: r.rows[0].display_name });
});
app.get("/auth/me/export", async (c) => {
  const t = bearer2(c);
  if (!t) return c.body(null, 401);
  let sub, cid;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    ({ sub, cid } = await verifyAccess2(t));
  } catch {
    return c.body(null, 401);
  }
  const cred = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!cred || cred.rowCount === 0) return c.body(null, 401);
  const user = (await q(`SELECT id, display_name, created_at FROM users WHERE id=$1 AND deleted_at IS NULL`, [sub])).rows[0];
  if (!user) return c.body(null, 401);
  const identities = (await q(`SELECT provider, email_verified, created_at FROM user_identities WHERE user_id=$1 ORDER BY created_at`, [sub])).rows;
  const memberships = (await q(
    `SELECT m.family_id, f.name AS family_name, m.role, m.status, m.joined_at
       FROM memberships m JOIN families f ON f.id=m.family_id WHERE m.user_id=$1 ORDER BY m.created_at`,
    [sub]
  )).rows;
  const credentials = (await q(
    `SELECT kind, scopes, label, last_used_at, created_at FROM credentials WHERE user_id=$1 AND revoked_at IS NULL ORDER BY created_at`,
    [sub]
  )).rows;
  return c.json({ exported_at: (/* @__PURE__ */ new Date()).toISOString(), user, identities, memberships, credentials });
});
app.get("/auth/me/credentials", async (c) => {
  const t = bearer2(c);
  if (!t) return c.body(null, 401);
  let sub, cid;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    ({ sub, cid } = await verifyAccess2(t));
  } catch {
    return c.body(null, 401);
  }
  const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!self || self.rowCount === 0) return c.body(null, 401);
  const rows = (await q(
    `SELECT id, kind, label, scopes, family_scope, last_used_at, last_used_ip, created_at
       FROM credentials WHERE user_id=$1 AND revoked_at IS NULL ORDER BY last_used_at DESC NULLS LAST, created_at DESC`,
    [sub]
  )).rows;
  return c.json({ credentials: rows.map((r) => ({ ...r, current: r.id === cid })) });
});
app.delete("/auth/me/credentials/:id", async (c) => {
  const t = bearer2(c);
  if (!t) return c.body(null, 401);
  let sub, cid;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    ({ sub, cid } = await verifyAccess2(t));
  } catch {
    return c.body(null, 401);
  }
  const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!self || self.rowCount === 0) return c.body(null, 401);
  const target = c.req.param("id");
  const r = await q(`UPDATE credentials SET revoked_at=now() WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL RETURNING 1`, [target, sub]);
  if (r.rowCount === 0) return c.body(null, 404);
  (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("credential.revoke", { actorUserId: sub, detail: { credential_id: target } });
  return c.body(null, 204);
});
app.delete("/auth/me", async (c) => {
  const t = bearer2(c);
  if (!t) return c.body(null, 401);
  let sub, cid;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    ({ sub, cid } = await verifyAccess2(t));
  } catch {
    return c.body(null, 401);
  }
  const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!self || self.rowCount === 0) return c.body(null, 401);
  const blocked = await q(
    `SELECT m.family_id, f.name FROM memberships m JOIN families f ON f.id=m.family_id
      WHERE m.user_id=$1 AND m.role='owner' AND m.status='active'
        AND (SELECT count(*) FROM memberships o WHERE o.family_id=m.family_id AND o.role='owner' AND o.status='active' AND o.user_id<>$1)=0
        AND (SELECT count(*) FROM memberships x WHERE x.family_id=m.family_id AND x.status='active' AND x.user_id<>$1)>0`,
    [sub]
  );
  if (blocked.rowCount && blocked.rowCount > 0)
    return c.json({ type: "transfer-required", families: blocked.rows }, 409);
  await q(`UPDATE users SET deleted_at=now() WHERE id=$1 AND deleted_at IS NULL`, [sub]);
  await q(`UPDATE memberships SET status='removed', updated_at=now() WHERE user_id=$1 AND status<>'removed'`, [sub]);
  await q(`UPDATE credentials SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`, [sub]);
  (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("account.soft_delete", { actorUserId: sub });
  return c.body(null, 204);
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
  return c.json({ familyId });
});
app.get("/.well-known/jwks.json", async (c) => {
  c.header("cache-control", "public, max-age=300");
  const { jwks: jwks2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
  return c.json(await jwks2());
});
var ANDROID_DEBUG_SHA256 = "15:A0:66:E7:BF:25:07:CB:0E:4D:5C:24:DE:FC:C9:75:06:EE:FF:19:B1:06:CB:7F:76:DF:C0:E9:23:00:87:6B";
app.get("/.well-known/assetlinks.json", (c) => {
  const fps = (process.env.ANDROID_CERT_SHA256 || ANDROID_DEBUG_SHA256).split(",").map((s) => s.trim()).filter(Boolean);
  c.header("cache-control", "public, max-age=300");
  return c.json([{
    relation: ["delegate_permission/common.handle_all_urls"],
    target: { namespace: "android_app", package_name: "com.sloopworks.dayfold", sha256_cert_fingerprints: fps }
  }]);
});
app.get("/.well-known/apple-app-site-association", (c) => {
  const appID = process.env.APPLE_APP_ID || "TEAMID.com.sloopworks.dayfold";
  c.header("content-type", "application/json");
  c.header("cache-control", "public, max-age=300");
  return c.json({ applinks: { apps: [], details: [{ appID, paths: ["/device", "/device?*"] }] } });
});
app.get("/device", (c) => {
  const code = (c.req.query("user_code") || "").replace(/[^A-Za-z0-9-]/g, "").slice(0, 9);
  c.header("content-type", "text/html; charset=utf-8");
  return c.html(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Approve a device \xB7 Dayfold</title>
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
  const fid = c.req.param("fid"), id3 = c.req.param("id");
  {
    const e = idError(id3);
    if (e) return c.json(e, 422);
  }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!await requireScope(a.cred.id, "content", "write")) return c.json({ type: "forbidden" }, 403);
  {
    const cur = await q(`SELECT visibility, audience FROM briefing_cards WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [fid, id3]);
    if (cur.rowCount && !cardVisible(cur.rows[0], { userId: a.userId, legacy: a.legacy })) return c.body(null, 404);
  }
  const raw = await c.req.json().catch(() => null);
  if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
  if (raw.visibility !== void 0 && raw.visibility !== "family" && raw.visibility !== "restricted")
    return c.json({ type: "validation", issues: [{ path: ["visibility"], message: "family|restricted" }] }, 422);
  const visibility = raw.visibility === "restricted" ? "restricted" : "family";
  let audience;
  if (visibility === "restricted") {
    if (raw.audience !== void 0 && (!Array.isArray(raw.audience) || raw.audience.some((x) => typeof x !== "string")))
      return c.json({ type: "validation", issues: [{ path: ["audience"], message: "string[] of user ids" }] }, 422);
    audience = Array.isArray(raw.audience) ? raw.audience : [];
  }
  const { visibility: _v, audience: _a, ...rest } = raw;
  let body = stripServerManaged(rest);
  body = stampProvenance(body, a.cred.id);
  const parsed = BriefingCardSchema.safeParse({ ...body, id: id3 });
  if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
  const cross = crossValidateCard(parsed.data);
  if (cross.length) return c.json({ type: "validation", issues: cross }, 422);
  const media = parsed.data.media;
  const mediaIssues = validateCardMedia(media);
  if (mediaIssues.length) return c.json({ type: "validation", issues: mediaIssues }, 422);
  if (media?.accentColor) media.accentColor = normalizedAccent(media.accentColor);
  return c.json(await upsertCard(fid, id3, { ...parsed.data, visibility, audience }), 200);
});
app.get("/families/:fid/cards", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!await requireScope(a.cred.id, "content", "read")) return c.json({ type: "forbidden" }, 403);
  return c.json(await listCards(fid, { userId: a.userId, legacy: a.legacy }));
});
app.get("/families/:fid/members", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const rows = await q(
    `SELECT m.user_id AS uid, u.display_name, m.role, m.status, m.joined_at
       FROM memberships m JOIN users u ON u.id = m.user_id
      WHERE m.family_id = $1 AND m.status = 'active'
      ORDER BY (m.role = 'owner') DESC, m.joined_at`,
    [fid]
  );
  return c.json({ members: rows.rows });
});
app.delete("/families/:fid/cards/:id", async (c) => {
  const fid = c.req.param("fid"), id3 = c.req.param("id");
  {
    const e = idError(id3);
    if (e) return c.json(e, 422);
  }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!await requireScope(a.cred.id, "content", "write")) return c.json({ type: "forbidden" }, 403);
  return c.body(null, await softDeleteCard(fid, id3) ? 204 : 404);
});
var RESOURCE_ID = /^[A-Za-z0-9_-]{1,128}$/;
var idError = (id3) => RESOURCE_ID.test(id3) ? null : { type: "validation", issues: [{ path: ["id"], message: "id must match [A-Za-z0-9_-]{1,128}" }] };
app.get("/families/:fid/hubs", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const grants = await resolveGrants(a.cred.id);
  const hubGrantIds = grantedHubIds(grants, "read");
  if (hubGrantIds !== null && hubGrantIds.length === 0) return c.json({ type: "forbidden" }, 403);
  return c.json(await listHubs(fid, { userId: a.userId, legacy: a.legacy }, hubGrantIds));
});
app.get("/families/:fid/hubs/:id", async (c) => {
  const fid = c.req.param("fid"), id3 = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!await requireScope(a.cred.id, `hub:${id3}`, "read")) return c.body(null, 404);
  const hub = await getHub(fid, id3);
  const caller = { userId: a.userId, legacy: a.legacy };
  if (!hub) return c.body(null, 404);
  const allow = await allowListFor(fid, id3);
  if (!hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
  return c.json(hub);
});
app.get("/families/:fid/hubs/:id/tree", async (c) => {
  const fid = c.req.param("fid"), id3 = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!await requireScope(a.cred.id, `hub:${id3}`, "read")) return c.body(null, 404);
  const hub = await getHub(fid, id3);
  const caller = { userId: a.userId, legacy: a.legacy };
  if (!hub) return c.body(null, 404);
  const allow = await allowListFor(fid, id3);
  if (!hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
  return c.json(await getHubTree(fid, id3));
});
app.get("/families/:fid/hubs/:id/audience", async (c) => {
  const fid = c.req.param("fid"), id3 = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!await requireScope(a.cred.id, `hub:${id3}`, "read")) return c.body(null, 404);
  const hub = await getHub(fid, id3);
  const caller = { userId: a.userId, legacy: a.legacy };
  if (!hub) return c.body(null, 404);
  const allow = await allowListFor(fid, id3);
  if (!hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
  return c.json({ visibility: hub.visibility, members: await hubAudience(fid, id3) });
});
app.put("/families/:fid/hubs/:id", async (c) => {
  const fid = c.req.param("fid"), id3 = c.req.param("id");
  {
    const e = idError(id3);
    if (e) return c.json(e, 422);
  }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!await requireScope(a.cred.id, `hub:${id3}`, "write")) return c.json({ type: "forbidden" }, 403);
  const raw = await c.req.json().catch(() => null);
  if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
  if (raw.visibility !== void 0 && raw.visibility !== "family" && raw.visibility !== "restricted")
    return c.json({ type: "validation", issues: [{ path: ["visibility"], message: "family|restricted" }] }, 422);
  const visibility = raw.visibility === "restricted" ? "restricted" : "family";
  let audience;
  if (visibility === "restricted") {
    if (raw.audience !== void 0 && (!Array.isArray(raw.audience) || raw.audience.some((x) => typeof x !== "string")))
      return c.json({ type: "validation", issues: [{ path: ["audience"], message: "string[] of user ids" }] }, 422);
    audience = Array.isArray(raw.audience) ? raw.audience : [];
  }
  const { visibility: _v, audience: _a, ...rest } = raw;
  const parsed = HubSchema.safeParse({ ...rest, id: id3 });
  if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
  const hubMediaIssues = validateHubMedia(parsed.data.media);
  if (hubMediaIssues.length) return c.json({ type: "validation", issues: hubMediaIssues }, 422);
  {
    const m = parsed.data.media;
    if (m?.accentColor) m.accentColor = normalizedAccent(m.accentColor);
  }
  const caller = { userId: a.userId, legacy: a.legacy };
  const existing = await getHub(fid, id3);
  if (existing) {
    const allow = await allowListFor(fid, id3);
    const permitted = () => !!caller.userId && allow.has(caller.userId);
    if (!hubVisible(existing, caller, permitted)) return c.body(null, 404);
    if (!caller.legacy && existing.created_by && existing.created_by !== caller.userId && !permitted())
      return c.json({ type: "forbidden" }, 403);
  }
  return c.json(await upsertHub(fid, id3, parsed.data, caller, visibility, audience), 200);
});
app.post("/families/:fid/hubs/:id/archive", async (c) => {
  const fid = c.req.param("fid"), id3 = c.req.param("id");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!await requireScope(a.cred.id, `hub:${id3}`, "write")) return c.json({ type: "forbidden" }, 403);
  return c.body(null, await archiveHub(fid, id3) ? 204 : 404);
});
app.delete("/families/:fid/hubs/:id", async (c) => {
  const fid = c.req.param("fid"), id3 = c.req.param("id");
  {
    const e = idError(id3);
    if (e) return c.json(e, 422);
  }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!await requireScope(a.cred.id, `hub:${id3}`, "write")) return c.json({ type: "forbidden" }, 403);
  return c.body(null, await softDeleteHub(fid, id3) ? 204 : 404);
});
app.put("/families/:fid/sections/:id", async (c) => {
  const fid = c.req.param("fid"), id3 = c.req.param("id");
  {
    const e = idError(id3);
    if (e) return c.json(e, 422);
  }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const raw = await c.req.json().catch(() => null);
  if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
  const hubId = typeof raw.hubId === "string" ? raw.hubId : null;
  if (!hubId) return c.json({ type: "validation", issues: [{ path: ["hubId"], message: "required" }] }, 422);
  const gate = await hubWriteGate(fid, hubId, { userId: a.userId, legacy: a.legacy, cred: a.cred });
  if (gate === "invisible") return c.body(null, 404);
  if (gate === "denied") return c.json({ type: "forbidden" }, 403);
  if (gate === "absent") return c.json({ type: "conflict", detail: "parent hub missing or deleted" }, 409);
  const { hubId: _h, ...rest } = raw;
  const parsed = SectionSchema.safeParse({ ...rest, id: id3 });
  if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
  const ifMatch = c.req.header("if-match");
  if (ifMatch) {
    const cur = await q(`SELECT version FROM sections WHERE family_id=$1 AND id=$2 AND deleted_at IS NULL`, [fid, id3]);
    if (ifMatchFails(ifMatch, cur.rowCount ? Number(cur.rows[0].version) : null)) return c.body(null, 412);
  }
  const row = await upsertSection(fid, id3, hubId, parsed.data);
  return row ? c.json(row, 200) : c.json({ type: "conflict", detail: "parent hub missing or deleted" }, 409);
});
app.put("/families/:fid/blocks/:id", async (c) => {
  const fid = c.req.param("fid"), id3 = c.req.param("id");
  {
    const e = idError(id3);
    if (e) return c.json(e, 422);
  }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const raw = await c.req.json().catch(() => null);
  if (!raw || typeof raw !== "object") return c.json({ type: "bad-json" }, 400);
  const sectionId = typeof raw.sectionId === "string" ? raw.sectionId : null;
  if (!sectionId) return c.json({ type: "validation", issues: [{ path: ["sectionId"], message: "required" }] }, 422);
  const caller = { userId: a.userId, legacy: a.legacy, cred: a.cred };
  const hubId = await liveHubOfSection(fid, sectionId);
  if (!hubId) return c.json({ type: "conflict", detail: "parent section missing or deleted" }, 409);
  const gate = await hubWriteGate(fid, hubId, caller);
  if (gate === "invisible") return c.body(null, 404);
  if (gate === "denied") return c.json({ type: "forbidden" }, 403);
  if (gate === "absent") return c.json({ type: "conflict", detail: "parent section missing or deleted" }, 409);
  const { sectionId: _s, ...rest } = raw;
  const body = stampProvenance(rest, a.cred.id);
  const parsed = BlockSchema.safeParse({ ...body, id: id3 });
  if (!parsed.success) return c.json({ type: "validation", issues: parsed.error.issues }, 422);
  const payloadIssues = blockPayloadIssues(parsed.data);
  if (payloadIssues.length) return c.json({ type: "validation", issues: payloadIssues }, 422);
  const blockMediaIssues = validateBlockPayloadMedia(parsed.data.type, parsed.data.payload);
  if (blockMediaIssues.length) return c.json({ type: "validation", issues: blockMediaIssues }, 422);
  {
    const p = parsed.data.payload;
    if (p?.accentColor) p.accentColor = normalizedAccent(p.accentColor);
  }
  const opId = c.req.header("idempotency-key");
  if (opId) {
    const prior = await findOp(fid, opId);
    if (prior) {
      const existing = prior.result_ref ? await getBlock(fid, prior.result_ref) : null;
      return existing ? c.json(existing, 200) : c.body(null, 410);
    }
  }
  const member = isMemberWrite(a);
  const st = await blockState(fid, id3);
  if (member && st.deleted) return c.body(null, 410);
  if (ifMatchFails(c.req.header("if-match"), st.deleted ? null : st.version)) return c.body(null, 412);
  const row = await upsertBlock(fid, id3, sectionId, parsed.data, { allowResurrect: !member, createdBy: a.userId });
  if (!row) {
    return member && st.exists ? c.body(null, 410) : c.json({ type: "conflict", detail: "parent section missing or deleted" }, 409);
  }
  if (opId) await recordOp(fid, opId, "block", id3, Number(row.version));
  return c.json(row, 200);
});
app.delete("/families/:fid/blocks/:id", async (c) => {
  const fid = c.req.param("fid"), id3 = c.req.param("id");
  {
    const e = idError(id3);
    if (e) return c.json(e, 422);
  }
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  const caller = { userId: a.userId, legacy: a.legacy };
  const opId = c.req.header("idempotency-key");
  if (opId && await findOp(fid, opId)) return c.body(null, 204);
  const blk = await blockForDelete(fid, id3);
  if (!blk) return c.body(null, 404);
  const hubId = await liveHubOfSection(fid, blk.section_id);
  if (!hubId) return c.body(null, 404);
  const hub = await getHub(fid, hubId);
  if (!hub) return c.body(null, 404);
  const allow = await allowListFor(fid, hubId);
  if (!hubVisible(hub, caller, () => !!caller.userId && allow.has(caller.userId))) return c.body(null, 404);
  if (!await requireScope(a.cred.id, "content", "delete")) return c.json({ type: "forbidden" }, 403);
  if (isMemberWrite(a) && blk.created_by !== caller.userId) return c.json({ type: "forbidden" }, 403);
  if (blk.deleted) {
    if (opId) await recordOp(fid, opId, "block", id3, null);
    return c.body(null, 204);
  }
  const ok = await softDeleteBlock(fid, id3);
  if (opId) await recordOp(fid, opId, "block", id3, null);
  return c.body(null, ok ? 204 : 404);
});
app.post("/device/authorize", async (c) => {
  const { clientIp: clientIp2, hit: hit2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
  const ip = clientIp2(c);
  const rl = await hit2(`ip:authorize:${ip}`, 600, 10);
  if (!rl.ok) return c.body(null, 429);
  const body = await c.req.json().catch(() => ({}));
  const { createAuthorization: createAuthorization2 } = await Promise.resolve().then(() => (init_device(), device_exports));
  const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
  const { device_code, user_code } = await createAuthorization2(body?.client ?? "dayfold-cli", ip, c.req.header("user-agent") ?? null);
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
  const { redeem: redeem3 } = await Promise.resolve().then(() => (init_device(), device_exports));
  const { mintAccess: mintAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
  const { issueRefresh: issueRefresh2 } = await Promise.resolve().then(() => (init_refresh(), refresh_exports));
  const out = await redeem3(body.device_code, mintAccess2, issueRefresh2);
  if ("tokens" in out) {
    const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
    await audit2("device.token.redeemed", { detail: { device_code: body.device_code } });
    return c.json(out.tokens, 200);
  }
  return c.json({ error: out.error }, 400);
});
app.get("/device/pending", async (c) => {
  const t = bearer2(c);
  if (!t) return c.body(null, 401);
  let sub, cid;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    ({ sub, cid } = await verifyAccess2(t));
  } catch {
    return c.body(null, 401);
  }
  const self = await q(`SELECT 1 FROM credentials WHERE id=$1 AND revoked_at IS NULL`, [cid]);
  if (!self || self.rowCount === 0) return c.body(null, 401);
  const { isLocked: isLocked2, recordFailure: recordFailure2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
  const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
  const lockKey = `account:approve:${sub}`;
  if (await isLocked2(lockKey)) {
    await audit2("device.lockout", { actorUserId: sub });
    return c.body(null, 429);
  }
  const userCode = c.req.query("user_code");
  if (!userCode) return c.json({ type: "bad-request" }, 400);
  const r = await q(
    `SELECT user_code, client, origin_ip, origin_ua, created_at, expires_at
       FROM device_authorizations WHERE user_code=$1 AND status='pending' AND expires_at > now()`,
    [userCode]
  );
  if (r.rowCount !== 1) {
    await recordFailure2(lockKey, 900, 5, 900);
    return c.json({ type: "not-found" }, 404);
  }
  const row = r.rows[0];
  const { classifyOrigin: classifyOrigin2 } = await Promise.resolve().then(() => (init_origin(), origin_exports));
  await audit2("device.lookup", { actorUserId: sub, detail: { user_code: userCode } });
  return c.json({
    user_code: row.user_code,
    client: row.client,
    origin_ip: row.origin_ip,
    origin_ua: row.origin_ua,
    origin_kind: classifyOrigin2(row.origin_ip),
    created_at: row.created_at,
    expires_at: row.expires_at
  });
});
async function ownerGate(c, fid) {
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return { status: a.status };
  if (a.role !== "owner") return { status: 403 };
  if (a.cred.kind !== "app") return { status: 403 };
  return { sub: a.userId };
}
app.post("/families/:fid/device/approve", async (c) => {
  const fid = c.req.param("fid");
  const g = await ownerGate(c, fid);
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
  const g = await ownerGate(c, fid);
  if ("status" in g) return c.body(null, g.status);
  const body = await c.req.json().catch(() => null);
  if (!body?.user_code) return c.json({ type: "bad-request" }, 400);
  const r = await q(`UPDATE device_authorizations SET status='denied' WHERE user_code=$1 AND status='pending' AND expires_at > now() RETURNING device_code`, [body.user_code]);
  const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
  if (r.rowCount === 1) await audit2("device.deny", { actorUserId: g.sub, familyId: fid });
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
  if (role !== "adult") return c.json({ type: "bad-role" }, 400);
  const maxUses = mode === "qr" ? 1 : Math.trunc(body?.max_uses ?? 1);
  if (maxUses < 1 || maxUses > 10) return c.json({ type: "bad-max-uses" }, 400);
  const { clientIp: clientIp2, hit: hit2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
  if (!(await hit2(`owner:mint:${g.sub}`, 600, 20)).ok) return c.body(null, 429);
  const caps = await q(
    `SELECT (SELECT count(*) FROM invites WHERE family_id=$1 AND status='active' AND expires_at>now()) AS inv,
            (SELECT count(*) FROM memberships WHERE family_id=$1 AND status='pending') AS pend`,
    [fid]
  );
  if (Number(caps.rows[0].inv) >= 10 || Number(caps.rows[0].pend) >= 20) return c.body(null, 429);
  const { createInvite: createInvite2 } = await Promise.resolve().then(() => (init_invites(), invites_exports));
  const { inviteId, token } = await createInvite2(fid, g.sub, mode, role, maxUses);
  const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
  await audit2("invite.mint", { actorUserId: g.sub, familyId: fid, detail: { mode, role, max_uses: maxUses } });
  const expires = await q(`SELECT expires_at FROM invites WHERE id=$1`, [inviteId]);
  c.header("cache-control", "no-store, no-transform");
  return c.json({ invite_id: inviteId, token, url: `${new URL(c.req.url).origin}/invite/${token}`, role, mode, expires_at: expires.rows[0].expires_at }, 201);
});
app.post("/invites:redeem", async (c) => {
  const t = bearer2(c);
  if (!t) return c.body(null, 401);
  let sub;
  try {
    const { verifyAccess: verifyAccess2 } = await Promise.resolve().then(() => (init_tokens(), tokens_exports));
    sub = (await verifyAccess2(t)).sub;
  } catch {
    return c.body(null, 401);
  }
  const { isLocked: isLocked2, recordFailure: recordFailure2, resetFailures: resetFailures2 } = await Promise.resolve().then(() => (init_ratelimit(), ratelimit_exports));
  const key = `account:redeem:${sub}`;
  if (await isLocked2(key)) return c.body(null, 429);
  const body = await c.req.json().catch(() => null);
  if (!body?.token) return c.json({ type: "bad-request" }, 400);
  const { redeem: redeem3 } = await Promise.resolve().then(() => (init_invites(), invites_exports));
  const out = await redeem3(body.token, sub);
  const { audit: audit2 } = await Promise.resolve().then(() => (init_audit(), audit_exports));
  if ("notfound" in out) {
    await recordFailure2(key, 900, 5, 900);
    return c.body(null, 404);
  }
  if ("capfull" in out) return c.body(null, 429);
  await resetFailures2(key);
  if ("conflict" in out) {
    if (out.conflict === "pending") return c.json({ status: "pending" }, 200);
    return c.json({ type: out.conflict === "active" ? "already-member" : "removed" }, 409);
  }
  const fam = await q(`SELECT name FROM families WHERE id=$1`, [out.family_id]);
  await audit2("invite.redeem", { actorUserId: sub, familyId: out.family_id });
  return c.json({ family_id: out.family_id, family_name: fam.rows[0]?.name, role: out.role, status: "pending" }, 200);
});
app.post("/families/:fid/members/*", async (c) => {
  const fid = c.req.param("fid");
  const pathname = new URL(c.req.url).pathname;
  const membersPrefix = `/families/${fid}/members/`;
  const seg = pathname.startsWith(membersPrefix) ? pathname.slice(membersPrefix.length) : "";
  const colonIdx = seg.lastIndexOf(":");
  if (colonIdx === -1) return c.body(null, 404);
  const uid = seg.slice(0, colonIdx);
  const action = seg.slice(colonIdx + 1);
  if (action !== "approve" && action !== "decline" && action !== "promote") return c.body(null, 404);
  const g = await ownerGate(c, fid);
  if ("status" in g) return c.body(null, g.status);
  if (action === "promote") {
    const r = await q(`UPDATE memberships SET role='owner', updated_at=now() WHERE user_id=$1 AND family_id=$2 AND status='active' AND role<>'owner' RETURNING 1`, [uid, fid]);
    if (r.rowCount === 1) {
      (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("member.promote", { actorUserId: g.sub, familyId: fid, detail: { uid } });
      return c.body(null, 204);
    }
    const cur = await q(`SELECT role, status FROM memberships WHERE user_id=$1 AND family_id=$2`, [uid, fid]);
    if (cur.rowCount === 0 || cur.rows[0].status !== "active") return c.body(null, 404);
    return c.body(null, 200);
  } else if (action === "approve") {
    const r = await q(`UPDATE memberships SET status='active', joined_at=now() WHERE user_id=$1 AND family_id=$2 AND status='pending' RETURNING 1`, [uid, fid]);
    if (r.rowCount === 1) {
      (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("invite.approve", { actorUserId: g.sub, familyId: fid, detail: { uid } });
      return c.body(null, 204);
    }
    const cur = await q(`SELECT status FROM memberships WHERE user_id=$1 AND family_id=$2`, [uid, fid]);
    if (cur.rowCount === 0) return c.body(null, 404);
    return c.body(null, cur.rows[0].status === "active" ? 200 : 409);
  } else {
    const r = await q(`UPDATE memberships SET status='removed' WHERE user_id=$1 AND family_id=$2 AND status='pending' RETURNING 1`, [uid, fid]);
    if (r.rowCount === 1) (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("invite.decline", { actorUserId: g.sub, familyId: fid, detail: { uid } });
    return c.body(null, r.rowCount === 1 ? 204 : 404);
  }
});
app.delete("/families/:fid/members/:uid", async (c) => {
  const fid = c.req.param("fid"), uid = c.req.param("uid");
  const g = await ownerGate(c, fid);
  if ("status" in g) return c.body(null, g.status);
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const owners = await client.query(
      `SELECT user_id FROM memberships WHERE family_id=$1 AND role='owner' AND status='active' FOR UPDATE`,
      [fid]
    );
    const targetIsOwner = owners.rows.some((r2) => r2.user_id === uid);
    if (targetIsOwner && (owners.rowCount ?? 0) < 2) {
      await client.query("ROLLBACK");
      return c.body(null, 409);
    }
    const r = await client.query(
      `UPDATE memberships SET status='removed' WHERE user_id=$1 AND family_id=$2 AND status IN ('active','pending') RETURNING 1`,
      [uid, fid]
    );
    await client.query("COMMIT");
    if ((r.rowCount ?? 0) !== 1) return c.body(null, 404);
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    client.release();
  }
  (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("member.remove", { actorUserId: g.sub, familyId: fid, detail: { uid } });
  return c.body(null, 204);
});
app.delete("/families/:fid/invites/:id", async (c) => {
  const fid = c.req.param("fid"), iid = c.req.param("id");
  const g = await ownerGate(c, fid);
  if ("status" in g) return c.body(null, g.status);
  const r = await q(`UPDATE invites SET status='revoked' WHERE id=$1 AND family_id=$2 AND status='active' RETURNING 1`, [iid, fid]);
  if (r.rowCount === 1) (await Promise.resolve().then(() => (init_audit(), audit_exports))).audit("invite.revoke", { actorUserId: g.sub, familyId: fid, detail: { invite_id: iid } });
  return c.body(null, 204);
});
app.get("/families/:fid/invites", async (c) => {
  const fid = c.req.param("fid");
  const g = await ownerGate(c, fid);
  if ("status" in g) return c.body(null, g.status);
  const invites = await q(`SELECT id, role, mode, max_uses, used_count, expires_at, created_at FROM invites WHERE family_id=$1 AND status='active' AND expires_at>now() ORDER BY created_at DESC`, [fid]);
  const pending = await q(
    `SELECT m.user_id AS uid, u.display_name, ui.provider, ui.provider_uid, ui.email_verified,
            m.role, m.invite_id, m.created_at AS requested_at
       FROM memberships m JOIN users u ON u.id=m.user_id
       LEFT JOIN LATERAL (
         SELECT provider, provider_uid, email_verified
           FROM user_identities WHERE user_id=m.user_id ORDER BY created_at, id LIMIT 1
       ) ui ON true
      WHERE m.family_id=$1 AND m.status='pending' ORDER BY m.created_at`,
    [fid]
  );
  return c.json({ invites: invites.rows, pending: pending.rows });
});
app.get("/families/:fid/sync", async (c) => {
  const fid = c.req.param("fid");
  const a = await authorizeTenant(c, fid);
  if ("status" in a) return c.body(null, a.status);
  if (!await requireScope(a.cred.id, "content", "read")) return c.json({ type: "forbidden" }, 403);
  const caller = { userId: a.userId, legacy: a.legacy };
  const raw = c.req.query("since") ?? "";
  let su = "", st = "", si = "";
  if (raw) {
    const parts = Buffer.from(raw, "base64").toString().split("|");
    if (parts.length === 2) {
      const validTs = parts[0] === "-infinity" || !Number.isNaN(Date.parse(parts[0]));
      if (!validTs) return problem(c, 400, "bad-cursor");
    } else if (parts.length === 3) {
      const validTs = !Number.isNaN(Date.parse(parts[0]));
      if (!validTs) return problem(c, 400, "bad-cursor");
      if (["card", "hub", "section", "block"].includes(parts[1])) {
        [su, st, si] = parts;
      }
    } else {
      return problem(c, 400, "bad-cursor");
    }
  }
  const rows = await syncContent(fid, su, st, si);
  const restrictedHubIds = Array.from(new Set([
    ...rows.filter((r) => r.type === "hub" && r.payload?.visibility === "restricted" && !r.deleted_at).map((r) => r.id),
    ...rows.filter((r) => (r.type === "section" || r.type === "block") && r.hub_visibility === "restricted").map((r) => r.hub_id)
  ].filter(Boolean)));
  const allowSets = /* @__PURE__ */ new Map();
  if (restrictedHubIds.length > 0) {
    const { q: dbq } = await Promise.resolve().then(() => (init_db(), db_exports));
    const rv = await dbq(
      `SELECT hub_id, user_id FROM resource_visibility WHERE family_id=$1 AND hub_id = ANY($2)`,
      [fid, restrictedHubIds]
    );
    for (const row of rv.rows) {
      if (!allowSets.has(row.hub_id)) allowSets.set(row.hub_id, /* @__PURE__ */ new Set());
      allowSets.get(row.hub_id).add(row.user_id);
    }
  }
  const changes = { cards: [], hubs: [], sections: [], blocks: [] };
  const tombstones = [];
  for (const r of rows) {
    let visible;
    if (r.deleted_at) {
      visible = false;
    } else if (r.type === "card") {
      visible = cardVisible(r.payload, caller);
    } else if (r.type === "hub") {
      visible = hubVisible(r.payload, caller, (hid) => !!(caller.userId && allowSets.get(hid)?.has(caller.userId)));
    } else {
      const parentHub = { id: r.hub_id, visibility: r.hub_visibility, created_by: r.hub_created_by };
      visible = hubVisible(parentHub, caller, (hid) => !!(caller.userId && allowSets.get(hid)?.has(caller.userId)));
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
  const next_cursor = last ? Buffer.from(`${last.updated_at}|${last.type}|${last.id}`).toString("base64") : raw;
  return c.json({ changes, tombstones, next_cursor, has_more: rows.length >= SYNC_LIMIT });
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
