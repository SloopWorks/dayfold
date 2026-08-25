# Contributing to Dayfold

> Staged alongside the licensing posture (ADR 0032, pending counsel). Contributions
> open once the license is effective — see [`LICENSING.md`](LICENSING.md).

Thanks for your interest. A few ground rules.

## License of contributions (inbound = outbound)

Dayfold is a **per-component** monorepo (see [`LICENSING.md`](LICENSING.md)). Your
contribution is licensed under the license of the component you touch:

- `apps/cli`, `apps/client`, `apps/androidApp`, `packages/schema` → **Apache-2.0**
- `apps/api` (server) → **AGPL-3.0-or-later**

We do **not** require a CLA. There is no copyright assignment; you keep your copyright
and license the contribution under the component's license.

## Developer Certificate of Origin (DCO)

Every commit must be **signed off** to certify the DCO below. Add a `Signed-off-by`
line with your real name and email:

```
Signed-off-by: Jane Developer <jane@example.com>
```

Git does this for you with the `-s` flag:

```
git commit -s -m "your message"
```

The email must match the commit author. By signing off you certify:

```
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.
1 Letterman Drive
Suite D4700
San Francisco, CA, 94129

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

## Practical notes

- **AGPL boundary:** never bundle or statically link `apps/api` (AGPL) code into the
  Apache client/CLI. The boundary is the network/process line.
- Match the surrounding code's style; keep changes focused.
- Run the relevant tests before opening a PR (`apps/api`: `npx vitest run`;
  `apps/cli`: `./gradlew test`; `apps/client`: `./gradlew :client:desktopTest`).
- Security issues: follow [`SECURITY.md`](SECURITY.md) — do **not** open a public issue.
