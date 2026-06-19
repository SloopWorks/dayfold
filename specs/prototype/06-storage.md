# 06 — Storage (object storage / large bodies / document refs)

> Status: **draft → in review**. Object storage for large-markdown spill +
> (post-MVP) document uploads, off `01-architecture.md` + `event-hubs-design.md`
> + the architecture security review. **E2E-agnostic:** if ADR 0015 is
> accepted, every stored body is already-encrypted ciphertext — the tenant-
> scoping controls below still apply as defense-in-depth.

## Scope by milestone

- **[M0] No object storage.** Content is **inline only** (`blocks.body_md`
  ≤1 MB in Postgres). Document blocks are **links + small refs** (URLs), never
  uploaded files. This component is essentially inert at M0.
- **[M1] Large-markdown spill.** `body_md` > ~1 MB spills to object storage;
  `blocks.body_ref` holds the key; Postgres keeps the metadata row.
- **[post-MVP] Document upload** (real PDFs/tickets) + its privacy tier — out
  of scope here (ADR 0006 revisit).

## Provider & key scheme

- Provider: **S3-compatible / Vercel Blob** (agent-buildable lean, C3). Private
  bucket, **no public/CDN exposure**.
- **Object keys are tenant-prefixed:** `{family_id}/{hub_id}/{block_id}/{version}`.
  Tenancy is enforced by the API (below), AND the prefix makes cross-tenant
  access structurally visible/testable.
- **Default-private ACL.** No object is ever world-readable or long-lived-URL.

## Write path (spill, M1)

1. CLI/Claude pushes a block whose `body_md` exceeds the inline limit.
2. API (after the tenancy middleware authorizes the write) either (a) accepts
   the body and stores it server-side to the tenant-prefixed key, or (b)
   issues a **short-lived scoped upload URL** (presigned PUT, ≤60 s, exact key,
   content-length bounded) the client uploads to directly.
3. API sets `blocks.body_ref` = key, clears `body_md`, bumps `version`. The
   **one-of CHECK** (`body_md` XOR `body_ref`) holds.
4. **Size guard:** max object size enforced (e.g. ≤25 MB) at the presign +
   at-rest; reject over-limit (`413`). Compressed/decompressed caps per
   `03-api`.

## Read path (M1)

1. Client `GET /families/{fid}/...` → API authorizes (middleware) → for a
   spilled block, returns a **freshly-minted signed GET URL** (presigned,
   **≤60 s**, single object, tenant-prefixed key).
2. Signed URLs are **minted only AFTER the membership/scope check** — they are
   never pre-generated, never embedded in stored markdown, never logged.
3. Client fetches the blob directly, decrypts if E2E, renders (mikepenz lazy).
   The blob is cached in the local (SQLDelight/SQLCipher) cache like inline
   content.
4. **IDOR matrix:** family A cannot mint or replay a signed URL for family B's
   object (the API never issues a URL for a key outside the authorized
   family). Replay within the ≤60 s window is the accepted residual.

## Lifecycle / GC

- **Soft-delete** a block → its `body_ref` object becomes **unreferenced**.
- A **GC sweep** deletes objects whose owning row is hard-purged or
  soft-deleted past a retention window (e.g. 30 d), so deep-link "that item
  moved" still resolves during the window.
- **Versioning:** a new spilled `version` writes a new key; the prior key is
  GC-eligible after the retention window (supports rollback/audit briefly).

## Security controls

- Private ACL + tenant-prefixed keys + post-authz **short-lived** signed URLs
  (the architecture-review P0 closed here).
- **The server NEVER fetches a document `ref` URL** (links open on the client,
  externally) → no SSRF surface from user/Claude-supplied URLs.
- No third-party CDN/asset on any page that handles a signed URL; no token in
  logs/traces (ADR 0012 audit excludes secret URL params).
- Bucket access via a least-privilege role (ADR 0012); deploy role binds
  credentials, doesn't read object data.
- **E2E (if ADR 0015):** bodies are ciphertext before they reach storage; the
  server/bucket can't read them even on breach. Signed-URL scoping remains
  (defense-in-depth + prevents blob-existence/size probing across tenants).

## Limits

- M0 inline `body_md` ≤ 1 MB (Postgres). M1 spill threshold ~1 MB; max object
  ~25 MB (revisit with document upload). Brotli/gzip on transport; serve raw
  `text/markdown` (or ciphertext) — not JSON-wrapped (per `event-hubs-design`).

## Open questions
- Provider choice (Vercel Blob vs S3/R2) — C3, weighted by agent-buildability.
- Presigned-upload (client→bucket) vs API-proxied upload — proxy is simpler/
  safer at low scale; presign saves API compute. Decide at C2/C3.
- Retention window for GC + whether spilled-version history is kept.
