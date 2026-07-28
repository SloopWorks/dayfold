import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const migDir = resolve(dirname(fileURLToPath(import.meta.url)), "../migrations");

// Applies every migration in migrations/, in order, to a fresh schema. Shared by
// every DB-backed suite so a new migration is exercised everywhere automatically
// (see migrations.test.ts's drift guard) instead of each suite hand-listing its
// own subset — those lists had drifted inconsistent and silently skipped newer
// migrations, the same failure class that caused the prod outage fixed by
// 0014_card_columns_repair.sql (briefing_cards was missing columns no suite's
// hardcoded list ever applied, so no test caught it before the manual prod apply).
export async function applyAllMigrations(q: (sql: string) => Promise<unknown>) {
  await q(`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`);
  const files = readdirSync(migDir).filter((f) => f.endsWith(".sql")).sort();
  for (const f of files) await q(readFileSync(resolve(migDir, f), "utf8"));
}
