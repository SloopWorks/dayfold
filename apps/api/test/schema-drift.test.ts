import { describe, it, expect } from "vitest";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { expectedTables, missingTables, expectedAddedColumns, missingColumns } from "../scripts/schema-drift.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const migDir = resolve(here, "../migrations");

// The full schema the migrations define (matches migrations.test.ts's drift guard).
const ALL = [
  "audit_log", "blocks", "briefing_cards", "credential_grants", "credentials",
  "device_authorizations", "families", "hubs", "invites", "memberships",
  "op_log", "places", "rate_limits", "refresh_tokens", "resource_visibility",
  "schema_migrations", "sections", "user_identities", "users",
];

describe("schema-drift detector", () => {
  it("parses every CREATE TABLE across migrations/ into the expected set", () => {
    expect(expectedTables(migDir)).toEqual(ALL);
  });

  it("reports nothing missing when the DB has the full set", () => {
    expect(missingTables(ALL, ALL)).toEqual([]);
    expect(missingTables(ALL, [...ALL, "extra_unmanaged"])).toEqual([]); // extra tables are not drift
  });

  it("reports exactly the tables the live DB lacks (the prod-outage case)", () => {
    // a DB that only ran 0001 (families/credentials/places/briefing_cards...) but
    // not the AUTH epic — the state that 500'd sign-in + device-login on prod.
    const onlyBase = ["families", "credentials", "places", "briefing_cards"];
    const missing = missingTables(ALL, onlyBase);
    expect(missing).toContain("user_identities"); // the firebase 500
    expect(missing).toContain("rate_limits");      // the device 500
    expect(missing).toContain("credential_grants");
    expect(missing).not.toContain("families");     // present → not flagged
  });
});

// The #180 outage was COLUMN drift, not table drift: briefing_cards existed (table check
// said "in sync") but lacked the ALTER-added columns, so every INSERT 500'd. These cover
// the column-aware extension.
describe("column-aware drift (table present, ALTER-added columns missing)", () => {
  const cols = expectedAddedColumns(migDir);

  it("parses ALTER … ADD COLUMN across migrations — incl. multi-add + a ';' inside a comment", () => {
    const bc = cols.get("briefing_cards");
    // 0006 adds type,payload,privacy,hub_ref in ONE statement whose `type` comment holds
    // "D1); NULL" — the ';' must NOT truncate the parse (comment-stripping guards this).
    for (const c of ["type", "payload", "privacy", "hub_ref"]) expect(bc?.has(c), c).toBe(true);
    for (const c of ["related", "related_kicker", "visibility", "audience", "media"]) expect(bc?.has(c), c).toBe(true);
  });

  it("flags exactly the migration-added columns a stale table lacks (the #180 shape)", () => {
    const actual = new Map([["briefing_cards", new Set(["id", "family_id", "kind", "title"])]]);
    const drift = missingColumns(cols, actual).find((d) => d.table === "briefing_cards");
    expect(drift?.columns).toContain("type");     // the INSERT-500 column
    expect(drift?.columns).toContain("media");     // ADR 0036
    expect(drift?.columns).not.toContain("kind");  // a base col that's present → not flagged
  });

  it("reports no column drift when every migration-added column is present", () => {
    const actual = new Map([...cols].map(([t, s]) => [t, new Set(s)]));
    expect(missingColumns(cols, actual)).toEqual([]);
  });
});
