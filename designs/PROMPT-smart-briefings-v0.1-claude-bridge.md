# Claude Design prompt — V0.1 Claude Bridge operator pilot

**Use this entire file in a fresh Claude Code session with Claude Design only
after the synthetic compatibility spike is recorded.** Read `CLAUDE.md` first.
This creates the ADR 0008 design gate; it does not authorize app code, deployment,
OAuth, external account setup, Terms acceptance, private data, or spend.

## Assignment

Create a spike-informed hi-fi suite for **Claude briefing — operator pilot**. The
owner manually starts a run in Claude. Claude uses the owner's Gmail connection
and a narrow Dayfold connector. Dayfold receives zero or one private-card
proposal and requires in-app human acceptance before named adults can see it.

Create:

```text
designs/smart-briefings-v0.1/
  Index.dc.html
  Journey.dc.html
  Recovery.dc.html
  Browser-Approval.dc.html
  Smart-Briefings-V01-Phone.dc.html
  support.js
  NOTES.md
```

Use `designs/routine-integration/` as the visual source, but fork the experience.
Reuse shared stateless visual components where practical; fake/live state and
actions remain separate. Do not modify the broad existing gallery.

Read, in order:

1. `CLAUDE.md`
2. `research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md`
3. `specs/smart-briefings-v0.1/system-design.md`
4. Proposed `adr/0071-self-managed-claude-bridge-v0.1.md`
5. `designs/routine-integration/NOTES.md`
6. `designs/routine-integration/Smart-Briefings-Phone.dc.html`
7. the existing Settings/Auth phone designs
8. ADRs 0008, 0009, 0029, 0030, 0053, and Proposed 0062

If the spike report does not exist or leaves a load-bearing provider behavior
unknown, stop and list the missing evidence. Do not fabricate Claude UI, install
URLs, supported clients, deep links, or return behavior.

## Product truth

- This is an operator pilot, not a paid hosted release.
- The user's Claude account supplies inference and owns Google OAuth. Dayfold does
  not receive a Google or Claude subscription credential.
- The owner starts every run manually. No schedule, next-run time, or task cleanup.
- Gmail + one selected Dayfold Hub are the only sources.
- Requested source preset: Dayfold asks Claude to search no more than 100 results
  from the prior 14 days, excluding spam and trash. Dayfold cannot enforce or
  verify that Gmail boundary. No saved free-text query or label control.
- One installation per family; connecting/source/approving/reviewing adult is the
  same Owner.
- A run yields zero or one `create_card` proposal. Accept or Reject only; no Edit.
- The draft is **visible only to you in Dayfold**, but server-readable by Dayfold
  M0. It is not “zero knowledge” or end-to-end encrypted.
- Exact adult recipients are selected only at acceptance. The source owner is
  mandatory. Setup asks only for the destination Hub.
- The Dayfold connector can read that Hub and submit a private proposal. It cannot
  publish, edit, delete, share, invite, manage people, fetch URLs, or call Google.
- Claude's Gmail connection may include write tools. Dayfold cannot inspect,
  narrow, or revoke that Google grant. Provider-level write behavior must have
  passed the compatibility gate.
- Gmail attachment contents and original-source links are unavailable. Canonical
  copy is **Attachment content not read** and **Original link unavailable**.
- Dayfold can verify only its grant and connector calls. Gmail results are
  **reported by Claude**.
- Revoking Dayfold access does not disconnect Google or remove the connector in
  Claude.
- Private-data dogfood is blocked until an eligible no-training authority exists.
  Dayfold cannot verify a consumer training toggle.

## Tone and visual system

Use the existing Dayfold Material 3 Expressive system: Outfit headings, Figtree
body, warm surfaces, coral/teal/violet roles, dark mode, clear audience/provenance
chips, and restrained Claude identification. The flow is a calm handoff, not
developer setup. Avoid celebration, urgency, fear copy, OAuth/MCP jargon,
security theater, and claims such as “completely private,” “zero retention,”
“AI never sees,” or “one tap.”

Claude-owned screens are dashed neutral **Outside Dayfold** beats on the journey,
not fabricated provider UI.

## Normative state model

```text
Off
 -> Hub
 -> Privacy review
 -> Continue in Claude
 -> Dayfold browser/app approval
 -> Ready for first run
 -> Run in progress
 -> Draft | No changes | Failed
 -> Ready · Manual
 -> Revoking -> Revoked
```

- OAuth approval → `Approved · finishing connection`; successful token exchange →
  `Ready for first run`.
- First authenticated Dayfold connector call → `Run in progress`.
- Finish receipt → `Ready · Manual` plus result.
- App/Claude return alone causes no promotion.
- Never label this manual connector **Active**.

## Canonical phone journey

### 1. Account row and owner entry

```text
Claude briefing
Off
Use Claude to prepare a private Dayfold draft from recent email.
```

Owner primary: **Set up with Claude**. Adult read-only state names the Owner who
manages it and has no setup/review/revoke controls.

Support copy:

```text
You start each run in Claude. Claude prepares at most one private Dayfold card for
you to review. Nothing runs on a schedule in this pilot.
```

### 2. Hub

Heading: **Where could an accepted card appear?**

Select one visible Hub; restricted Hubs are off by default and visibly locked.
No recipient picker appears here.

```text
Every proposal starts visible only to you in Dayfold. You choose named adults only
when you accept it. Sharing the Dayfold card never shares your Gmail access.
```

Show the requested source preset as read-only:

```text
Claude is asked to search Gmail: no more than 100 results from the last 14 days,
excluding spam and trash. Dayfold cannot verify that search and does not store a
Gmail query.
```

### 3. Two connections and privacy review

```text
Google -> Claude             Claude -> Dayfold
Your email connection       One Hub + private proposal permission
Managed in Claude           Approved and revoked in Dayfold
```

Rows:

1. **Claude reads** — matching Gmail results and permitted Hub context.
2. **Anthropic processes** — plaintext under the eligible account/contract.
3. **Dayfold receives** — one private proposal and minimal run status.
4. **You decide** — inspect it, then accept for named adults or reject.

Required disclosure:

```text
Claude reads the Gmail and Dayfold information you ask it to use. Anthropic
processes it under your Claude account terms. Dayfold receives the proposal Claude
submits. It may contain sensitive or third-party information and is visible only
to you in Dayfold until you choose named recipients.
```

Add: remove information you should not send to Anthropic, Dayfold, or family
members, including child, correspondent, health, and financial information.

For synthetic setup show **Test data only**. For a future eligible private-data
pilot show the exact ratified account/contract evidence; never use a consumer
toggle as a verified Dayfold control.

### 4. Handoff

Heading: **Continue setup in Claude**

Use only the install/open/copy behavior proven by the spike. Include copy success
and copy failure. Label external actions **opens Claude**. Returning without OAuth
completion shows **Setup is not complete** with one next action.

### 5. Dayfold approval

Heading: **Claude wants to connect to Dayfold**

Show family, source owner, exact Hub, **Read this Hub**, **Submit one private
proposal**, expiry/revocation, and explicit cannot-publish/delete/share/manage
people. Actions: **Deny**, **Allow**. Do not show Google permissions here.

### 6. Ready and run

After OAuth:

```text
Dayfold access approved
Ready for first run
Start the briefing in Claude.
```

During the first connector call:

```text
Run in progress
Dayfold connector received a request.
Gmail result: no report from Claude yet.
```

A return without finish says **No completed run was reported**. A successful
finish shows requested versus Claude-reported outcomes separately.

### 7. Draft

Heading: **Briefing ready to review**

Show exactly one proposal with:

- bounded title/body;
- selected Hub;
- **From Gmail · prepared by Claude**;
- **Visible only to you in Dayfold**;
- **Attachment content not read**, if reported;
- **Original link unavailable**.

Actions: **Accept**, **Reject**. Accept opens recipient confirmation. The source
owner is selected and locked; additional eligible adults are optional. Repeat
that recipients see the Dayfold card, not Gmail.

Draft body variants: loading, unavailable offline, expired while open, decided on
another device, and cleared on background/family switch. Disable decisions without
the body. A refreshed Account/Today surface shows a pending-draft badge; do not add
notifications.

### 8. No changes and failed

No changes is calm success:

```text
Run complete — no new card
Claude reported no useful Gmail updates for this run.
```

Never say “Gmail needs reauthorization.” Say **Claude reported that it could not
use Gmail**. Primary recovery is **Continue in Claude** with copied steps as
fallback. Raw provider errors are never displayed.

### 9. Ready · Manual

Show last finish time, result, requested versus reported source status, selected
Hub, source owner, pending badge, **Continue in Claude**, **Copy run instruction**,
and **Manage Dayfold access**. No schedule or Active chip.

### 10. Reject, cancel, and revoke

Reject/cancel copy must match ratified retention:

```text
Removed from review now. Dayfold deletes the proposal body from its servers within
{proposalPurgeHours} hours.
```

Revoke sheet explains that new Dayfold reads/proposals stop after confirmation;
accepted cards remain; Google and the Claude connector are unchanged. Include
pending, failed, confirmed, owner-role loss/family departure/Hub archive auto-
revocation, and wrong-account recovery.

## Responsive browser approval

`Browser-Approval.dc.html` must include waiting, app unavailable, approved and
redirecting, denied, expired, and safe manual fallback. Show short code + deep link
as text alternatives to QR. The code/deep link pairs Claude's pending authorize
request with the prepared app enrollment; the app never receives the browser's
opaque polling secret. Never encode/show that secret, the database attempt ID, or
an OAuth code/token in human UI/QR/app links. The spike determines return behavior
and whether the MCP URL must be copied.

## Recovery layout families

Use four reusable families, not a bespoke screen per backend enum:

1. **Setup:** Claude unavailable/not installed, feature on another surface, copy
   failed, OAuth denied/expired/incomplete, wrong account.
2. **Run:** approved but no run, in progress, no finish, Claude-reported Gmail
   unavailable, proposal rejected by policy.
3. **Draft/accept:** loading, offline body unavailable, expired, already decided,
   conflict, app not refreshed, retry-safe accept failure.
4. **Revoke:** confirm, pending, failed, confirmed, automatic fail-closed revoke.

Every state maps a closed Dayfold code to one next action. Preserve accepted
Dayfold content through connector errors.

## Deliverable inventory and QA

Design these ten happy-path phone views plus the four recovery families:

```text
owner-entry
hub-selection
privacy-review
claude-handoff
dayfold-approval
ready-first-run
run-in-progress
private-draft
audience-confirm
ready-manual
```

Also include owner vs Adult and source owner vs recipient read-only variants.
Provide light/dark examples for every layout family, not every enum permutation.
QA scenes:

- 320 dp + 200% text: privacy, draft, revoke;
- compact and 700 dp wide: Ready · Manual;
- RTL: Hub, privacy, approval;
- reduced motion: run and revoke pending;
- source owner vs recipient provenance/source-link behavior;
- browser approval at 320 px, 200% zoom, desktop wide, and high contrast.

## Accessibility acceptance

- 48×48 dp targets and semantic selection/state descriptions.
- Wizard announces **Step N of N**; never color-only.
- Keyboard-only browser operation, landmarks, logical focus, visible focus, 200%
  zoom, and high contrast.
- QR always has short-code/deep-link text alternatives.
- Polling announces meaningful transitions only; no timer/countdown chatter.
- External return restores focus to the initiating control or new status heading.
- Copy success/failure is announced and not toast/color only.
- Modal background is accessibility-hidden; focus is trapped/restored.
- At 200% text paired actions stack without clipping.
- RTL preserves reading order; reduced motion uses static progress.
- Draft heading and audience are announced before Accept.
- Accept/Reject are disabled when the sensitive draft body is absent.

## `NOTES.md` requirements

Record reused components, intentional scope cuts, spike evidence references,
unresolved constants, the exact transition table, Compose/browser component map,
draft discovery, responsive/a11y checklist, and an explicit statement that mockups
do not prove a live connector.

End with an ADR 0008 sign-off checklist for:

1. manual Claude-only and operator-pilot positioning;
2. two-connection/provider-write honesty;
3. requested source preset and one-Hub/one-card scope;
4. no-training and sensitive third-party disclosure;
5. lifecycle and provider-reported wording;
6. source-owner-private review and exact restricted audience;
7. cancellation/revocation/deletion consequences;
8. browser approval and accessibility.
