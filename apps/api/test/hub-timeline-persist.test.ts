// ADR 0045/0046 — authored Hub timeline persists end-to-end vs live Postgres.
// Regression for the "validated then silently dropped" gap: before 0016 + the
// upsertHub timeline column, a pushed timeline returned 200 but pull showed none.
// Mirrors the media-enrichment harness; applies through 0016 (the timeline column).
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
const J = { "content-type": "application/json", ...AUTH };
const putHub = (id: string, body: any) =>
  app.request(`/families/famA/hubs/${id}`, { method: "PUT", headers: J, body: JSON.stringify(body) });
const get = (path: string) => app.request(path, { headers: AUTH });

const TIMELINE = {
  tz: "America/Los_Angeles",
  stops: [
    { at: "2026-07-04T09:00:00-07:00", title: "Pack the car" },
    { at: "2026-07-04T12:00:00-07:00", title: "Lunch stop", attachments: [{ kind: "nav", label: "Harris Ranch" }] },
  ],
};

beforeAll(async () => {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  for (const m of ["0001_m0_init.sql", "0002_auth.sql", "0006_typed_content.sql", "0007_related.sql",
    "0008_credential_grants.sql", "0009_visibility.sql", "0010_hub_sync_fanout.sql",
    "0011_hub_visibility_fanout.sql", "0013_visual_enrichment.sql", "0016_hub_timeline.sql"])
    await q(readFileSync(resolve(here, "../migrations/" + m), "utf8"));
  await q(`INSERT INTO families(id,name) VALUES ('famA','A')`);
  await q(`INSERT INTO credentials(id,kind,family_scope,scopes) VALUES ('hcred','cli','famA','{content:read,content:write}')`);
  await q(`INSERT INTO credential_grants(credential_id,scope) VALUES ('hcred','content:read'),('hcred','content:write')`);
});
afterAll(async () => { await pool.end(); });

describe("ADR 0045 hub timeline persistence (vs live Postgres)", () => {
  it("timeline round-trips on PUT + via GET + sync", async () => {
    const r = await putHub("hub_trip", { type: "vacation", title: "Road trip", timeline: TIMELINE });
    expect(r.status).toBe(200);
    const row = await r.json();
    expect(typeof row.timeline).toBe("object");
    expect(row.timeline.tz).toBe("America/Los_Angeles");
    expect(row.timeline.stops).toHaveLength(2);
    expect(row.timeline.stops[1].attachments[0].kind).toBe("nav");

    const got = await (await get("/families/famA/hubs/hub_trip")).json();
    expect(got.timeline.stops[0].title).toBe("Pack the car");

    const sync = await (await get("/families/famA/sync")).json();
    const sh = sync.changes.hubs.find((h: any) => h.id === "hub_trip");
    expect(sh.timeline.tz).toBe("America/Los_Angeles");
  });

  it("re-push updates the timeline (ON CONFLICT DO UPDATE)", async () => {
    const updated = { tz: "UTC", stops: [{ at: "2026-07-05T00:00:00Z", title: "Depart" }] };
    const r = await putHub("hub_trip", { type: "vacation", title: "Road trip", timeline: updated });
    expect(r.status).toBe(200);
    const got = await (await get("/families/famA/hubs/hub_trip")).json();
    expect(got.timeline.tz).toBe("UTC");
    expect(got.timeline.stops).toHaveLength(1);
  });

  it("hub without a timeline → 200, timeline NULL (back-compat)", async () => {
    const r = await putHub("hub_plain", { type: "move", title: "House move" });
    expect(r.status).toBe(200);
    expect((await r.json()).timeline).toBeNull();
  });

  it("re-push WITHOUT timeline clears it (EXCLUDED, not COALESCE)", async () => {
    await putHub("hub_clearable", { type: "vacation", title: "x", timeline: TIMELINE });
    const r = await putHub("hub_clearable", { type: "vacation", title: "x" });
    expect(r.status).toBe(200);
    expect((await r.json()).timeline).toBeNull();
  });
});
