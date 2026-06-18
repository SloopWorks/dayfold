# Process — Agent-Operated Build & Deploy

How agents configure, deploy, and verify the build with minimal human steps.
Boundaries are fixed by **ADR 0012**; this doc is the how-to. The standing
guardrails in `CLAUDE.md` still apply — full *technical* autonomy stops at
pricing, legal/compliance filings, app-store submission, spend above cap, new
entities, and external/customer-facing actions.

## Autonomy summary (ADR 0012)

- **Deploys:** full autonomy **including production** — but only behind the
  safety rails below.
- **Cost actions:** allowed up to **< ~$50/mo infra + single charge ≤ $20**;
  above → escalate.
- **Browser consoles:** allowed **after the operator logs in**; agent runs a
  runbook below.

## Safety rails (mandatory — a rail gap is a P0)

1. **Test-green-before:** unit + integration + **Firebase Emulator** pass,
   and a **preview deploy verifies green**, before any prod promote.
2. **Verify-and-rollback-after:** every prod deploy runs an automated
   post-deploy health/smoke check (+ browser flow check where relevant);
   **failure auto-rolls-back** and escalates.
3. **Log every prod/cost action** (what, when, which credential, result).
4. **Reversibility bias:** prefer preview→promote + instant rollback.

## The agent build/verify loop

```
configure → build → test → deploy preview → verify → promote prod → verify → report
   (APIs,     (gradle) (unit+   (Vercel/      (smoke+   (only if      (health/
    DB, env)           int+emu)  Firebase)     browser)  green)        rollback)
```

## Toolchain (auth method · automation scope)

| Tool | Auth (operator one-time) | Agent scope |
|---|---|---|
| **gh** (GitHub) | already authed (`patjackson52`) | branch, PR, CI, releases |
| **Vercel** | MCP connected (`mcp__plugin_vercel_vercel`) + `vercel` CLI token | deploy preview/prod, env vars, logs, provision Marketplace DB (Neon/Supabase/Upstash) |
| **Firebase CLI** | `firebase login:ci` token **or** service-account + `GOOGLE_APPLICATION_CREDENTIALS` | deploy auth-config/rules/functions/hosting, App Check config |
| **Firebase Emulator Suite** | none (local) | configure + test auth/db/functions locally — **verify without prod/cost** |
| **gcloud** | service-account key (least-privilege) | enable APIs, IAM, project config |
| **DB (Neon/Supabase)** | CLI/API token | branch DBs, run migrations |
| **Gradle** | none | Android/JVM unit+integration, lint (headless) |
| **Claude-in-Chrome** | operator-authenticated session | drive consoles lacking CLIs (runbooks below) |

## Credential handoff model

Operator authenticates each tool **once** and exposes least-privilege
credentials agents may use (CI tokens / scoped service accounts in the
project secret store) + live browser sessions for console-only steps. Define
and minimize each credential's blast radius; prefer per-task scoped SAs over
broad ones. Secrets are never committed; agents read them from the configured
secret store / env.

## Browser runbooks (console steps without a CLI)

Operator logs in; agent executes. Keep each as a short checklist (link to the
console, the exact toggles, the verify step). Expected set:
- **Firebase Console** — enable Google/Apple/Phone providers; App Check
  enforcement; SMS region allowlist.
- **Google Cloud** — OAuth consent screen config (verification *submission*
  stays operator-gated); API enablement double-check.
- **Apple Developer / App Store Connect** — capability config (enrollment,
  signing, and **submission** remain operator-gated).
- **Vercel/DB dashboards** — only for what the CLI/MCP can't do.

(Store finalized runbooks here as they're written; align with the operator's
browser-agent-runbook conventions.)

## Verify toolkit

- **Firebase Emulator Suite** — auth/firestore/functions locally; the primary
  pre-deploy gate.
- **Preview deploys** (Vercel) — real-environment verify before prod.
- **Smoke/health checks** — scripted post-deploy; required for prod.
- **Browser-driven E2E** (Claude-in-Chrome) — drive the actual user flow
  (sign-in, push content, render) after operator auth.

## Stack-selection criterion (feeds C3)

Prefer tools with **CLI/MCP + emulator + preview/rollback** — they maximize
agent autonomy. Current lean (agent-buildability-weighted): **Vercel (MCP) +
Firebase (CLI + Emulator) + Neon/Supabase Postgres**. Final choice at C3.
