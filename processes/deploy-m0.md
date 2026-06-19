# M0 Cloud Deploy Runbook (INB-12)

> **STATUS 2026-06-18 — Neon DONE (via `neonctl`):** project
> `spring-waterfall-37999618` (aws-us-west-2, pg17); M0 migration applied (7
> tables); family `fam_b47fc75bfb5a` + household token provisioned. The
> **pooled** DSN host is `ep-lively-sun-a6gu4yu6-pooler.us-west-2.aws.neon.tech`.
> **Verified:** local API pointed at the Neon pooler → CLI `push` → `/sync`
> round-trip works, `updated_at` keeps microsecond precision (F3 holds on Neon).
> Secret + DSNs are in `/tmp/neon.env` (local, NOT committed) — they go into
> Vercel's env at deploy. **Remaining: Vercel** (account + deploy §2).

Move the M0 API off `localhost` to **Vercel + Neon**. Single household,
plaintext, household token. Cost target: **$0** (Neon free + Vercel hobby),
well under the <$50/mo cap (ADR 0012). The app + CLI + Android client are
unchanged — only `FAMILYAI_API` and `DATABASE_URL`/secrets move.

Operator-gated steps (account creation, billing, auth) are marked **[YOU]**;
agent-doable-after-auth steps are **[AGENT]** (per ADR 0012 — once you've
created + authed the accounts, I can drive the Vercel MCP for the deploy).

## 1. Neon (Postgres) — [YOU] create, [AGENT] migrate

1. **[YOU]** Create a Neon account + project at neon.tech (region near you).
2. **[YOU]** Copy **two** connection strings from the dashboard:
   - **Direct** (for migrations + provisioning) — host like `ep-xxx.<region>.aws.neon.tech`.
   - **Pooled** (for the app) — host with **`-pooler`** + `?sslmode=require`
     (transaction mode; this is the one ADR 0018 mandates for the serverless API).
3. **[AGENT/YOU]** Apply the schema with the **direct** URL:
   ```
   psql "<DIRECT_URL>" -f apps/api/migrations/0001_m0_init.sql
   ```
4. **[AGENT/YOU]** Provision a family + household token against Neon (direct URL):
   ```
   DATABASE_URL="<DIRECT_URL>" node apps/api/scripts/provision.mjs "Your Family"
   ```
   Capture `FAMILY_ID`, `HOUSEHOLD_CREDENTIAL_ID`, `HOUSEHOLD_SECRET` (secret
   shown once — store it).

## 2. Vercel (API host) — [YOU] account, [AGENT] deploy

1. **[YOU]** Create a Vercel account; `npm i -g vercel` (or use the Vercel MCP).
2. **[YOU/AGENT]** In `apps/api/`: `vercel link` (create/link the project).
3. **[YOU/AGENT]** Set production env (encrypted at rest in Vercel):
   ```
   vercel env add DATABASE_URL production            # the POOLED Neon URL
   vercel env add HOUSEHOLD_SECRET production         # from provision
   vercel env add HOUSEHOLD_CREDENTIAL_ID production  # from provision
   ```
4. **[AGENT]** Preview deploy + smoke test (ADR 0012 preview-before-promote):
   ```
   vercel deploy                                      # → https://<preview>.vercel.app
   curl "https://<preview>/families/$FAMILY_ID/sync" -H "authorization: Bearer $HOUSEHOLD_SECRET"
   # expect 200 + your cards
   ```
5. **[AGENT]** Promote to prod once green: `vercel promote <preview>` (or `vercel --prod`).
   Rollback if health fails: `vercel rollback`.

## 3. The one known unknown — `.ts`-extension imports

`api/index.ts` imports `../src/app.ts` with explicit `.ts` extensions (needed
for the local `node`/`vitest` run). Vercel's bundler may reject them. If the
preview build fails with an unresolved-import / extension error, apply the
**ready fallback** — bundle with esbuild before deploy:

`apps/api/package.json` →
```json
"scripts": { "vercel-build": "esbuild api/index.ts --bundle --platform=node --format=esm --outfile=api/_bundled.mjs --packages=external" }
```
`apps/api/vercel.json` → point the function at `api/_bundled.mjs`, and add
`esbuild` to devDependencies. (esbuild rewrites the `.ts` imports during bundle;
`--packages=external` keeps `pg`/`hono` as node_modules.) I'll wire + verify
this on the first deploy if needed.

## 4. Repoint the clients — [AGENT]

- **CLI:** `export FAMILYAI_API="https://<prod-url>"` (FAMILY_ID + HOUSEHOLD_SECRET
  from §1.4). `familyai push …` now writes to the cloud.
- **Android:** rebuild with the prod config:
  ```
  FAMILYAI_API="https://<prod-url>" FAMILY_ID=… HOUSEHOLD_SECRET=… \
    ./gradlew assembleRelease   # (or assembleDebug for dogfood)
  ```
  Install on your phone (`adb install`) — now syncs from the cloud, no laptop.

## 5. Guardrails check

- **Cost:** Neon free + Vercel hobby = $0 idle/scale-to-zero. Under the cap.
- **Security:** secret lives only in Vercel's encrypted env + the Neon row
  (revocable via `revoked_at`); TLS to Neon; **plaintext M0** (no E2E — ADR
  0015 is M1). No Gmail/restricted scopes → no CASA (guardrail 3 clear).
- **CI:** the GitHub deploy can be gated on the green CI run (already in place).

## What I need from you to start

Create the **Neon project** + **Vercel account** and either (a) paste the two
Neon URLs + confirm Vercel is linked, or (b) auth the Vercel MCP and I'll drive
§1.3–§2.5 + the §3 fallback end to end, then verify the live `/sync`.
