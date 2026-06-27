# Licensing

> **STATUS — DRAFT, NOT YET EFFECTIVE.** These license files are *staged* per the
> recommendation in **ADR 0032** (Proposed, `[pending-counsel]`). They take effect
> only when (a) counsel confirms and (b) the operator accepts ADR 0032 and merges
> this. Until then the repository remains **all-rights-reserved (unlicensed)** and
> must not be published. The **BLOCKING pre-flight** (full-history secret scan →
> rotate → push protection; ADR 0032 §5) must pass *before* any public push.

Dayfold is a monorepo with a **per-component** license set (ADR 0032 §2). GitHub's
root-`LICENSE` detection does not badge a mixed monorepo cleanly, so this file is the
authoritative map.

Copyright © 2026 SloopWorks. *(Operator to confirm the legal entity / copyright
holder before going public.)*

## Per-component license

| Path | License | SPDX | Why |
|---|---|---|---|
| `apps/cli` | Apache License 2.0 | `Apache-2.0` | Separate process over the API; max adoption + patent grant. |
| `apps/client`, `apps/androidApp` | Apache License 2.0 | `Apache-2.0` | Showcase/adoption + patent grant. |
| `packages/schema` (codegen/contracts) | Apache License 2.0 | `Apache-2.0` | Must flow into both client and server → permissive, compatible both directions. |
| `apps/api` (server) | GNU AGPL v3.0-or-later | `AGPL-3.0-or-later` | §13 network-copyleft — optics + insurance vs a same-stack competitor; the sole copyright owner stays free to run/sell a closed SaaS. |
| The future authoring "brains" (G1) | **Closed** (separate private repo) | — | Closed by *not publishing*, not by a license. Not in this repo. |

- Root **`LICENSE`** = Apache-2.0 (the predominant + public-facing components).
- **`apps/api/LICENSE`** = AGPL-3.0-or-later overrides the root for the server.

## Why a mixed set is legally clean (ADR 0032)

Apache-2.0 → AGPL-3.0 is one-directional compatible. An Apache-licensed client/CLI
that talks **HTTP** to the AGPL server is **aggregation**, not a derivative work.
**Never bundle or statically link AGPL server code into the Apache client/CLI** — keep
the boundary at the network/process line (which the architecture already enforces:
TS server, Kotlin CLI/client, separate builds).

## Monetization posture

Hosted SaaS is the sole paid path (ADR 0032 §3); the AGPL server keeps the owner free
to run a closed hosted offering. The permissive CLI/client is a showcase/adoption +
acquisition lever, not a revenue lever. GitHub Sponsors is a thin secondary; the G1
brains stay closed for optionality.

## Contributions

Inbound = outbound under the license of the component touched (Apache for client/CLI/
schema, AGPL for `apps/api`), certified by the **Developer Certificate of Origin**
(`Signed-off-by`). See [`CONTRIBUTING.md`](CONTRIBUTING.md). A **CLA would be required
only if a future dual-license / commercial-sale is intended** — decide that *before*
accepting contributions (relicensing contributed code afterward needs re-consent).

## References

- ADR 0032 — `adr/0032-licensing-open-source-posture.md` (the decision; pending counsel).
- Strategy report — `research/2026-06-25-licensing-open-source-strategy.md`.
- ADR 0031 — `adr/0031-cli-distribution-homebrew-tap.md` (this closes its license gate).
