import { describe, it, expect, afterAll } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

// Drift guard for the migration set. Every other suite hardcodes its own subset
// of migrations/ in beforeAll — those lists are inconsistent and skip the newest
// ones (no suite applied 0010/0011), so a broken or forgotten migration can pass
// CI and only blow up at a manual prod apply. This suite reads the WHOLE dir and
// proves the full chain applies cleanly + yields the expected schema, so a new
// migration is covered automatically.
const here = dirname(fileURLToPath(import.meta.url));
process.env.DATABASE_URL ||= "postgres:///fad_test";
const { pool, q } = await import("../src/db.ts");

const migDir = resolve(here, "../migrations");
const sqlFiles = () => readdirSync(migDir).filter((f) => f.endsWith(".sql")).sort();

afterAll(async () => { await pool.end(); });

describe("migration set is complete and applies cleanly (drift guard)", () => {
  it("every migration in migrations/ applies in order on a fresh schema", async () => {
    const files = sqlFiles();
    expect(files.length).toBeGreaterThan(0);
    await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
    // a broken / out-of-order migration throws here and fails the suite
    for (const f of files) await q(readFileSync(resolve(migDir, f), "utf8"));

    const tables = (await q(`SELECT tablename FROM pg_tables WHERE schemaname = 'public'`))
      .rows.map((r: any) => r.tablename).sort();
    // the full expected schema — incl. the AUTH-epic (0002/0003/0008) + fanout
    // (0010/0011) tables that the per-suite hardcoded lists silently skipped, and
    // whose absence on prod broke sign-in + device-login.
    expect(tables).toEqual([
      "audit_log", "blocks", "briefing_cards", "credential_grants", "credentials",
      "device_authorizations", "families", "hubs", "invites", "memberships",
      "op_log", "places", "rate_limits", "refresh_tokens", "resource_visibility",
      "schema_migrations", "sections", "user_identities", "users",
    ]);
  });

  it("migration files are contiguously numbered from 0001 (no gap a hand-apply could skip)", () => {
    const nums = sqlFiles().map((f) => Number(f.slice(0, 4)));
    expect(nums).toEqual(nums.map((_, i) => i + 1));
  });
});
