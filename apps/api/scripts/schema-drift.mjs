// Schema-drift detector: compare the tables a target DB actually has against the
// tables the migrations/ define, and report what's missing. Built after a prod
// outage where sign-in + device-login 500'd because the AUTH-epic migrations
// (0002/0003/0008) were never applied to prod — there was no one-shot way to see
// the gap. Run this against any DB to diagnose deploy drift in seconds.
//
// Usage: DATABASE_URL=postgres://... node scripts/schema-drift.mjs
//   exit 0 = in sync · exit 1 = drift (missing tables) · exit 2 = usage/conn error
//
// Read-only: it only SELECTs from pg_tables; it never mutates the target DB.
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

/** The set of tables the migrations define — parsed from every `CREATE TABLE`
 *  (with or without IF NOT EXISTS) across migrations/*.sql. Pure + sorted. */
export function expectedTables(migrationsDir) {
  const re = /CREATE TABLE\s+(?:IF NOT EXISTS\s+)?([a-z_][a-z0-9_]*)/gi;
  const out = new Set();
  for (const f of readdirSync(migrationsDir).filter((f) => f.endsWith(".sql")).sort()) {
    const sql = readFileSync(resolve(migrationsDir, f), "utf8");
    for (const m of sql.matchAll(re)) out.add(m[1]);
  }
  return [...out].sort();
}

/** Tables expected by the migrations but absent from the live DB. Pure. */
export function missingTables(expected, actual) {
  const have = new Set(actual);
  return expected.filter((t) => !have.has(t));
}

// ── CLI runner (only when executed directly, not when imported by a test) ──
async function main() {
  const here = dirname(fileURLToPath(import.meta.url));
  const expected = expectedTables(resolve(here, "../migrations"));
  if (!process.env.DATABASE_URL) {
    console.error("schema-drift: set DATABASE_URL to the DB you want to check");
    process.exit(2);
  }
  const pg = (await import("pg")).default;
  const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  try {
    const { rows } = await pool.query(`SELECT tablename FROM pg_tables WHERE schemaname = 'public'`);
    const actual = rows.map((r) => r.tablename);
    const missing = missingTables(expected, actual);
    if (missing.length === 0) {
      console.log(`schema in sync — all ${expected.length} migration-defined tables present`);
      process.exit(0);
    }
    console.error(`SCHEMA DRIFT — ${missing.length}/${expected.length} table(s) missing:`);
    for (const t of missing) console.error(`  - ${t}`);
    console.error("apply the pending migrations to this database.");
    process.exit(1);
  } finally {
    await pool.end();
  }
}

// `import.meta.url === pathToFileURL(argv[1])` → run only when invoked directly.
if (import.meta.url === `file://${process.argv[1]}`) await main();
