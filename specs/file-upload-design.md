# File Upload & Storage — Design

Status: PROPOSED — UNDECIDED (plan only; no implementation committed; extract ADRs on acceptance)
Date: 2026-08-30
Author: operator + claude
Related: ADR 0014 (privacy chips), ADR 0020 (offline-first client data), ADR 0030 (per-member visibility),
ADR 0038/0039 (two-way mutation engine), ADR 0040 (freshness & tombstones), ADR 0049 (geofence posture),
ADR 0053 (per-hub participation roles), specs/domain-model (content.schema.json `document`/`file` payloads)

## 1. Problem

Dayfold content refers to real documents — health forms, school schedules, permission slips, receipts —
but the platform has **no way to store file bytes**. The `document` block and `file` card payloads carry a
`docRef` that must already be a URL; images are restricted to an allowlisted external host
(`upload.wikimedia.org`). The curator agent hit this wall trying to attach a school-schedule PDF: there is
nowhere to put the bytes.

This spec adds a first-class file service: upload, storage, retrieval, quotas, ACL-guarded access,
lifecycle, and client (Android/iOS) integration — offline-first and consistent with the existing content
model.

## 2. Shape of the solution (one paragraph)

A new **Files Worker** on Cloudflare (`apps/files-worker`) owns file bytes in **R2** and file metadata +
storage accounting in **D1**. The existing core API (Hono on Vercel + Postgres) remains the **policy
decision point**: it knows tenancy, visibility allow-lists, and hub roles, and it mints **short-lived
signed grants** (JWTs) for upload and download. The Files Worker is a stateless **enforcement point**: it
verifies grants, streams bytes, enforces size while streaming, and never makes ACL decisions of its own.
Content in Postgres references files by an opaque `dfile://<fileId>` ref in the existing `document`/`file`
payloads. Files are **immutable after commit**; replacement mints a new file id and tombstones the old one.

```
client ──(1) POST /files/intent──────────▶ core API (Vercel, Postgres)   ← policy: authz + quota reserve
client ◀─(2) upload grant JWT + fileId ───┘
client ──(3) PUT bytes + grant───────────▶ Files Worker (CF) ──▶ R2 (bytes) + D1 (metadata, usage)
client ──(4) attach dfile://id in content▶ core API (normal content push)
client ──(5) GET /files/:id/grant────────▶ core API                       ← policy: ACL check at mint time
client ──(6) GET bytes + grant───────────▶ Files Worker ──▶ R2 stream (etag/304)
```

Why split-stack: the operator wants file storage on Cloudflare (R2 economics: zero egress fees; D1
locality with the Worker). The split is safe **because the Worker holds no policy** — revoking access =
core API stops minting grants; grants expire in minutes. No ACL replication between Postgres and D1, so
there is no cross-database consistency problem for authorization.

## 3. D1 schema (metadata + accounting)

D1 stores metadata only — **never file bytes** (1 MB row limit; BLOB export is unreliable). SQLite
conventions: ISO-8601 TEXT timestamps, INTEGER 0/1 booleans, `PRAGMA foreign_keys = ON`, prepared
statements everywhere, indexes on every query path.

```sql
CREATE TABLE files (
  id                TEXT PRIMARY KEY,          -- ULID; also the R2 key suffix
  family_id         TEXT NOT NULL,
  owner_user_id     TEXT NOT NULL,             -- uploader; adults only (accounts are adults, ADR guardrail)
  r2_key            TEXT NOT NULL UNIQUE,      -- f/{family_id}/{id}
  status            TEXT NOT NULL DEFAULT 'pending',  -- pending | committed | tombstoned
  size_bytes        INTEGER,                   -- authoritative after commit
  mime_declared     TEXT,                      -- what the client claimed
  mime_sniffed      TEXT,                      -- what the Worker detected (authoritative)
  sha256            TEXT,                      -- integrity + dedup hint; verified by R2 on put
  filename_original TEXT,                      -- display only; NEVER used as a key
  storage_class     TEXT NOT NULL DEFAULT 'Standard',  -- Standard | InfrequentAccess (archive)
  sensitivity       TEXT NOT NULL DEFAULT 'normal',    -- normal | sensitive (envelope-encrypted)
  expires_at        TEXT,                      -- optional TTL; NULL = keep until deleted/orphaned
  derived_from      TEXT REFERENCES files(id), -- thumbnails/previews chain
  version_of        TEXT REFERENCES files(id), -- replacement chain (old file tombstoned)
  provenance        TEXT NOT NULL,             -- JSON: {source, at, credential_id} — matches content model
  origin            TEXT NOT NULL,             -- JSON: see §9 Provenance
  created_at        TEXT NOT NULL,
  committed_at      TEXT,
  tombstoned_at     TEXT
);
CREATE INDEX idx_files_family      ON files(family_id, status);
CREATE INDEX idx_files_expiry      ON files(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_files_gc          ON files(status, created_at);   -- pending-abandoned sweep

CREATE TABLE upload_intents (
  id                  TEXT PRIMARY KEY,        -- ULID; jti of the upload grant
  file_id             TEXT NOT NULL REFERENCES files(id),
  family_id           TEXT NOT NULL,
  user_id             TEXT NOT NULL,
  reserved_bytes      INTEGER NOT NULL,        -- counted against quota until commit/expiry
  multipart_upload_id TEXT,                    -- R2 multipart id (large files)
  parts_state         TEXT,                    -- JSON: [{part, etag, size}] for resume
  expires_at          TEXT NOT NULL,           -- intent TTL (24 h); reservation released after
  created_at          TEXT NOT NULL
);
CREATE INDEX idx_intents_expiry ON upload_intents(expires_at);

CREATE TABLE storage_usage (
  family_id       TEXT PRIMARY KEY,
  bytes_used      INTEGER NOT NULL DEFAULT 0,  -- committed files
  bytes_reserved  INTEGER NOT NULL DEFAULT 0,  -- open intents
  file_count      INTEGER NOT NULL DEFAULT 0,
  quota_bytes     INTEGER NOT NULL,            -- per-family limit (plan-driven)
  updated_at      TEXT NOT NULL
);

CREATE TABLE file_audit (
  id        TEXT PRIMARY KEY,
  file_id   TEXT NOT NULL,
  family_id TEXT NOT NULL,
  user_id   TEXT,                              -- NULL for system (GC, lifecycle)
  action    TEXT NOT NULL,   -- intent | commit | download | tombstone | gc_delete | acl_denied
  at        TEXT NOT NULL,
  detail    TEXT                               -- JSON: ip country, ua class, grant jti, bytes
);
CREATE INDEX idx_audit_file ON file_audit(file_id, at);
```

Batch writes are chunked (D1 batch limits); the usage row is updated in the **same batch** as the file
status transition so accounting can never drift from state. A nightly reconciliation job recomputes
`storage_usage` from `files` and logs (never silently fixes) any drift.

## 4. Requirement-by-requirement

### 4.1 Storage accounting & limits (req 1)

- **Tenant = family** (matches every other resource). `storage_usage` row per family.
- Quota check happens at **intent time** on the core API: `bytes_used + bytes_reserved + declared_size
  <= quota_bytes`, else `413 quota_exceeded` with a structured body the client renders ("Storage full —
  8.0 of 8 GB used").
- Reservation model: intent reserves `declared_size`; commit reconciles to actual streamed size (Worker
  counts bytes; a stream exceeding `maxBytes` in the grant is aborted mid-flight). Intent expiry (24 h)
  releases the reservation via the GC cron.
- Per-file caps ride in the grant: v1 defaults **100 MB/file**, **8 GB/family** (constants in one place,
  plan-driven later). Per-user accounting is derivable from `files.owner_user_id` — reported in settings,
  not enforced (family is the budget owner).
- Surfacing: core API `GET /families/:id/storage` returns usage for the client settings screen; the same
  numbers feed an "approaching quota" card the curator can author honestly.

### 4.2 Privacy & encryption (req 2)

- **In transit:** TLS everywhere (client→API, client→Worker, Worker→R2 internal).
- **At rest, default:** R2 server-side encryption is always on (AES-256, Cloudflare-managed keys). For
  the `normal` sensitivity class this is the whole story — matches the rest of the platform (Postgres at
  rest is provider-encrypted too).
- **`sensitive` class (health forms, IDs):** app-layer **envelope encryption**. Worker generates a random
  256-bit DEK per file, encrypts bytes with AES-GCM before `put`, wraps the DEK with a family KEK held in
  Worker secrets (rotatable; wrapped-DEK stored in `files.origin.wrapped_dek`). R2 never sees plaintext;
  a leaked bucket credential yields ciphertext. SSE-C was considered and rejected: it breaks ranged
  reads through caches and complicates multipart; envelope-at-Worker keeps the client protocol identical.
- **Not E2EE, stated honestly:** the Worker sees plaintext during upload/download. True E2EE is a future
  ADR — it would kill server-side thumbnailing and cross-device access without a key-sync protocol.
  Privacy chips must not overclaim (ADR 0014): file cards get storage chip `on_device` only for the
  cached copy; the canonical copy is family-synced content.
- **Images:** EXIF GPS is stripped by default at commit (post-process, §4.6) — a photographed form should
  not leak home coordinates. Opt-out flag on intent for cases where location matters.
- Filenames are display metadata, never keys; keys are ULIDs (no PII in R2 keys or URLs — URL-parameter
  privacy rule).

### 4.3 ACLs (req 3)

Files do not grow a new ACL system. They attach to the **existing three-axis model**:

1. **Tenancy** — `family_id` on every row; cross-family access is structurally impossible (grants carry
   the family, Worker checks it).
2. **Visibility (ADR 0030)** — a file's effective visibility is **derived from its attachment point**:
   the hub/card/block whose payload references `dfile://<id>`. Private hub → file grants minted only for
   the allow-list. Unattached files (uploaded, not yet referenced) are visible to the **owner only**.
3. **Per-hub roles (ADR 0053)** — `viewer` can download; `contributor`/`co_owner` can attach new files
   and replace (`version_of`); only the **owner or a hub co_owner** can tombstone.

Resolution lives where the data lives: the core API (Postgres) answers "may user U access file F?" by
walking `content payload refs → resource_visibility → membership`. A `file_refs(file_id, resource_type,
resource_id)` join table in **Postgres** (maintained on content push, exactly like body_md linkification)
makes the walk O(1). The Worker never resolves ACLs — it verifies grant signatures.

### 4.4 Security — only owners and permitted members (req 4)

- **No public bucket. No long-lived URLs. No direct R2 exposure.** Every byte flows through the Worker.
- **Grants**: ES256 JWTs signed by the core API, verified by the Worker against a pinned JWKS.
  - Upload grant: `{jti, sub, family_id, file_id, max_bytes, mime_allowlist, exp: +15 min}`.
  - Download grant: `{jti, sub, family_id, file_id, exp: +5 min}` — minted **per access**, so a
    visibility change or member removal takes effect within minutes, not at credential rotation.
  - Replay: `jti` recorded in `file_audit`; multipart part-uploads reuse one grant (parts are idempotent
    by part number + etag).
- **Content validation at the Worker:** magic-byte sniffing (`mime_sniffed` authoritative), extension
  allowlist per intent purpose, byte-count enforcement mid-stream, `sha256` passed to R2 for integrity.
  Executables/scripts rejected outright for v1 (`application/x-*executable`, `.apk`, `.sh`, …).
- **Serving:** `Content-Disposition: attachment` + original filename (sanitized); `Content-Type` from
  `mime_sniffed`, never client-declared; `X-Content-Type-Options: nosniff`; no HTML/SVG served inline
  (SVG = script vector — render to raster for previews or force download).
- **Malware posture:** v1 = type allowlist + size caps + audit; an AV-scan Queue consumer is a marked
  extension point at the event-notification hook (§4.6), flipping `status` to `quarantined` (additive
  enum value) on detection.
- **Rate limits:** per-user intent creation (burst 10/min) and per-family download bandwidth guard at
  the Worker; audit row on every denial.

### 4.5 TTL & lifecycle (req 5)

Four independent clocks, each with one owner:

| Clock | Owner | Behavior |
|---|---|---|
| Upload-intent TTL (24 h) | GC cron | Abort R2 multipart, release reservation, delete `pending` row |
| Abandoned multipart (7 d) | R2 built-in | Auto-abort — backstop under the cron |
| File `expires_at` | GC cron | Tombstone at expiry (mirrors card `expires_at`; a receipt card's file can die with the card) |
| Orphan grace (30 d) | GC cron | Committed file with zero `file_refs` for 30 d → tombstone (protects "upload now, attach later" flows) |

Tombstone ≠ erase: `tombstoned` rows keep metadata for the ADR 0040 retention window (client sync
convergence), then the cron hard-deletes R2 object + row + decrements usage. R2 **lifecycle rules** are
configured as a belt-and-suspenders on the `tmp/` prefix only; authoritative deletion is always
cron-driven so D1 accounting and R2 state move together. Deletion order: R2 delete **first**, then D1
row — a crash between the two leaves a metadata row the reconciler flags, never an orphaned invisible
object that bills forever.

### 4.6 Cloudflare best practices applied (req 6)

**R2**
- Keys: `f/{family_id}/{file_id}` — ULIDs, validated (no `..`, no leading `/`), family prefix enables
  per-tenant listing and future export.
- Simple `put` streaming for ≤ ~95 MB with **explicit Content-Length** (unknown-length streams truncate
  silently); **multipart** above that: uniform part size (8 MB), parts numbered from 1, ≥5 MB minimum,
  part state tracked in `upload_intents.parts_state` for resume via `resumeMultipartUpload`.
- One checksum only (`sha256`), `httpEtag` (quoted) for HTTP caching, conditional GET → 304 with the
  body-null check, `list()` paginated by `truncated`/`cursor` never by count.
- `customMetadata` ≤ 2 KB — carry only `{file_id, family_id}` for disaster reconciliation; D1 is the
  metadata source of truth.
- Storage class `Standard`; `InfrequentAccess` only via explicit archive action (30-day minimum billing,
  retrieval fees, no IA→Standard lifecycle transition — one-way door, so never automatic).
- **Event notifications → Queues** for post-processing: EXIF strip, thumbnail render (Images binding),
  PDF first-page preview. Derived outputs are normal `files` rows with `derived_from` set. Consumers are
  idempotent (notifications are at-least-once).
- Presigned S3 URLs deliberately **not** used client-facing (would bypass the Worker's streaming
  enforcement and mime sniffing); the S3 API stays available for ops tooling.
- Local dev: `wrangler dev --remote` for multipart work (Miniflare R2 lacks it); Miniflare fine for the
  simple-put path.

**D1**
- Prepared statements + bind everywhere (injection posture), FK enforcement on, `EXPLAIN QUERY PLAN` in
  CI for the hot queries, batches chunked under the statement cap.
- Migrations via `wrangler d1 migrations apply --remote` wired into the existing Migrate workflow
  pattern; Time Travel is the rollback story.
- Single D1 database at launch; keys are family-prefixed so a future per-family database shard (the D1
  horizontal-scale pattern) is a data move, not a schema change. 10 GB paid cap ≫ metadata volume.
- Sessions API only for migrations/backfills, `try/finally close()`.

### 4.7 Android & iOS background transfer (req 7)

The wire protocol is the same for both: intent → (parts) → commit, every step resumable, part state
recoverable from the server (`GET /files/:id/intent` returns `parts_state`).

**Android**
- **WorkManager** owns every transfer: `Constraints(NetworkType.UNMETERED | CONNECTED)` chosen from the
  user's sync setting (§4.8), `BackoffPolicy.EXPONENTIAL`, unique work per `file_id` (dedupe on retry).
- Large uploads: `setForegroundAsync` with FGS type **`dataSync`** and a progress notification —
  required posture on Android 14+; keep chunks small enough (8 MB parts) that each Worker attempt fits
  comfortably inside JobScheduler execution windows, so even non-FGS retries make forward progress.
- Doze/App Standby: rely on WorkManager deferral — never `setExpedited` for bulk bytes (quota-limited);
  expedited only for tiny high-urgency uploads (a photo attached to an active card).
- Downloads: same WorkManager pipeline into the local cache; ranged GETs resume partial downloads.

**iOS**
- **`URLSession` background configuration** per transfer batch: `isDiscretionary` mapped from the
  metered/battery setting, `waitsForConnectivity = true`, upload tasks **from file** (background sessions
  require `fromFile:`; stage each part as a temp file slice).
- Resume: rely on per-part idempotency rather than `resumeData` fragility — on relaunch, re-list server
  part state and continue from the first missing part.
- `handleEventsForBackgroundURLSession` completion handling wired in the app delegate; BGProcessingTask
  for periodic cache GC and orphan-part cleanup.
- Low Data Mode: `allowsConstrainedNetworkAccess = false` for bulk transfers when the user's setting is
  Wi-Fi-only (Low Data Mode is the iOS-native metered signal).

### 4.8 Offline-first client architecture (req 8)

Extends ADR 0020's model — files get the same treatment as content rows:

- **Local file store**: app-private directory, content-addressed by `file_id` (immutability ⇒ a cached
  file is **valid forever** — no revalidation traffic, `etag` match is a formality). Metadata rows in the
  client DB with state machines:
  - Outbound: `staged → queued → uploading(parts…) → committed | failed(retryable)`
  - Inbound: `remote-only → fetching → cached → pinned | evicted`
- **Write path is optimistic**: attaching a file to content works offline — the block payload carries
  `dfile://<local-staged-id>`; the sync engine uploads first, rewrites the ref to the server id on
  commit, then pushes the content mutation (two-way engine, ADR 0039, gains one new outbound op type).
- **Cache policy**: LRU over `cached` files with a user-visible size budget (default 500 MB, slider in
  settings); `pinned` files (user "keep offline", or referenced by a hub the user pinned) are exempt.
  Thumbnails/previews cached separately with a small always-on budget — lists render offline even when
  originals are evicted.
- **Metered respect**: one tri-state app setting — *Sync files: Wi-Fi only / Always / Ask for large
  files* — mapped to WorkManager constraints (Android) and `isDiscretionary`/constrained-network flags
  (iOS). Thumbnails are exempt (small); originals over a threshold prompt on metered when "Ask" is set.
  Downloads are **on-demand by default** (tap to fetch) — no automatic bulk prefetch of originals.
- **Freshness spectrum (ADR 0040)**: file *metadata* syncs like any content (tombstones propagate,
  client evicts bytes when the tombstone lands); file *bytes* are pull-through cache, never part of the
  sync delta.

### 4.9 Provenance (req 9)

Two layers, both mandatory at intent time:

- `provenance` — identical shape to every other resource: `{source: "user" | "claude" | "codex" | …,
  at, credential_id}` (server-stamped credential). Curator-authored uploads say so; guardrails §5
  applies unchanged.
- `origin` — file-specific JSON captured at intent + enriched at commit:

```json
{
  "captured_by":   "android|ios|cli|api",
  "app_version":   "1.4.2",
  "device_model":  "Pixel 9",
  "original_filename": "fairbanks_fall_26_schedule.pdf",
  "mime_declared": "application/pdf",
  "source_kind":   "camera|photo_library|document_scan|file_picker|share_sheet|agent_fetch",
  "source_ref":    "gmail thread 1a03…| https url | null",
  "sha256":        "…",
  "exif_stripped": true,
  "wrapped_dek":   "… (sensitive class only)"
}
```

- Derived files chain via `derived_from`; replacements via `version_of` — so "where did this PDF come
  from, and is this the version the school sent?" is answerable from metadata alone. Audit rows (§3)
  give the access half of the story: who downloaded what, when.
- Agent-fetched files must carry `source_ref` (the email/URL they came from) — the curator skill's
  link-back rule extended to bytes.

### 4.10 Opening files on mobile (req 10)

- **Type identity comes from `mime_sniffed`, never the filename.** The client maps mime → platform type
  (Android mime string / iOS `UTType`), with a curated table for the family-doc formats that matter:
  pdf, jpeg/png/heic, office/iWork docs, txt/csv/ics.
- **In-app preview first** (keeps bytes in the sandbox, honest privacy):
  - Images: native rendering (Coil / SwiftUI).
  - PDF: Android `PdfRenderer`, iOS `PDFKit`.
  - iOS everything-else: **QuickLook** (`QLPreviewController`) — covers office formats free.
  - Android office formats: no system previewer — go straight to open-with.
- **Open-with / export** (bytes leave the sandbox — one-time confirmation the first time per file):
  - Android: copy to a `FileProvider`-served cache path, `ACTION_VIEW` with `content://` URI +
    `FLAG_GRANT_READ_URI_PERMISSION`, chooser fallback; SAF `CREATE_DOCUMENT` for "save to Files".
  - iOS: `UIActivityViewController` / `UIDocumentInteractionController` with a temp-file URL; temp
    cleaned after the interaction.
  - Never grant write URIs; never export a `sensitive`-class file without the confirmation sheet.
- Unknown mime: no inline render, no ACTION_VIEW guess — export/share only, labeled with size and type.

## 5. Content-model integration

- `document` block payload: `docRef` accepts `dfile://<fileId>` alongside https URLs; client resolves via
  the grant flow, renders the cached thumbnail.
- `file` card payload: `{filename, mime, size, pages, source, modified, docRef}` — all now fillable from
  the `files` row; the card's chip shows real size/pages.
- CLI: `dayfold upload <path> [--sensitive] [--expires <iso>]` → runs intent/put/commit, prints
  `dfile://<id>` for use in a subsequent block push. `dayfold pull` gains `--files <dir>` to fetch
  referenced files.
- Curator skill: gains an "attach the actual document" rule — prefer uploading the source PDF over
  paraphrasing it, with `origin.source_ref` pointing at the email/URL it came from.

## 6. Rollout

1. **M1 — plumbing**: Worker + D1 schema + R2 bucket + grants; CLI upload/download; `document` blocks
   render `dfile://` on Android. No multipart (100 MB simple-put ceiling covers v1 docs).
2. **M2 — clients**: Android WorkManager pipeline + cache + settings; iOS background session pipeline;
   open-with/preview matrix.
3. **M3 — lifecycle & post-processing**: GC cron, event-notification queue (EXIF strip, thumbnails),
   storage settings screen, quota cards.
4. **M4 — hardening**: sensitive-class envelope encryption, AV-scan hook, archive storage class,
   per-family D1 shard decision review.

## 7. Open questions

- Quota tiers: is 8 GB/family the right free ceiling, and does a paid tier exist to justify the
  reservation machinery? (Accounting is built plan-agnostic.)
- HEIC: transcode-to-JPEG at commit for cross-platform preview, or store original + derived JPEG?
  (Leaning: keep original, derive JPEG preview — provenance intact.)
- Should the Worker also front hero/thumbnail images for hubs/cards, retiring the
  `upload.wikimedia.org`-only allowlist? (Natural M3 follow-on; guardrail 8 operator-surfacing stays.)
- Web client (wasmJs) — background transfer story is different (no service worker access to native
  constraints); out of scope until a web target ships.
