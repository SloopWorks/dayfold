import { defineConfig } from "vitest/config";

// All test suites share the same Postgres instance (fad_test) and each calls
// DROP SCHEMA + recreate in beforeAll. Running suites in parallel causes
// concurrent DDL races (pg_type conflicts). Serialize execution so each suite
// gets exclusive access to the DB.
export default defineConfig({
  test: {
    fileParallelism: false,
  },
});
