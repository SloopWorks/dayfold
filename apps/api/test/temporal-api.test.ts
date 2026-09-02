import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { applyAllMigrations } from "./_migrations.ts";

process.env.DATABASE_URL ||= "postgres:///fad_test";
process.env.HOUSEHOLD_SECRET = "temporal-test-secret";
process.env.HOUSEHOLD_CREDENTIAL_ID = "temporal-cred";
const { pool, q } = await import("../src/db.ts");
const { app } = await import("../src/app.ts");

const AUTH = { authorization: "Bearer temporal-test-secret", "content-type": "application/json" };
const CAP = { ...AUTH, "x-dayfold-content-capability": "temporal-v1" };
const occurrenceId = "01K45ABCDEF0123456789GHJKM";
const temporal = { occurrences: [{
  id: occurrenceId, role: "event", label: "Show", start: "2026-08-28T21:00:00-07:00",
  zone: "America/Los_Angeles", status: "confirmed",
}] };
const provenance = { source: "cli", at: "2026-09-01T12:00:00Z" };

async function put(path: string, body: any, headers: Record<string, string> = AUTH) {
  return app.request(path, { method: "PUT", headers, body: JSON.stringify(body) });
}

beforeAll(async () => {
  await applyAllMigrations(q);
  await q(`INSERT INTO families(id,name) VALUES ('temporal-family','Temporal')`);
  await q(`INSERT INTO credentials(id,kind,family_scope,scopes)
    VALUES ('temporal-cred','cli','temporal-family','{content:read,content:write}')`);
  await q(`INSERT INTO credential_grants(credential_id,scope)
    VALUES ('temporal-cred','content:read'),('temporal-cred','content:write')`);
  await q(`INSERT INTO hubs(id,family_id,type,title,status,version)
    VALUES ('hub-1','temporal-family','party-event','Band','active',1)`);
  await q(`INSERT INTO sections(id,family_id,hub_id,title,ord,version)
    VALUES ('section-1','temporal-family','hub-1','Schedule',0,1)`);
});
afterAll(async () => { await pool.end(); });

describe("ADR 0067 API persistence and compatibility", () => {
  it("requires capability to introduce a fact_ref trigger", async () => {
    const response = await put("/families/temporal-family/cards/capability-card", {
      kind: "info", title: "Show", provenance, temporal,
      triggers: [{ when: { fact_ref: `temporal:${occurrenceId}` } }],
    });
    expect(response.status).toBe(422);
    expect((await response.json()).issues[0].code).toBe("temporal.capability-required");
  });

  it("round-trips card facts, preserves facts ACL and behavior for an old writer, and enforces If-Match", async () => {
    const first = await put("/families/temporal-family/cards/card-1", {
      kind: "info", title: "Show", provenance, visibility: "restricted", audience: ["adult-1"], temporal,
      triggers: [{ when: { fact_ref: `temporal:${occurrenceId}`, alert_offset: "-PT30M" } }],
    }, CAP);
    expect(first.status).toBe(200);
    const firstRow: any = await first.json();
    expect(firstRow.temporal).toEqual(temporal);
    expect(firstRow.visibility).toBe("restricted");

    const stale = await put("/families/temporal-family/cards/card-1", {
      kind: "info", title: "Stale", provenance, temporal,
    }, { ...CAP, "if-match": "999" });
    expect(stale.status).toBe(412);

    const legacy = await put("/families/temporal-family/cards/card-1", {
      kind: "info", title: "Edited by old writer", provenance,
      triggers: [{ when: { at: "2026-08-28T18:00:00-07:00" } }],
    }, { ...AUTH, "if-match": String(firstRow.version) });
    expect(legacy.status).toBe(200);
    const legacyRow: any = await legacy.json();
    expect(legacyRow.temporal).toEqual(temporal);
    expect(legacyRow.visibility).toBe("restricted");
    expect(legacyRow.audience).toEqual(["adult-1"]);
    expect(legacyRow.triggers).toContainEqual({ when: { fact_ref: `temporal:${occurrenceId}`, alert_offset: "-PT30M" } });

    const cleared = await put("/families/temporal-family/cards/card-1", {
      kind: "info", title: "No schedule", provenance, temporal: null, triggers: [],
    }, { ...CAP, "if-match": String(legacyRow.version) });
    expect(cleared.status).toBe(200);
    expect((await cleared.json()).temporal).toBeNull();
  });

  it("round-trips and preserve-on-omit facts for blocks through tree and sync projections", async () => {
    const first = await put("/families/temporal-family/blocks/block-1", {
      sectionId: "section-1", type: "markdown", body_md: "Show at nine", provenance, temporal,
    }, CAP);
    expect(first.status).toBe(200);
    const firstRow: any = await first.json();
    expect(firstRow.temporal).toEqual(temporal);

    const preserved = await put("/families/temporal-family/blocks/block-1", {
      sectionId: "section-1", type: "markdown", body_md: "Edited prose", provenance,
    }, { ...AUTH, "if-match": String(firstRow.version) });
    expect(preserved.status).toBe(200);
    expect((await preserved.json()).temporal).toEqual(temporal);

    const tree: any = await (await app.request("/families/temporal-family/hubs/hub-1/tree", { headers: AUTH })).json();
    expect(tree.blocks.find((block: any) => block.id === "block-1").temporal).toEqual(temporal);
    const sync: any = await (await app.request("/families/temporal-family/sync", { headers: AUTH })).json();
    expect(sync.changes.blocks.find((block: any) => block.id === "block-1").temporal).toEqual(temporal);
  });
});
