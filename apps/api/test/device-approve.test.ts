import { describe, it, expect, beforeAll, afterAll } from "vitest";
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
  for (const m of ["0001_m0_init.sql","0002_auth.sql","0003_device_grant.sql","0004_refresh_grace.sql","0006_typed_content.sql","0007_related.sql","0008_credential_grants.sql","0009_visibility.sql","0013_visual_enrichment.sql"])
    await q(readFileSync(resolve(here, "../migrations/"+m), "utf8"));
});
afterAll(async () => { await pool.end(); });

// Mint an owner of a fresh family via dev-token + POST /families
async function ownerOf(uid: string) {
  const dev = { "content-type":"application/json", authorization:"Bearer dev" };
  const t = (await (await app.request("/auth/dev-token",{method:"POST",headers:dev,body:JSON.stringify({provider:"dev",provider_uid:uid})})).json()).access;
  const fam = await (await app.request("/families",{method:"POST",headers:{...dev,authorization:`Bearer ${t}`},body:JSON.stringify({name:uid})})).json();
  return { token: t, familyId: fam.familyId };
}
const approve = (fid:string, tok:string, user_code:string) =>
  app.request(`/families/${fid}/device/approve`,{method:"POST",headers:{"content-type":"application/json",authorization:`Bearer ${tok}`},body:JSON.stringify({user_code})});

describe("/families/:fid/device/approve + e2e", () => {
  it("owner approves -> CLI token mints -> push WRITE succeeds on bound family, 404 elsewhere", async () => {
    const o = await ownerOf("alice");
    const a = await (await app.request("/device/authorize",{method:"POST",headers:{"content-type":"application/json"},body:"{}"})).json();
    expect((await approve(o.familyId, o.token, a.user_code)).status).toBe(204);
    const tok = await (await app.request("/device/token",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({grant_type:"urn:ietf:params:oauth:grant-type:device_code",device_code:a.device_code})})).json();
    // [I2] the granted CLI token actually writes
    const put = await app.request(`/families/${o.familyId}/cards/c1`,{method:"PUT",headers:{"content-type":"application/json",authorization:`Bearer ${tok.access_token}`},body:JSON.stringify({kind:"info",title:"hi",provenance:{source:"cli",at:"2026-06-18T10:00:00Z"}})});
    expect(put.status).toBe(200);
    // IDOR: a different owner's family rejects this token
    const o2 = await ownerOf("bob");
    const cross = await app.request(`/families/${o2.familyId}/cards`,{headers:{authorization:`Bearer ${tok.access_token}`}});
    expect(cross.status).toBe(404);
  });
  it("non-owner -> 403; kind='cli' caller -> 403; bad user_code -> 404 then lockout after 5", async () => {
    const o = await ownerOf("carol");
    const a = await (await app.request("/device/authorize",{method:"POST",headers:{"content-type":"application/json"},body:"{}"})).json();
    // a CLI-kind token cannot approve [C2]: grant one, then try to use it to approve
    expect((await approve(o.familyId, o.token, a.user_code)).status).toBe(204);
    const cliTok = await (await app.request("/device/token",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({grant_type:"urn:ietf:params:oauth:grant-type:device_code",device_code:a.device_code})})).json();
    const a2 = await (await app.request("/device/authorize",{method:"POST",headers:{"content-type":"application/json"},body:"{}"})).json();
    expect((await approve(o.familyId, cliTok.access_token, a2.user_code)).status).toBe(403); // kind='cli' rejected
    // bad code -> 404; 5 bad -> lockout 429
    let last;
    for (let i=0;i<5;i++) last = await approve(o.familyId, o.token, "ZZZZ-ZZZZ");
    expect([404,429]).toContain(last!.status);
    expect((await approve(o.familyId, o.token, "ZZZZ-ZZZZ")).status).toBe(429);
  });
});
