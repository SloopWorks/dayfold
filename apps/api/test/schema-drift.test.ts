import { describe, it, expect } from "vitest";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { expectedTables, missingTables } from "../scripts/schema-drift.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const migDir = resolve(here, "../migrations");

// The full schema the migrations define (matches migrations.test.ts's drift guard).
const ALL = [
  "audit_log", "blocks", "briefing_cards", "credential_grants", "credentials",
  "device_authorizations", "families", "hubs", "invites", "memberships",
  "places", "rate_limits", "refresh_tokens", "resource_visibility", "sections",
  "user_identities", "users",
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
