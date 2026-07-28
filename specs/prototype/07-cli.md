# 07 — CLI Tool (`dayfold`)

> Status: **reviewed (1 agent) → fixes applied**. The operator's + Claude Code's content
> authoring interface — the **dogfood critical path**. Pushes cards / hubs /
> blocks / markdown / triggers / places to the API (`03-api.md`). Kotlin/JVM
> (ADR 0013 — CLI stays Kotlin), **types codegen'd from the JSON schema** so
> the CLI and client share one content contract.
>
> **⚠ SUPERSEDED BY THE SHIPPED CLI (18th repo-maintenance pass, 2026-07-28).**
> This is an early prototype spec; the built CLI has diverged in ways that would
> mislead an agent reading this for "how auth/authoring actually works" —
> notably the env var is `DAYFOLD_API`/`FAMILY_ID`/`HOUSEHOLD_SECRET`, not
> `FAMILYAI_TOKEN` (§Auth below); `dayfold login` is a plain RFC 8628 device
> grant with **no** X25519/E2EE key bootstrap (E2EE is deferred, ADR 0017); and
> there is no declarative git-backed directory-authoring model — content is
> authored per-file via `dayfold push <id> <file.json>`. For the CURRENT CLI,
> use `dayfold help` / `dayfold <command> --help --json` (self-documenting,
> tested — `apps/cli/src/test/kotlin/.../HelpTest.kt`), `apps/cli/README.md`,
> `apps/cli/templates/README.md`, and `.claude/skills/dayfold-curator/`. Kept
> here only as historical record of the original M1 design intent (E2EE +
> declarative authoring), not as an accurate how-it-works doc.

## Auth

- **[M0]** household token: **OS keychain preferred**. Env (`FAMILYAI_TOKEN`)
  is allowed **only for headless/CI behind `--allow-env-key` with a loud
  warning** (env = plaintext-in-process: leaks via `ps`/CI logs/child procs) —
  not a co-equal default. Read **statelessly per invocation**, so a rotated
  secret (04-auth overlap window) is picked up automatically.
- **[M1]** **`dayfold login`** = RFC 8628 device grant **+ E2E key bootstrap**:
  CLI **generates an X25519 keypair**, calls `POST /device/authorize` **sending
  its public key** → prints a **QR + `user_code`** → operator scans with the
  signed-in app, **confirms the `user_code` + selects the family**, approves →
  CLI polls `/device/token` → receives a **scoped, content-only, revocable**
  credential **and the wrapped-`FCK` blob** (FCK sealed to the CLI pubkey, ADR
  0015) → stores the **private key + credential in the OS credential store**.
  `logout` revokes; `whoami` shows family + scope + label.

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
- **Stable IDs pinned to CONTENT, never to the mutable path (P0):** explicit
  `id:` is **mandatory** for persisted content. On first `push` of a file
  without one, the CLI **mints a ULID and writes it back into the frontmatter**
  (so the ID survives file rename/move — a path-hash would orphan+duplicate on
  rename). A **local manifest** (`~/.config/dayfold/<family>.manifest`) maps
  `path ↔ id ↔ server version`; `status` reads it.
- **Edit-stable section/block IDs (P0 — load-bearing for deep-links):** each
  section/block gets a stable anchor, NOT a heading-text/position derivation
  (which would re-key everything below an edited/inserted heading → mass
  orphan + **dangling card `target` deep-links**). The CLI **auto-injects a
  stable anchor on first push and rewrites the file** (`## Packing {#packing}`
  → a pinned slug/ULID) and tracks heading→id in the manifest, so a heading
  rename is an **UPDATE, not a recreate**. Long body → one `markdown` block
  with its own pinned id; checklists → `checklist` blocks.

## Commands

| Command | Action |
|---|---|
| `dayfold login` / `logout` / `whoami` | device-grant auth (M1) |
| `dayfold push [path]` | upsert content from a file/dir (the main verb) |
| `dayfold push --dry-run` / `--diff` | show what would change; no write |
| `dayfold hub get <id>` / `archive <id>` / `rm <id>` | hub ops |
| `dayfold card put …` / `rm <id>` | briefing card ops |
| `dayfold place put <ref> --geo lat,lng --radius 150` / `rm <ref>` | places (ADR 0014) |
| `dayfold status` | local manifest vs server (drift) |

- **Inline flags** mirror frontmatter for one-off pushes:
  `--geo place_ref|lat,lng --radius`, `--at/--when`, `--alert-offset`,
  `--target hub/{id}#block/{bid}` (card deep-link), `--md file.md`.

## Idempotency & concurrency

- Client-supplied stable IDs; **single-writer LWW at M0**; the CLI carries
  `version`/**`If-Match`** so `412` surfaces a conflict. **412 resolution UX:**
  re-pull `status` → show diff → `push --force` to overwrite (no silent
  clobber).
- **Full-replace is opt-in (P1):** default is **per-child upsert (merge)**.
  Absent-child deletion happens **only** under explicit `push --replace` (or
  frontmatter `replace: true`). `--dry-run`/`--diff` **shows deletions in red
  and requires confirmation** for any replace — protects against a stale local
  tree silently soft-deleting another writer's content.
- **One hub = one strategy per push** (all-children-inline-bulk OR
  all-as-separate-PUTs, never mixed) to avoid double-write/version races;
  parents always before children (no `409` orphans).
- **gzip**; large markdown per `06-storage` (uploads-first + `:confirm-spill`).
  The CLI does a **pre-flight POST-encryption size check** and warns locally,
  so a near-1 MB block doesn't silently `413` after ciphertext inflation.

## E2E (if ADR 0015 accepted)

Encryption happens **inside the `dayfold` binary** (never in skill JS/prompt
context): it holds `FCK` and encrypts `body_md`/`payload`/titles/`triggers`/
place coords client-side (AEAD) **before** push; uploads ciphertext for spill.
The server never sees plaintext or `FCK`.

- **AAD version authority (P1 — load-bearing, the whole chain depends on it):**
  AEAD AAD = `(family_id, id, version)`, but the server normally bumps
  `version`. Under E2E the **CLI computes the next `version` and sends it; the
  server validates it is monotonic and uses it (does NOT re-bump)** — so the
  AAD the writer encrypted under == the AAD a reader recomputes from the stored
  row == the object-key `{version}` (06-storage). *(Cross-spec: if ADR 0015 is
  accepted, 02/03/04's "server bumps version" becomes "client supplies,
  server validates monotonic.")*
- **Headless `FCK` source (P0):** keychain on interactive macOS; for headless/
  CI/loop runs (no OS keychain), `FAMILYAI_FCK` from a secret store or a
  `0600` file, **behind `--allow-env-key` + loud warning**. Without this, the
  "scheduled-task/loop authoring" use is impossible.

## Claude skill (`.claude/skills/dayfold/`)

Ship a Claude Code skill wrapping the CLI so **Claude authors + pushes content
as a power-user** — the original wedge + ADR 0012 agent-buildability. The skill
**only shells out to the `dayfold` binary** (which owns auth + encryption +
keychain); the skill JS/prompt context **never touches `FCK` or tokens**. It
documents the manifest format, the commands, and the ID rules, so an AI loop
generates a hub/card from context and `dayfold push`es it. For unattended
loops, the binary sources `FCK`/token per the headless rules above.

## DX / config

- Config under `~/.config/dayfold/` (profiles per family); **secrets in the
  OS keychain**, never the config file.
- Errors mapped from `problem+json` (content) + OAuth2 error JSON (device
  grant) into clear CLI messages; `--json` for machine output (for the skill).
- **Distribution:** a JVM binary (or `./gradlew` run) — the same Kotlin build
  as the client (shared modules). Verify loop `./gradlew build` (ADR 0013).

## DX notes (review)
- **`--diff`/`--dry-run` under E2E** pulls + **decrypts** server content to
  compare *plaintext* (needs read scope — confirm the M0 household token has
  content **read**, not just write, for diff) — never shows ciphertext.
- **`watch` mode** (if shipped): debounce, ignore editor temp/swap files,
  **never auto-`--replace`** (a half-saved file under full-replace = data loss).

## Visual enrichment authoring (ADR 0036)

Hubs and briefing cards carry an optional `media` object; blocks carry image
fields on their existing `payload`. The CLI validates these locally before push
(`MediaValidation`), mirroring the server gate — keep all three (server Zod, CLI,
client) in lock-step.

- **Hub.media** `{heroUrl, thumbnailUrl, heroFit:cover|contain, imageAlt, icon,
  accentColor}` · **BriefingCard.media** `{icon, accentColor, thumbnailUrl,
  imageAlt, imageFit}` · **block** link/document `payload.thumbnailUrl`(+`thumbnailAlt`),
  contact `payload.avatarUrl`(+`accentColor`). All optional; absent = today's look.
- **Image URLs (hardened, Phase 1):** `https` only, on the **host allowlist =
  exactly `upload.wikimedia.org`** (exact host, not suffix); no userinfo, no
  explicit non-443 port, **no SVG**, ≤2048 chars. Anything else → local validation
  error (and a server 422). Grow the allowlist one operator-approved host at a time.
- **`icon`** must be one of the curated 18 NAMES: `school, luggage, medical, move,
  party, baby, calendar, location, link, document, contact, budget, travel, car,
  food, pet, sport, list`. Unknown name → the client falls back to the accent tile.
- **`accentColor`** = `#RRGGBB` (lowercased on write). Decorative surfaces only
  (edge/tile/chip/scrim); never body text (WCAG 1.4.1).
- **`heroFit`/`imageFit`**: `cover` for photos (edge-to-edge crop), `contain` for
  logos/mascots (letterboxed on an accent tint).
- **Templates:** `dayfold template hub` / `dayfold template block` emit `media`
  examples. The constants live in the CLI's `MediaValidation` (bundled).
- **Skill duty:** the authoring skill reads these constants (never guesses a host
  or icon) and **surfaces the chosen hero/icon/accent to the operator for
  confirmation before `push`** — the trademark / wrong-entity guard (ADR 0036).
- **Note:** structured *block* payloads (incl. block media) only round-trip
  through the server once ADR 0035 reconciles the block-payload schema; until then
  block media is exercised by the CLI validator + client render (live content is
  `body_md`-only). Hub + card media round-trip server-side today.

## Open questions
- Manifest convention (one file per hub vs a tree) — settle with first dogfood.
- `watch` auto-push vs explicit `push` — likely both, ties to AI-loop use.
- `FCK` provisioning UX on the operator's machine (if ADR 0015).
- *(resolved: M0 token = content:read+write — 04-auth §M0.)*
