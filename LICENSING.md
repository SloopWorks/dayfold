# Licensing

**Dayfold is licensed under the Apache License 2.0, with one exception: the
server (`apps/api`) is not licensed.** Contributions are not currently accepted.

Copyright © 2026 SloopWorks.

## The map

| Path | Licence | SPDX |
|---|---|---|
| Repository root — everything not listed below | Apache License 2.0 | `Apache-2.0` |
| `apps/cli` — the `dayfold` content-authoring CLI | Apache License 2.0 | `Apache-2.0` |
| `apps/client`, `apps/ui`, `apps/androidApp`, `apps/iosApp` | Apache License 2.0 | `Apache-2.0` |
| `packages/schema`, `packages/linkrules`, `packages/routine-schema` | Apache License 2.0 | `Apache-2.0` |
| **`apps/api` — the server** | **No licence granted.** All rights reserved. | — |
| `third_party/` | Governed by its own upstream terms, not this repository's. | — |

The root [`LICENSE`](LICENSE) is the Apache-2.0 grant.
[`apps/api/LICENSE`](apps/api/LICENSE) states the carve-out explicitly, so a
reader who lands in that directory sees it without having to find this file.

## Why the server is carved out

The licence for the server is a genuinely separate decision from the client and
CLI, and it is asymmetric: **a permissive grant cannot be withdrawn.** Publishing
`apps/api` under Apache-2.0 today would make that snapshot permanently forkable,
so choosing AGPL later would leak. Choosing AGPL later after granting *nothing*
costs nothing.

ADR 0032 proposes AGPL-3.0-or-later for the server, on the reasoning that §13
network-copyleft is cheap insurance while the sole copyright owner stays free to
run a closed hosted service. That ADR is **Proposed, not accepted**, and it also
opens questions — how §13 interacts with a hosted offering once there are outside
contributors — that are not worth answering before they are real.

So the server stays unlicensed until the question is live. "Published for
inspection, no rights granted" is the honest description of its status, and it is
the status the whole repository had before this file existed.

## Contributions

**Not currently accepted.** There is no CLA and no DCO, because there is nothing
for them to govern yet.

This is deliberate and reversible. Accepting contributions is the step that is
*not* easily reversible: under inbound=outbound with a DCO, contributed code
cannot be relicensed without re-consent from every contributor, which forecloses
a future dual-licence or sale without their agreement. That decision should be
made when someone actually wants to contribute — not pre-emptively.

If you want to propose a change, open an issue first.

## Trademark

Apache-2.0 §6 grants no trademark rights. The Dayfold and SloopWorks names and
logos are not licensed by the grant above.

## What this file is not

It is not legal advice, and it does not restate the licence. Where this summary
and [`LICENSE`](LICENSE) disagree, `LICENSE` governs.

## References

- [`LICENSE`](LICENSE) — the Apache-2.0 text.
- [`apps/api/LICENSE`](apps/api/LICENSE) — the server carve-out.
- `adr/0032-licensing-open-source-posture.md` — the broader posture
  (Apache + AGPL + DCO). **Proposed**; this file implements a deliberately
  narrower first step, not that ADR.
- `adr/0031-cli-distribution-homebrew-tap.md` — CLI distribution; its licence
  gate is what this file closes for `apps/cli`.
- `research/2026-06-25-licensing-open-source-strategy.md` — the strategy report.
