import { randomBytes } from "node:crypto";
import { pool, q } from "../db.ts";

const ALPHABET = "23456789CFGHJMPQRVWX"; // 20 unambiguous symbols
export function genUserCode(): string {
  const pick = () => ALPHABET[randomBytes(1)[0] % ALPHABET.length];
  const block = () => Array.from({ length: 4 }, pick).join("");
  return `${block()}-${block()}`;
}
export const genDeviceCode = (): string => randomBytes(32).toString("base64url");
const credId = () => "cred_" + randomBytes(9).toString("hex");

const EXPIRES_S = 600, INTERVAL_S = 5;

export async function createAuthorization(client: string | null, ip: string, ua: string | null) {
  const device_code = genDeviceCode();
  // [M1] collision-retry the pending-unique user_code up to 3x
  for (let attempt = 0; ; attempt++) {
    const user_code = genUserCode();
    try {
      await q(
        `INSERT INTO device_authorizations(device_code,user_code,client,origin_ip,origin_ua,interval_s,expires_at)
         VALUES ($1,$2,$3,$4,$5,$6, now() + ($7||' seconds')::interval)`,
        [device_code, user_code, client, ip, ua, INTERVAL_S, String(EXPIRES_S)],
      );
      return { device_code, user_code };
    } catch (e: any) {
      if (e?.code === "23505" && attempt < 3) continue; // unique violation -> retry
      throw e;
    }
  }
}

// Returns one of: {pending:'authorization_pending'|'slow_down'} | {error} | {tokens:{access,refresh,...}}
export async function redeem(device_code: string, mintAccess: (a:{sub:string;cid:string})=>Promise<string>, issueRefresh:(cid:string,client:any)=>Promise<string>) {
  const row = (await q(`SELECT * FROM device_authorizations WHERE device_code=$1`, [device_code])).rows[0];
  if (!row) return { error: "expired_token" };
  const expired = new Date(row.expires_at).getTime() < Date.now();
  if (expired) {
    if (row.status === "pending") await q(`UPDATE device_authorizations SET status='expired' WHERE device_code=$1 AND status='pending'`, [device_code]); // [M2]
    return { error: "expired_token" };
  }
  if (row.status === "denied") return { error: "access_denied" };
  if (row.status === "consumed") return { error: "expired_token" };
  if (row.status === "pending") {
    // [I5/I6] single conditional throttle UPDATE; fixed interval, no ratchet
    const upd = await q(
      `UPDATE device_authorizations SET last_polled_at=now()
       WHERE device_code=$1 AND status='pending'
         AND (last_polled_at IS NULL OR last_polled_at < now() - make_interval(secs => interval_s)) RETURNING 1`,
      [device_code],
    );
    return { error: (upd.rowCount ?? 0) === 1 ? "authorization_pending" : "slow_down" };
  }
  // approved -> lazy mint in ONE txn on a SINGLE client [C-1/I3/I4]
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const cas = await client.query(
      `UPDATE device_authorizations SET status='consumed' WHERE device_code=$1 AND status='approved'
       RETURNING user_id, family_id, origin_ua`, [device_code]);
    if (cas.rowCount !== 1) { await client.query("COMMIT"); return { error: "expired_token" }; }
    const { user_id, family_id, origin_ua } = cas.rows[0];
    const cid = credId();
    await client.query(
      `INSERT INTO credentials(id,user_id,family_scope,kind,scopes,label)
       VALUES ($1,$2,$3,'cli','{content:read,content:write}', 'dayfold-cli '||left(coalesce($4,''),64))`,
      [cid, user_id, family_id, origin_ua],
    );
    const refresh = await issueRefresh(cid, client);
    await client.query(`UPDATE device_authorizations SET credential_id=$1 WHERE device_code=$2`, [cid, device_code]);
    await client.query("COMMIT");
    const access = await mintAccess({ sub: user_id, cid });
    return { tokens: { access_token: access, refresh_token: refresh, token_type: "Bearer", expires_in: 300 } };
  } catch (e) { await client.query("ROLLBACK"); throw e; } finally { client.release(); }
}
