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

/** Columns ADDED by `ALTER TABLE … ADD COLUMN` across migrations → Map<table, Set<col>>.
 *  These are the post-CREATE evolutions (0006 type/payload, 0009 visibility/audience,
 *  0013 media, 0014 repair, …) — the columns a table CAN lack when later migrations were
 *  never applied. That is the exact shape of the #180 outage: briefing_cards existed (no
 *  TABLE drift) but lacked these columns, so every INSERT 500'd while the table-only check
 *  said "in sync". Comments are stripped first because they contain ';' (e.g. "D1); NULL"),
 *  which would otherwise truncate a multi-`ADD COLUMN` statement. Pure. */
export function expectedAddedColumns(migrationsDir) {
  const stmtRe = /ALTER TABLE\s+(?:IF EXISTS\s+)?([a-z_][a-z0-9_]*)\b([\s\S]*?);/gi;
  const colRe = /ADD COLUMN\s+(?:IF NOT EXISTS\s+)?([a-z_][a-z0-9_]*)/gi;
  const out = new Map();
  for (const f of readdirSync(migrationsDir).filter((f) => f.endsWith(".sql")).sort()) {
    const sql = readFileSync(resolve(migrationsDir, f), "utf8")
      .replace(/--[^\n]*/g, "").replace(/\/\*[\s\S]*?\*\//g, ""); // drop comments (may hold ';')
    for (const s of sql.matchAll(stmtRe)) {
      for (const c of s[2].matchAll(colRe)) {
        if (!out.has(s[1])) out.set(s[1], new Set());
        out.get(s[1]).add(c[1]);
      }
    }
  }
  return out;
}

/** Per-table columns the migrations ADD but the live DB lacks. [expected] is
 *  Map<table, Set<col>> (from expectedAddedColumns); [actual] is Map<table, Set<col>>
 *  (from information_schema.columns). Returns sorted [{ table, columns }]. Pure. */
export function missingColumns(expected, actual) {
  const out = [];
  for (const [table, cols] of expected) {
    const have = actual.get(table) ?? new Set();
    const miss = [...cols].filter((c) => !have.has(c)).sort();
    if (miss.length) out.push({ table, columns: miss });
  }
  return out.sort((a, b) => a.table.localeCompare(b.table));
}

// ── CLI runner (only when executed directly, not when imported by a test) ──
async function main() {
  const here = dirname(fileURLToPath(import.meta.url));
  const expected = expectedTables(resolve(here, "../migrations"));
  if (!process.env.DATABASE_URL) {
    console.error("schema-drift: set DATABASE_URL to the DB you want to check");
    process.exit(2);
  }
  const expectedCols = expectedAddedColumns(resolve(here, "../migrations"));
  const pg = (await import("pg")).default;
  const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  try {
    const tableRows = (await pool.query(`SELECT tablename FROM pg_tables WHERE schemaname = 'public'`)).rows;
    const missing = missingTables(expected, tableRows.map((r) => r.tablename));

    // Columns too — the #180 shape (table present, ALTER-added columns absent → INSERT 500s).
    const colRows = (await pool.query(
      `SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = 'public'`,
    )).rows;
    const actualCols = new Map();
    for (const r of colRows) {
      if (!actualCols.has(r.table_name)) actualCols.set(r.table_name, new Set());
      actualCols.get(r.table_name).add(r.column_name);
    }
    const colDrift = missingColumns(expectedCols, actualCols);

    if (missing.length === 0 && colDrift.length === 0) {
      console.log(`schema in sync — all ${expected.length} tables + their migration-added columns present`);
      process.exit(0);
    }
    if (missing.length) {
      console.error(`SCHEMA DRIFT — ${missing.length}/${expected.length} table(s) missing:`);
      for (const t of missing) console.error(`  - ${t}`);
    }
    if (colDrift.length) {
      console.error(`SCHEMA DRIFT — migration-added column(s) missing (table present but stale):`);
      for (const { table, columns } of colDrift) console.error(`  - ${table}: ${columns.join(", ")}`);
    }
    console.error("apply the pending migrations to this database.");
    process.exit(1);
  } finally {
    await pool.end();
  }
}

// `import.meta.url === pathToFileURL(argv[1])` → run only when invoked directly.
if (import.meta.url === `file://${process.argv[1]}`) await main();
