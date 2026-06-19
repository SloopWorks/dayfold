# ADR 0017: E2E Key-Authenticity + Deploy Trust-Root Boundary (M1 security gate)

## Status

**Proposed** (2026-06-18). From the 6-agent whole-project review
(`research/project-review-2026-06.md`, security dimension). **Hard gate for M1
live-E2EE build** (ADR 0015). Not M0-blocking (M0 = single operator, single
key, no distribution, plaintext). Operator-gated (security posture).

## Context

The per-component reviews hardened each auth/E2E piece in isolation; the
system-level review found **three un-owned seams**, two of which silently
defeat the E2E value proposition, plus a trust-root concentration that
contradicts ADR 0015's blast-radius claim.

## Decision (proposed, M1)

1. **Key-authenticity binding (closes fake-key MITM).** Server-mediated
   key-wrap-on-approve (ADR 0015 scheme b) trusts the server to serve the
   correct member/CLI public key — a malicious/insider/compromised server can
   substitute its own key and obtain `FCK`. Bind the key to the approval
   ceremony the human already performs:
   - Show a **short-authentication-string / key fingerprint** on BOTH the
     approver and joiner screens (alongside the existing `user_code` + origin);
     the human confirms it matches.
   - For device-grant, **carry the CLI public key fingerprint in the QR /
     `user_code` derivation** (TOFU-on-scan — no server in the key trust path).
   - **Key-change detection** — clients pin peer keys and surface "X's key
     changed" (key-transparency-lite).
2. **Deploy trust-root boundary (fixes the concentration risk).** ADR 0012's
   full-prod deploy autonomy must NOT extend to the trust roots:
   - **Signing-key rotation / JWKS-allowlist changes and `FCK` custody are
     operator-gated**, NOT "cost-bearing config" the deploy agent may do.
   - **Tamper-evident, out-of-band audit** — log to an append-only sink the
     deploy credential cannot rewrite (separate account, write-only).
   - Treat the deploy/loop agent as **untrusted-input-exposed** (it reads
     GitHub/PR/issue/skill text → prompt-injection surface); any action
     touching auth/E2E/secrets/customer-data is operator-gated regardless of
     budget cap.
   - **Reproducible / signed client builds** + store review as the backstop
     that shipped app == reviewed source.
3. **Supply-chain integrity gate** in the ADR 0012 rails: **hash-pin +
   verify** (lockfile integrity, `--frozen-lockfile`, verify libsodium/
   SQLCipher checksums/signatures), **SBOM + provenance (SLSA/sigstore)** before
   any prod deploy, and an **allowlist of Claude skills / MCP servers** the
   deploy agent may load (no `@latest`, no open-registry pull at deploy). Pin
   crypto libs to **reviewed commits**, not floating `0.9.x`.
4. **`intents:resolve` (ADR 0016) enters the threat model at build:** a
   first-class IDOR-matrix resource (forge-for-another-member test); the loop
   credential is **family-scoped + on a controlled host (not the operator
   laptop)**; member free-text is **injection-isolated** before the reasoning
   context.
5. **Whole-system location-privacy posture stated honestly** (one place): live
   position = device-only (strong); place coords = E2E content readable by any
   approved member + present in the device cache + **count-leaking to the
   server**. Consider padded/fixed-size place rows to kill the count oracle.
6. **M0 carry-over (applies now):** the M0 household token gets an **absolute
   lifetime + mandatory rotation** (not just anomaly alerting); the M0 `FCK`
   (if ever introduced) lives in a keystore **distinct** from the token;
   use-anomaly alerts go **out-of-band**.

## Consequences

Positive: E2E becomes honest and robust against the exact threats it markets
against; the deploy-autonomy blast radius is bounded; supply-chain + injection
seams are owned.
Negative: adds a key-fingerprint step to invite/device-grant (small UX cost);
removes a few actions from the deploy agent's autonomy (operator-gated trust-
root changes); a provenance/SBOM gate to build.

## Revisit Trigger

M1 E2E build begins (this ADR must be Accepted first); a new key-holder
credential is added; or a supply-chain/injection incident.
