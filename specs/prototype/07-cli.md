# 07 — CLI Tool (`familyai`)

> Status: **draft → in review**. The operator's + Claude Code's content
> authoring interface — the **dogfood critical path**. Pushes cards / hubs /
> blocks / markdown / triggers / places to the API (`03-api.md`). Kotlin/JVM
> (ADR 0013 — CLI stays Kotlin), **types codegen'd from the JSON schema** so
> the CLI and client share one content contract.

## Auth

- **[M0]** household token from a **platform secret / OS keychain** (never a
  flag, never plaintext in repo). `familyai` reads it from the configured env/
  keychain entry; one fixed family.
- **[M1]** **`familyai login`** = RFC 8628 device grant: calls
  `POST /device/authorize` → prints a **QR + `user_code`** → operator scans
  with the signed-in app, **confirms the `user_code` + selects the family**,
  approves → CLI polls `/device/token` (honoring `interval`/`slow_down`) →
  stores a **scoped, content-only, family-scoped, revocable** credential in the
  **OS credential store** (Keychain/Keystore/libsecret). `familyai logout`
  revokes it; `familyai whoami` shows family + scope + label.

## Authoring model (declarative, git-backable)

The power-user flow (ADR 0006): the operator/Claude maintains content as
**files in a directory** (the authoring source — may live in a git repo); the
CLI **upserts idempotently** to the platform. The app owns the rendered copy;
the files are the optional upstream.

- **Content files** = Markdown with **YAML frontmatter** for metadata:
  ```markdown
  ---
  id: trip-2026            # stable id (or derived deterministically from path)
  kind: hub                # hub | card | place
  type: vacation           # hub template type
  title: "Maui trip"
  countdown_to: 2026-08-01
  triggers:                # ADR 0014
    - when: { relative: "-1d", alert_offset: "-2h" }
    - geo:  { place_ref: home, radius_m: 150 }
  ---
  ## Packing
  - [ ] sunscreen
  ```
- **Deterministic IDs (ADR 0013 Rule G — mint at edge):** explicit `id:` or a
  stable hash of the file path, so **re-push is idempotent** (same file → same
  hub/section/block IDs → PUT updates, never duplicates).
- **Sections/blocks** derive from the markdown structure (headings → sections;
  a long body → one `markdown` block; checklists → `checklist` blocks) — or are
  declared explicitly in frontmatter for precision.

## Commands

| Command | Action |
|---|---|
| `familyai login` / `logout` / `whoami` | device-grant auth (M1) |
| `familyai push [path]` | upsert content from a file/dir (the main verb) |
| `familyai push --dry-run` / `--diff` | show what would change; no write |
| `familyai hub get <id>` / `archive <id>` / `rm <id>` | hub ops |
| `familyai card put …` / `rm <id>` | briefing card ops |
| `familyai place put <ref> --geo lat,lng --radius 150` / `rm <ref>` | places (ADR 0014) |
| `familyai status` | local manifest vs server (drift) |

- **Inline flags** mirror frontmatter for one-off pushes:
  `--geo place_ref|lat,lng --radius`, `--at/--when`, `--alert-offset`,
  `--target hub/{id}#block/{bid}` (card deep-link), `--md file.md`.

## Idempotency & concurrency

- Client-supplied stable IDs; **single-writer LWW at M0** (the operator is the
  only writer); the CLI carries `version`/**`If-Match`** so a `412` surfaces a
  conflict instead of clobbering once a 2nd writer exists.
- **Nested upsert** sends parents before children (or the bulk hub PUT with
  full-replace, declared); the CLI guarantees parent-exists ordering to avoid
  `409`.
- **gzip** request bodies; large markdown handled per `06-storage` (the CLI
  uploads-first + `:confirm-spill` when a body exceeds the inline threshold).

## E2E (if ADR 0015 accepted)

The CLI is the **encryptor**: it holds the family content key (`FCK`,
keychain), encrypts `body_md`/`payload`/titles/`triggers`/place coords
**client-side** (AEAD, AAD=`(family_id,id,version)`) **before** push, and
uploads ciphertext for spill. At M0 this is trivial (operator's single `FCK`).
The server never sees plaintext or `FCK`.

## Claude skill (`.claude/skills/familyai/`)

Ship a Claude Code skill wrapping the CLI so **Claude authors + pushes content
as a power-user** — the original wedge + ADR 0012 agent-buildability. The skill
documents the manifest format, the commands, and the deterministic-ID rule, so
an AI loop can generate a hub/card from context and `familyai push` it. (This
is how the dogfood content actually gets authored.)

## DX / config

- Config under `~/.config/familyai/` (profiles per family); **secrets in the
  OS keychain**, never the config file.
- Errors mapped from `problem+json` (content) + OAuth2 error JSON (device
  grant) into clear CLI messages; `--json` for machine output (for the skill).
- **Distribution:** a JVM binary (or `./gradlew` run) — the same Kotlin build
  as the client (shared modules). Verify loop `./gradlew build` (ADR 0013).

## Open questions
- Manifest convention (one file per hub vs a tree) — settle with first dogfood.
- Whether the CLI watches a dir and auto-pushes (scheduled-task/loop authoring)
  vs explicit `push` — likely both (a `familyai watch`), ties to AI-loop use.
- `FCK` provisioning/storage UX on the operator's machine (if ADR 0015).
