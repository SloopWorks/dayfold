# 06 — Storage (object storage / large bodies / document refs)

> Status: **reviewed (1 agent) → fixes applied**. Object storage for large-markdown spill +
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
- **Object keys are tenant-prefixed + nonce-suffixed:**
  `{family_id}/{hub_id}/{block_id}/{version}/{rand}` (the `{rand}` nonce means
  an undelete/re-create never reuses a key being GC'd — S7). `body_ref` is an
  **opaque KEY, never a URL**; a validator enforces `^{family_id}/` (rejects a
  compromised writer planting `body_ref='https://…'` — closes the SSRF door,
  S5). The server resolves it **only against the one configured bucket via
  SDK**, never via arbitrary-URL fetch.
- **Default-private ACL**, **`s3:ListBucket` denied** (missing key → uniform
  `AccessDenied`, not `NoSuchKey` → no cross-tenant existence oracle, S4), no
  anonymous access, no public/long-lived URL, **per-env bucket** (preview never
  shares prod's bucket).

## Write path (spill, M1) — upload-first, then atomic confirm

1. Client/CLI detects its (encrypted, if ADR 0015) body exceeds the spill
   threshold → uploads **bytes-first** to the bucket, then commits the DB swap.
   Two mechanisms (C2/C3 decision):
   - **API-proxied upload** (simplest/safest at low scale; **the only path
     valid under E2E** — the server just relays already-ciphertext bytes,
     never decrypts/encrypts), or
   - **client-presigned upload** with a **real size bound**: S3/R2 **POST
     policy `content-length-range`** (NOT a bare presigned PUT — a PUT signs
     one exact length and can't express "≤N", inviting an upload bomb), or
     Vercel Blob client-upload `maximumSizeInBytes`.
2. **Atomic confirm:** client calls `POST …/blocks/{bid}:confirm-spill {key,
   size}` → API **HEADs the object** (exists + size ≤ cap), then in **one
   transaction** swaps `body_md → NULL, body_ref = key`, bumps `version`. The
   one-of CHECK (`body_md` XOR `body_ref`) is never transiently violated;
   **`body_md` is never cleared until the object is confirmed present**.
   Missing/oversize → reject (`413`/`409`), `body_md` retained.
3. **Server cannot encrypt (E1):** under ADR 0015 the body arrives as
   ciphertext; the **server-side-encrypt-and-store branch is void** — server
   only relays/keys opaque bytes. The object key's `{version}` segment ==
   AAD `version` == `blocks.version` (AAD = `(family_id,id,version)`); new
   version → new AAD + new key together.

## Read path (M1)

1. Client `GET /families/{fid}/...` → API authorizes (middleware) → for a
   spilled block, mints a signed **GET-only** URL **over the exact `body_ref`
   read from the authorized row** (S3/S5 — the key is **never reassembled from
   request path segments**, so a crafted `hub_id`/`block_id` can't target
   another tenant): presigned, **≤60 s**, single object, GET-only (no
   PUT/DELETE/List), HTTPS-only.
2. Signed URLs are **minted only AFTER the membership/scope check** — never
   pre-generated, never embedded in stored markdown, never logged, **never
   persisted** (transient in memory only — not in crash reports, not a cached
   field, not a referer header).
3. Client fetches the blob directly, decrypts if E2E, renders (mikepenz lazy).
   The blob is cached in the local (SQLDelight/SQLCipher) cache like inline
   content.
4. **IDOR matrix:** family A cannot mint or replay a signed URL for family B's
   object (the API never issues a URL for a key outside the authorized
   family). Replay within the ≤60 s window is the accepted residual.

## Lifecycle / GC

- **Unreferenced-key sweep** (unifies the cases, C2/S7): an object is reclaimed
  when **no live row's current `body_ref` equals its key** — covers soft-
  deleted rows, hard-purged rows, **and rows whose body shrank back inline or
  bumped to a new version** (prior keys). Not "deleted rows only."
- **Race-safe delete (S7):** GC re-checks, **in the deleting transaction under
  the row lock**, that the key is still unreferenced (read-current-ref-then-
  delete) before the bucket `DELETE` — never a precomputed batch. Nonce-
  suffixed keys mean an undelete/re-create can't collide with a key mid-GC.
- **Retention window** (e.g. 30 d) is justified by **sync-lag + rollback
  grace** — a client mid-sync may request a soft-deleted block's body before
  applying the tombstone. *(Not* deep-link resolution — that's client-side off
  the local cache, C4.)
- **GC role is delete-only**, separate from the runtime API role, so a runtime
  compromise can't mass-delete via the GC path.

## Security controls

- Private ACL + tenant-prefixed keys + post-authz **short-lived** signed URLs
  (the architecture-review P0 closed here).
- **The server NEVER fetches a document `ref` URL** (links open on the client,
  externally) → no SSRF surface from user/Claude-supplied URLs.
- No third-party CDN/asset on any page that handles a signed URL; no token in
  logs/traces (ADR 0012 audit excludes secret URL params).
- **Runtime API role least-privilege (S6):** `GetObject/PutObject/DeleteObject`
  on `bucket/*` only — **no `ListBucket`**, no bucket-policy/ACL write (can't
  make objects public), no cross-bucket. GC uses a **separate delete-only**
  role. Deploy role binds credentials, doesn't read object data (ADR 0012).
- **E2E (if ADR 0015):** bodies are ciphertext before they reach storage; the
  server/bucket can't read them even on breach. Signed-URL scoping remains
  (defense-in-depth + prevents blob-existence/size probing across tenants).

## Limits

- All size checks are on **stored (post-encryption) byte length**, not
  plaintext (C3/E1 — AEAD adds nonce+tag; ciphertext > plaintext). Inline hard
  cap `body_md` ≤ **1 MB**; spill **triggers at a distinct lower threshold
  (~768 KB stored bytes)** so a body never straddles inline-vs-spill across the
  M0/M1 boundary. Max object ~25 MB (revisit with document upload). Brotli/gzip
  on transport; serve raw `text/markdown` (or ciphertext) — not JSON-wrapped.

## Open questions
- Provider choice (Vercel Blob vs S3/R2) — C3, weighted by agent-buildability.
- Presigned-upload (client→bucket) vs API-proxied upload — proxy is simpler/
  safer at low scale; presign saves API compute. Decide at C2/C3.
- Retention window for GC + whether spilled-version history is kept.
