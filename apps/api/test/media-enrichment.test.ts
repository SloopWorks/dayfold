// ADR 0036 — visual-enrichment media end-to-end vs live Postgres. Mirrors the
// typed-content harness; applies through 0012 (the media column + CHECK).
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.HOUSEHOLD_SECRET = "test-secret-123";
process.env.HOUSEHOLD_CREDENTIAL_ID = "hcred";

const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");

const AUTH = { authorization: "Bearer test-secret-123" };
const prov = { source: "claude", at: "2026-06-26T10:00:00Z" };
const J = { "content-type": "application/json", ...AUTH };

const putCard = (id: string, body: any) =>
  app.request(`/families/famA/cards/${id}`, { method: "PUT", headers: J, body: JSON.stringify(body) });
const putHub = (id: string, body: any) =>
  app.request(`/families/famA/hubs/${id}`, { method: "PUT", headers: J, body: JSON.stringify(body) });
const get = (path: string) => app.request(path, { headers: AUTH });

const HERO = "https://upload.wikimedia.org/wikipedia/commons/0/0c/Logo.png";

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql", "0002_auth.sql", "0006_typed_content.sql", "0007_related.sql",
    "0008_credential_grants.sql", "0009_visibility.sql", "0010_hub_sync_fanout.sql",
    "0011_hub_visibility_fanout.sql", "0013_visual_enrichment.sql"])
    await q(readFileSync(resolve(here, "../migrations/" + m), "utf8"));
  await q(`INSERT INTO families(id,name) VALUES ('famA','A')`);
  await q(`INSERT INTO credentials(id,kind,family_scope,scopes) VALUES ('hcred','cli','famA','{content:read,content:write}')`);
  await q(`INSERT INTO credential_grants(credential_id,scope) VALUES ('hcred','content:read'),('hcred','content:write')`);
});
afterAll(async () => { await pool.end(); });

describe("ADR 0036 media enrichment (vs live Postgres)", () => {
  it("hub media round-trips on PUT + via GET + sync; accent lowercased on write", async () => {
    const media = { heroUrl: HERO, thumbnailUrl: HERO, heroFit: "contain", imageAlt: "University logo", icon: "school", accentColor: "#2C3E73" };
    const r = await putHub("hub_college", { type: "starting-college", title: "Maya starts college", media });
    expect(r.status).toBe(200);
    const row = await r.json();
    expect(typeof row.media).toBe("object");
    expect(row.media.heroUrl).toBe(HERO);
    expect(row.media.icon).toBe("school");
    expect(row.media.accentColor).toBe("#2c3e73");      // lowercased on write

    const got = await (await get("/families/famA/hubs/hub_college")).json();
    expect(got.media.heroFit).toBe("contain");

    const sync = await (await get("/families/famA/sync")).json();
    const sh = sync.changes.hubs.find((h: any) => h.id === "hub_college");
    expect(sh.media.imageAlt).toBe("University logo");
  });

  it("card media (icon + accent + thumb) round-trips + sync carries it", async () => {
    const media = { icon: "travel", accentColor: "#1C6E8C", thumbnailUrl: HERO, imageAlt: "trip", imageFit: "cover" };
    const r = await putCard("card_trip", { kind: "action", title: "Lisbon check-in", provenance: prov, media });
    expect(r.status).toBe(200);
    const row = await r.json();
    expect(row.media.accentColor).toBe("#1c6e8c");
    const sync = await (await get("/families/famA/sync")).json();
    const sc = sync.changes.cards.find((c: any) => c.id === "card_trip");
    expect(sc.media.icon).toBe("travel");
    expect(sc.media.thumbnailUrl).toBe(HERO);
  });

  it("unenriched hub/card (no media) still → 200, media NULL (back-compat)", async () => {
    const r = await putHub("hub_plain", { type: "move", title: "House move" });
    expect(r.status).toBe(200);
    expect((await r.json()).media).toBeNull();
    const rc = await putCard("card_plain", { kind: "info", title: "x", provenance: prov });
    expect((await rc.json()).media).toBeNull();
  });

  it("non-allowlisted image host → 422", async () => {
    const r = await putHub("hub_bad_host", { type: "vacation", title: "x", media: { heroUrl: "https://evil.com/x.png" } });
    expect(r.status).toBe(422);
    const body = await r.json();
    expect(body.type).toBe("validation");
    expect(body.issues[0].path).toEqual(["media", "heroUrl"]);
  });

  it("userinfo-smuggled + http + svg image hosts → 422", async () => {
    for (const heroUrl of [
      "https://upload.wikimedia.org@evil.com/x.png",
      "http://upload.wikimedia.org/x.png",
      "https://upload.wikimedia.org/logo.svg",
    ]) {
      const r = await putHub("hub_bad_" + heroUrl.length, { type: "vacation", title: "x", media: { heroUrl } });
      expect(r.status, heroUrl).toBe(422);
    }
  });

  it("unknown icon → 422", async () => {
    const r = await putCard("card_bad_icon", { kind: "info", title: "x", provenance: prov, media: { icon: "medical_services" } });
    expect(r.status).toBe(422);
    expect((await r.json()).issues[0].path).toEqual(["media", "icon"]);
  });

  it("malformed accent hex → 422 (zod schema regex)", async () => {
    const r = await putCard("card_bad_hex", { kind: "info", title: "x", provenance: prov, media: { accentColor: "red" } });
    expect(r.status).toBe(422);
  });

  // NOTE: block-payload media (link/document thumbnailUrl, contact avatarUrl+accent)
  // is enforced by the block route's validateBlockPayloadMedia, but structured block
  // PUTs can't round-trip through the API until ADR 0035 fixes the generated
  // Block.payload oneOf (today it's [z.any()×7] → any structured payload fails
  // BlockSchema; live content is body_md-only). Block media is exercised at the unit
  // level (media-validation.test.ts) + the CLI (Validate) + the client render, where
  // it is meaningful now. Re-enable an API block round-trip once 0035 lands.

  it("media must be an object (jsonb CHECK + schema) — array rejected", async () => {
    const r = await putHub("hub_arr", { type: "vacation", title: "x", media: [1, 2] });
    expect(r.status).toBe(422);
  });
});
