# Scoped CLI/Device Tokens — Design

**Date:** 2026-07-11
**Status:** Approved (brainstorm complete). Realizes ADR 0029's deferred "per-hub picker" (the `credential_grants` migration comment: *"the per-hub picker lands later"*; `DeviceApprovalScreens.kt` scope row: *"informational interim per ADR 0029 (per-hub selection rides the content slice)"*). Composes with ADR 0053 (per-hub roles) — this is the credential-scope (axis 3) least-privilege story, distinct from the member-role gate (DC3) which already enforces writes.

## Why (and why not "Full")

The realistic threat in a single-operator household app is a **leaked or over-broad token**, not a member self-escalating (DC3 already blocks a Viewer's write server-side). Today every credential — including a CLI/device grant an agent holds — is minted with blanket `content:{read,write,delete}` over *all* hubs. This delivers ADR 0029's actual intent: **least-privilege CLI/agent tokens** scoped to specific hubs.

We explicitly rejected the "Full" option (narrow the blanket `content:write` and sync `hub:<id>:write` to each user's per-hub role across all their credentials): it mostly *duplicates* DC3, forces changes to the operator/M0 authoring path (authoring-lockout risk), and adds per-`user`×`hub` grant-sync correctness hazards — high risk to add a redundant check. This design touches **only** the device-grant mint path; app-session credentials and the blanket-authoring path are unchanged.

## Scope vocabulary (existing, ADR 0029)

`credential_grants(credential_id, scope)`; scope strings are global `content:read|write|delete` or resource-qualified `hub:<id>:read|write`. `scopeAllows(grants, resource, action)`: a global `content:<action>` covers any content resource; else exact `<resource>:<action>`. `grantedHubIds(grants, action)` returns the hub-id set for LIST filtering (null = global). All already built.

## Design

### Flow
Unchanged RFC 8628 device grant (CLI `POST /device/authorize` → operator approves in-app → CLI polls `POST /device/token` → `redeem` mints the credential). The single addition: **the approver chooses the grant scope at approve time.** Default = **Full content** (today's blanket, backward-compatible). New = **restrict to selected hubs**.

### API
1. **Migration** (`00NN_device_grant_scopes.sql`): `ALTER TABLE device_authorizations ADD COLUMN granted_scopes text[];` (nullable; `NULL` ⇒ blanket default — preserves every in-flight and future unscoped grant).
2. **Approve** (`POST /families/:fid/device/approve`): accept an optional body scope selection:
   - absent or `{"scope":"full"}` ⇒ `granted_scopes = NULL` (blanket).
   - `{"scope":"hubs","hubs":["H1","H2"]}` ⇒ validate each hub id (charset + exists in this family + caller may see it), materialize to `["hub:H1:read","hub:H1:write","hub:H2:read","hub:H2:write"]`, store on `granted_scopes`. Empty/invalid hub list ⇒ 400.
3. **Redeem** (`auth/device.ts`): read the row's `granted_scopes`. If non-null, mint the `cli` credential with those scopes (`credentials.scopes` + `credential_grants` rows) instead of the blanket `{content:read,write,delete}`. If null, today's blanket (unchanged).

### Client / UX
The existing informational scope row (`DeviceApprovalScreens.kt`) becomes a picker:
- **Full access** (default, selected) — "This device can read and author all your family's content."
- **Only these hubs** — reveals a multi-select of the family's hubs (`state.hubs`); at least one required to approve in this mode.
Threads the selection through `onApproveDevice` → `AuthEngine.approveDevice` → `AuthClient.approveDevice` (new optional scope arg) → the approve request body.

### Security semantics
A hub-scoped token: reads/writes **only** its granted hubs (content reads auto-filter via `grantedHubIds`); **cannot** create new hubs/cards (no global `content:write`); cannot touch other hubs. Least-privilege by construction. Composes with DC3 — a scoped token still also passes the member-role gate. Existing tokens and the app-session/M0 authoring path are untouched (zero authoring-lockout risk).

## Units & boundaries
- `auth/device.ts` `redeem` — mints from `granted_scopes ?? blanket`. Pure change of the scope list source.
- app.ts approve route — parses + validates the scope selection, computes grant strings, stores.
- `scope.ts` — unchanged (vocabulary already supports it); a small `hubGrantsFor(hubIds)` helper may live here.
- client `AuthClient`/`AuthEngine` — approve gains an optional scope arg (default full).
- `DeviceApprovalScreens.kt` — the scope picker.

## Testing
- **redeem**: `granted_scopes` set ⇒ credential + grants are exactly the scoped set; null ⇒ blanket (back-compat).
- **enforcement**: a scoped token → `requireScope("hub:H1","write")` true; `requireScope("hub:H2","write")` false; `requireScope("content","write")` false (can't create); reads filter to granted hubs.
- **approve validation**: unknown/other-family hub → 400; empty hub list in "hubs" mode → 400.
- **UI**: Full is the default; picking "Only these hubs" + selecting a hub emits the scope to `onApproveDevice`; approve disabled in hubs-mode with no hub selected.

## Non-goals
- No narrowing of the blanket `content:write` for app sessions or existing credentials (that was the rejected "Full" option).
- No sync of grants to `resource_visibility.role` changes.
- No `hub:<id>:delete` scoping (delete is ADR 0038/0039-reserved; no route gates it yet).
- Re-scoping an already-issued token (revoke + re-issue instead).
