// Required-env preflight. The 2026-06-25 prod outage had two root causes: missing
// DB tables (see schema-drift.mjs) AND missing AUTH_* env (AUTH_SIGNING_KEY/ISS/AUD
// were never set, so /auth/firebase 500'd while minting a token). This catches the
// env half: report any required var that's unset in the target environment.
//
// Usage: node scripts/env-check.mjs            # checks the current process.env
//   exit 0 = all present · 1 = missing/invalid · 2 = usage error
// Read-only: inspects env + source only; never mutates anything.
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve, join } from "node:path";

// Structural reads that don't use the throw-idiom but are equally required.
//
// SENTRY_NODE_EU_DSN / SENTRY_RELEASE are read by GENERATED SWIP code (ADR 0058), not by
// a guard in src/, so the grep below cannot see them — and their silent form is an API
// that believes it has error reporting and does not. SENTRY_NODE_EU_DSN is the API's OWN
// Sentry project: SENTRY_KOTLIN_EU_DSN is the mobile app's and SENTRY_DSN is legacy; all
// three are EU hosts, so only the name (and SWIP's org+project assertion at boot) tells
// them apart. VERCEL_ENV is required too but is set BY Vercel — listing it here would
// fail every local preflight, so it is documented, not checked.
// POSTHOG_* are guarded in src/swip.ts with the throw-idiom, but through a `${name}`
// template — the grep below reads LITERALS, so they are listed here too. (Keep both: the
// guard is what fails the boot; this is what fails the deploy preflight.)
const STRUCTURAL = [
  "DATABASE_URL",
  "FIREBASE_PROJECT_ID",
  "SENTRY_NODE_EU_DSN",
  "SENTRY_RELEASE",
  "POSTHOG_PROJECT_KEY",
  "POSTHOG_HOST",
];

function tsFiles(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...tsFiles(p));
    else if (e.name.endsWith(".ts")) out.push(p);
  }
  return out;
}

/** The required env vars: every `Missing required env var: NAME` guard in src/
 *  (drift-proof) plus the structural reads. Pure + sorted. */
export function requiredEnvVars(srcDir) {
  const re = /Missing required env var:\s*([A-Z][A-Z0-9_]*)/g;
  const names = new Set(STRUCTURAL);
  for (const f of tsFiles(srcDir)) {
    for (const m of readFileSync(f, "utf8").matchAll(re)) names.add(m[1]);
  }
  return [...names].sort();
}

/** Required vars absent (or empty) in `env`. Pure. */
export function missingEnv(required, env) {
  return required.filter((k) => !env[k]);
}

// ── CLI runner (only when executed directly) ──
async function main() {
  const here = dirname(fileURLToPath(import.meta.url));
  const required = requiredEnvVars(resolve(here, "../src"));
  const missing = missingEnv(required, process.env);
  if (missing.length > 0) {
    console.error(`ENV MISSING — ${missing.length}/${required.length} required var(s) unset:`);
    for (const k of missing) console.error(`  - ${k}`);
    console.error("set them in this environment (e.g. `vercel env add <NAME> production`) before deploy.");
    process.exit(1);
  }
  // AUTH_SIGNING_KEY must be a JSON JWK with a kid — a malformed key 500s token minting
  // just like a missing one. Validate shape (not the key material) when present.
  if (process.env.AUTH_SIGNING_KEY) {
    try {
      const jwk = JSON.parse(process.env.AUTH_SIGNING_KEY);
      if (!jwk.kid) throw new Error("AUTH_SIGNING_KEY JWK has no `kid` (verify allowlists by kid)");
    } catch (e) {
      console.error(`ENV INVALID — AUTH_SIGNING_KEY is not a valid JWK: ${e.message}`);
      process.exit(1);
    }
  }
  console.log(`env ok — all ${required.length} required vars present (${required.join(", ")})`);
  process.exit(0);
}

if (import.meta.url === `file://${process.argv[1]}`) await main();
