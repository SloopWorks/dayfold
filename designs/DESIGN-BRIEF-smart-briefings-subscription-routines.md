# Design Brief / Prompt — Smart Briefings via the User's AI Subscription

**Hand this whole file to a fresh Claude Code (Claude Design) session.** It is
self-contained. Use the `frontend-design` skill. This is the ADR 0008 design
gate for Dayfold's AI-routine enrollment, authorization, status, review, and
revocation surfaces.

Authoritative Dayfold references:

- `adr/0008-design-first-hifi-mockups.md` — hi-fi design + operator sign-off
  precede deeper planning/build.
- `adr/0009-design-system-m3-expressive-adaptive.md` and
  `designs/Design-System.dc.html` — visual system.
- `designs/Family AI dashboard design brief/designs/Settings-Phone.dc.html` —
  Settings hierarchy, spacing, navigation, and phone chrome.
- `designs/Family AI dashboard design brief/designs/Auth-Phone.dc.html` —
  provider/OAuth ceremony and approval patterns.
- `adr/0029-cli-token-resource-scoped-grants.md` — per-hub read/write grants.
- `adr/0030-per-member-hub-and-card-visibility.md` — restricted-resource
  visibility must survive every routine flow.
- `designs/two-way/` — review/staged-change interaction patterns.

The existing routine architecture draft and Proposed ADR 0061 predate the
operator clarification that **the user's own Claude or ChatGPT subscription
must perform the work**. Where those drafts imply Dayfold-funded model API
calls, Dayfold-owned scheduling, or a purely K1/K3 execution loop, this brief's
product framing wins for the UX. Do not resolve backend architecture in the
mockups.

---

## 0. Assignment

> **Design the complete hi-fi setup and control flow for Dayfold Smart
> Briefings.** A family owner uses their existing paid Claude or ChatGPT
> subscription to run a provider-owned scheduled routine. The provider owns the
> schedule, inference usage, and email/calendar/document connections. Dayfold
> supplies an OAuth-authenticated connector, hub-scoped context and update
> tools, review controls, run receipts, and revocation.
>
> Produce interactive HTML/CSS `.dc.html` prototypes matching Dayfold's
> Material 3 Expressive system. Mobile first (~390–430 px), **light + dark for
> every Dayfold screen**. Map components to Compose M3 names. Visuals only — no
> app code, provider API calls, or real secrets.

The experience must feel like a calm guided handoff, not developer setup. A
normal user must never install the Dayfold CLI, copy an API key, edit JSON,
understand OAuth, or configure cron. The default path may leave Dayfold once for
an explicit provider confirmation; do not hide that boundary.

## 1. Product truth that the UI must preserve

### 1.1 The subscription is the compute plane

- Claude or ChatGPT runs the routine under the **user's subscription and plan
  limits**. Dayfold does not invoke a model API or sell model usage in this
  flow.
- User-facing provider choices are **Claude** and **ChatGPT**. Do not call the
  OpenAI option “Codex” in primary copy: connected-source family briefings fit
  ChatGPT Scheduled Tasks. A technical note may say that Codex is part of the
  broader ChatGPT subscription, but it is not the setup destination here.
- Gmail, Calendar, Drive, and other source connections are selected and
  authorized in Claude/ChatGPT. Dayfold does not receive those account
  passwords or source OAuth tokens.
- Dayfold exposes only the selected Dayfold hubs and allowed operations through
  its connector. Default authority is **read selected hubs + submit drafts**.
  Automatic publishing is a later, separate opt-in.

### 1.2 The provider boundary is real

- Setup is a **two-surface ceremony**: Dayfold prepares and authorizes; the user
  finishes inside Claude or ChatGPT.
- Dayfold cannot currently use provider OAuth to discover, enumerate, create,
  inspect, pause, edit, or delete a consumer's routines/tasks.
- The provider initiates OAuth **to Dayfold** when connecting the Dayfold
  connector. That grant lets the provider call Dayfold; it does not let Dayfold
  manage the provider account.
- Activation is confirmed by the first idempotent `dayfold_run_finish` receipt
  for the enrollment attempt. The receipt proves that the routine used its
  Dayfold grant, completed a low-risk dry run, and reported structured source
  outcomes. Replaying the same attempt/run returns the original receipt; there
  is no separate `complete_enrollment` tool. Until that receipt exists, the
  honest state is **Waiting for {provider}**, not “Connected.”
- Schedule controls remain in the provider. Dayfold may show the schedule the
  user requested during setup, labeled **Requested schedule · managed in
  Claude/ChatGPT**, and the last observed run. Do not claim an authoritative
  next-run time.

### 1.3 Privacy and encryption copy is load-bearing

- Say plainly: **“Selected Dayfold content is shared with {provider} so it can
  prepare this briefing.”** The provider processes plaintext and its retention
  terms apply.
- Say plainly: **“Your connected email and files stay connected to
  {provider}. Dayfold receives only the Dayfold updates the routine submits.”**
- Do not claim the workflow is end-to-end private from Claude/OpenAI. Do not use
  zero-knowledge, “AI never sees your data,” or equivalent copy.
- Enrollment creates a revocable **AI access grant** scoped to chosen hubs and
  operations. The mainstream screen uses plain language; a secondary
  **Technical details** sheet may expose provider, grant ID, key fingerprint,
  scopes, creation time, and last use.
- Only a family **Owner** may enable, widen, or revoke AI access. An Adult may
  see a calm read-only summary and who manages it, but no authority controls.
- Respect restricted hubs. Never default a restricted hub on merely because an
  owner is configuring the routine.

### 1.4 Connected sources are a separate provider-owned authorization

- Dayfold may ask which sources the owner **intends** to use (Gmail, Google
  Calendar, Google Drive, or none), so it can prepare the setup instruction and
  privacy summary. This is not a Google connection ceremony inside Dayfold.
- The user connects Google directly inside Claude or ChatGPT and completes
  Google's OAuth consent there. Dayfold never receives the Google refresh token
  and cannot inspect or revoke that provider-to-Google grant.
- **Claude:** Google Workspace connectors are managed in Claude's connector UI;
  Team/Enterprise may require an organization owner to enable them first. Claude
  routines can include connected connectors and, during routine setup, may include
  more connectors than this Dayfold workflow needs. The setup must tell the user
  to keep only the selected sources + Dayfold connector.
- **ChatGPT:** apps are connected from Settings → Apps (or the app directory),
  followed by the provider's OAuth/sync flow. Availability and actions may depend
  on plan, geography, and workspace/admin settings. Google Drive sync may remain
  in progress after OAuth; do not equate “authorized” with “fully indexed.”
- Dayfold cannot query either provider for a reliable connector inventory. Keep
  two statuses distinct:
  - **Requested sources** — what the user chose in Dayfold.
  - **Observed sources** — a source whose low-risk query succeeded and was
    reported by the routine, with a last-observed time. A successful query that
    returns zero items still counts as observed; content presence is not an auth
    test.
- Do not show a green Connected check merely because the user returned from the
  provider. A source becomes observed only after a real low-risk read succeeds.
- The provider's Google permission grant may be broader than Dayfold's desired
  read-only workflow. Dayfold cannot narrow that external grant. The routine
  instruction forbids sending email, changing calendar events, editing Drive, or
  other source-system writes in the first tier, while the UI tells the user to
  review provider permissions and include only necessary connectors.

Provider facts are current as of 2026-08-07 and are design constraints, not UI
copy to reproduce verbatim:

- Claude Google connectors:
  `https://support.claude.com/en/articles/10166901-use-google-workspace-connectors`
- Claude routine connector selection:
  `https://code.claude.com/docs/en/routines#connectors`
- ChatGPT apps connection flow:
  `https://help.openai.com/en/articles/11487775-apps-in-chatgpt`
- ChatGPT Google Drive setup/sync:
  `https://help.openai.com/en/articles/10948259-google-drive-synced-connectors-self-service-setup`

## 2. Default end-to-end journey

Design this exact happy path as both individual phone screens and one annotated
journey board:

1. **Entry:** Settings → Devices & Connections → **Smart Briefings**, initially
   Off. Value line: “Use your existing AI subscription to keep Dayfold useful.”
2. **Choose provider:** Claude or ChatGPT. Each card says **Uses your existing
   subscription** and **Setup finishes in {provider}**. Do not show a discovered
   account, plan, task list, or connection status yet.
3. **Choose source intent:** Gmail, Calendar, Drive, or **Dayfold only**. Each
   row says **Connected later in {provider}**. Selection prepares instructions;
   it does not authorize Google or claim availability.
4. **Choose intent:** Daily family briefing is the hero preset. Let the owner
   choose a human schedule such as “Weekdays around 6:30 AM”; label it a setup
   request that will be confirmed in the provider.
5. **Choose Dayfold access:** select hubs; then choose **Read + submit drafts**
   (default) or read-only. Automatic publishing is visible but locked behind
   “Available after you review initial runs,” not silently enabled.
6. **Review privacy:** a concise data-flow summary names the provider, requested
   sources, selected hubs, permitted actions, the two separate OAuth grants,
   plaintext disclosure, and one-tap Dayfold revocability. Primary CTA:
   **Continue to {provider}**. Secondary: Back.
7. **Prepared handoff:** Dayfold creates a short-lived one-time enrollment and
   provider-specific setup instruction. Dayfold opens the provider app/web when
   possible and also offers **Copy setup instructions**. Never expose a
   Dayfold refresh token or encryption key.
8. **External provider beat:** show only an annotated neutral external-step card
   in the journey board, not a fabricated pixel-perfect Claude/ChatGPT screen.
   The user connects the Dayfold plugin, connects each requested Google source
   through provider-owned OAuth, removes unrelated connectors, reviews the
   generated routine/task, and explicitly confirms its schedule.
9. **OAuth return to Dayfold:** “Claude/ChatGPT wants to connect to Dayfold.”
   Show provider identity, family, exact hub grants, read/draft authority, expiry
   or revocation language, Deny, and Allow. This is a Dayfold-owned screen.
10. **Waiting:** after returning to Dayfold, show **Waiting for {provider} to
   finish setup** with separate Dayfold-connector, requested-source, schedule,
   and first-safe-read steps. Provide **Open
   {provider}**, **Copy instructions**, and **Cancel setup**. Do not spin forever;
   pairing expiry is visible.
11. **Verify:** the provider routine performs a low-risk dry run and calls
    `dayfold_run_finish` with the enrollment-attempt ID, run outcome, and one
    closed-enum result per requested source. A successful zero-result query is a
    success. For **Dayfold only**, a successful Dayfold context read + validation
    is sufficient; do not wait for a nonexistent external source.
12. **Active:** an idempotent successful finish receipt changes the status to
    **Active · Review drafts first**. Show provider, requested schedule, selected
    hubs, requested vs observed sources, last observed run, recent drafts,
    **Manage sources in {provider}**, and **Manage schedule in {provider}**. If
    only some requested sources succeeded, use **Needs source attention** and let
    the owner either repair them or explicitly **Continue with available
    sources** in review-only mode. Never silently treat a partial source set as
    complete.
13. **First result:** a quiet in-app receipt either summarizes proposed changes
    per hub with provenance and Accept / Reject / Review detail, or says **No new
    updates this time**. Reuse the `designs/two-way/` visual grammar; do not
    invent a second mutation UI or present an empty changeset as failure.

### 2.1 Provider-specific connector beats for the journey board

These are annotated external beats, not Dayfold-owned pixel mockups:

- **Claude:** open Customize/Settings → Connectors; connect the Google account;
  enable Gmail, Calendar, and/or Drive requested by the user; return to routine
  creation; include Dayfold + only those requested source connectors; remove
  unrelated connectors; save/run the routine. Include the possible
  **Ask your organization owner** branch.
- **ChatGPT:** open Settings → Apps/app directory; connect Gmail, Calendar,
  and/or Google Drive; complete Google OAuth and sync if offered; attach the
  requested apps + Dayfold plugin to the Scheduled Task; confirm the task. Show
  Drive's possible **Syncing** state and the possible **Unavailable or admin
  disabled** branch.
- In both branches, the final proof is not a checkbox the user taps. It is a
  low-risk first run that successfully reads each selected source and reports
  the observed set to Dayfold.

### 2.2 Recovery contract — every error ends in an honest next action

Design from a structured Dayfold status contract, never from raw provider error
text. Every failed or incomplete operation has:

- `phase` — `enrollment`, `provider_handoff`, `source_probe`, `context_read`,
  `analysis`, `validation`, `stage`, `apply`, `finish`, or `revoke`;
- a closed `reason` code, never a free-text provider response;
- `retryability` — automatic, user action required, or final;
- one recommended action — Retry, Resume in provider, Manage source, Review
  conflict, Continue with available sources, or Contact support;
- a content-free support code derived from an enrollment-attempt/run trace ID;
- the last successful receipt and content, which remain visible after activation.

Recovery behavior:

- Treat provider cancellation/denial, wrong Dayfold family or provider context,
  lost app return, duplicate/late callback, expired enrollment, admin-disabled
  source, provider quota, connector reauthorization, gateway outage, invalid
  model output, policy rejection, optimistic-concurrency conflict, and revoke
  failure as distinct conditions.
- Automatically retry only transient network/gateway failures with bounded
  backoff. Show **Retrying** without asking the user to act; after the policy-set
  limit, stop and offer one explicit Retry. OAuth denial, scope mismatch,
  conflicts, and policy rejection never auto-retry.
- All retries and callbacks are idempotent. A repeated handoff, finish, stage,
  apply, or revoke must resolve to the original receipt or current authority
  state, never duplicate a routine/draft/write.
- A partial-source run names the sources actually used. Review-mode drafts may
  continue only after the user accepts the reduced source set; bounded automatic
  publishing pauses rather than acting on an unexpected partial set.
- Once active, failures appear as a calm status card/banner over the last good
  Dayfold content, not as a full-screen dead end. Do not notify for one transient
  miss. Use one quiet needs-attention signal only after the policy threshold or
  an immediate user action is required.
- Revocation is **Pending** until Dayfold confirms it. Only then say Dayfold
  access is revoked. Provider-task cleanup remains a separate external step.
- Technical details may offer **Copy support code**. Never copy source content,
  provider error bodies, OAuth codes, tokens, hub IDs, or family/member IDs.

## 3. Screens and states to deliver

Every Dayfold-owned phone view below must render in light + dark.

### A. Discovery and consent

1. `entry-off-owner` — Smart Briefings off; owner CTA to set up.
2. `entry-off-adult` — read-only explanation: only a family owner can connect
   an AI subscription.
3. `provider-choice` — Claude and ChatGPT cards; no fake account discovery.
4. `source-intent` — Gmail / Calendar / Drive / Dayfold-only selection; each
   external source labeled “You'll connect this in {provider}.”
5. `source-permission-explainer` — two separate connections: provider↔Google
   and provider↔Dayfold; least-connector guidance and no external-write promise
   attributed to the routine policy, not to Google's OAuth grant.
6. `briefing-preset` — daily/weekday cadence intent and calm briefing preview.
7. `hub-scope` — per-hub selection, restricted-hub treatment, read/draft
   authority, explicit forbidden-action summary.
8. `privacy-review-claude` and `privacy-review-chatgpt` — provider-specific,
   honest plaintext/data-flow copy.
9. `technical-details` — secondary sheet with grant/fingerprint detail; never
   the main ceremony.

### B. Handoff and authorization

10. `handoff-claude` and `handoff-chatgpt` — prepared external handoff with
   Open + Copy instructions.
11. `oauth-approval-claude` and `oauth-approval-chatgpt` — provider-to-Dayfold
   connector grant approval.
12. `waiting-claude` and `waiting-chatgpt` — Dayfold connector, requested Google
    sources, schedule, and first-safe-read checklist + pairing TTL.
13. `source-setup-help-claude` and `source-setup-help-chatgpt` — provider-specific
    external steps, with Admin approval and Drive-sync branches where relevant.
14. `source-syncing-chatgpt` — Google Drive authorized but indexing may still be
    incomplete; allow setup to continue with an honest first-brief caveat.
15. `pairing-expired` — calm restart; no blame and no stale grant left active.
16. `returned-incomplete` — user returned without creating/confirming the
    provider routine; offer resume, copy instructions, or cancel.
17. `provider-unavailable` — task/routine or requested source connector is
    missing, disabled by an
    admin, or unsupported by the plan; no upsell invented by Dayfold.
18. `handoff-preparation-failed` — Dayfold could not create the enrollment;
    Retry is idempotent and no external setup has started.
19. `provider-authorization-denied` — the user canceled/denied provider-to-Dayfold
    OAuth; offer Resume in provider or Cancel setup without blame.
20. `authorization-context-mismatch` — wrong provider/family/scope or a late
    callback; do not widen or reuse the old grant. Restart with the intended
    context.
21. `return-recovery` — the provider app opened but the app return/deep link was
    lost. Resume from durable setup state; never make the user start over if the
    enrollment remains live.
22. `source-verification-partial` — which requested sources succeeded, returned
    zero results, are syncing, need reauthorization, or are admin-blocked;
    repair or explicitly continue review-only with the observed subset.

### C. Active and observable

23. `active-claude` and `active-chatgpt` — status, provider-managed schedule,
    requested vs observed sources, selected hubs, review mode, last observed
    run, recent receipts, and Manage sources in provider.
24. `first-draft` — proposed Dayfold changes, source provenance, and review
    actions.
25. `first-run-no-changes` — successful run and source probes, but nothing new to
    draft. Show the next provider-managed schedule request; this is not failure.
26. `partial-source-result` — last result used only a named subset; keep it in
    review mode and make the reduced provenance prominent.
27. `source-not-observed` — requested source was never successfully read; offer
    provider-specific setup help without claiming the Google grant's state.
28. `source-needs-reauth` — a routine explicitly reported authorization failure;
    open provider connector settings. Do not route Google OAuth through Dayfold.
29. `no-recent-checkin` — “We haven't heard from this routine recently.” Do not
    assert that the provider task failed or was deleted; Dayfold cannot know.
30. `connector-needs-attention` — Dayfold OAuth grant expired/revoked or a tool
    call was denied; reconnect without widening the old grant.
31. `run-reported-failed` — only when `dayfold_run_finish` reported a structured
    failure; distinguish this from unknown/missed status and map the reason to a
    safe recovery action.
32. `draft-stale-or-conflicted` — a human/newer run changed the target. Preserve
    the old draft as read-only and offer **Refresh draft**; never silently merge.
33. `apply-retrying` and `apply-failed` — use the existing saving/offline/retrying/
    couldn't-save vocabulary, preserve the draft, and make Retry idempotent.
34. `routine-auto-paused` — repeated failures, conflicts, unexpected source set,
    volume anomaly, or expired policy review paused automation. Explain the
    closed reason and require owner review before Resume.
35. `support-details` — last safe status, timestamps, app/provider versions where
    known, and Copy support code; no content or stable family/resource IDs.
36. `offline` — cached status and receipts render; setup/security mutations are
    disabled until reconnected.

### D. Stop and revoke

37. `stop-confirm` — three distinct responsibilities:
    - **Revoke Dayfold access now** sends the server-side revoke request. Once
      Dayfold confirms it, future Dayfold reads/writes stop immediately.
    - **Remove the scheduled task in {provider}** stops the provider from
      attempting future runs.
    - **Disconnect Google sources in {provider}** is optional and affects other
      provider uses too; Dayfold cannot perform it.

    Dayfold cannot promise to delete the provider task. Provide an **Open
    {provider}** step and an immediate Dayfold revoke request. Avoid cancellation
    friction or guilt copy.
38. `revoke-pending` — the request is being retried or its result is unknown.
    Keep the routine visibly active until confirmed; offer Retry when online.
39. `revoke-failed` — Dayfold still shows the last confirmed authority state and
    one Retry action. Never claim the grant is gone on a timeout/5xx.
40. `revoked-provider-task-remains` — access is safely revoked; the external
    task may still exist and will fail if it calls Dayfold. Show how to remove
    it in the provider.
41. `adult-active-readonly` — adult sees provider, owner/manager, last observed
    run, and review results; no grant/schedule/revoke controls.

## 4. Optional advanced path — “Run from Dayfold”

This is **not part of normal onboarding**. Put it behind an Advanced disclosure
on the active/detail screen.

### Claude

Claude Code subscription routines can expose an experimental per-routine API
trigger. There is no OAuth discovery or public token-management API. The user
must add the API trigger in Claude and copy the generated URL + bearer token.

Design:

- `advanced-run-claude` — explain the benefit: a deliberate **Run now** button
  or future Dayfold event can start the already-created Claude routine.
- Ask for **one secure paste** of Claude's complete generated command, not two
  developer-looking URL/token fields. Label it **Paste Claude connection**.
- Parse-preview only safe metadata: provider, routine ID suffix, and token scope
  “Can trigger this routine only.” Never render the full token after paste.
- Do not ask for a Claude password, Claude OAuth token, general API key, or
  source-connector credential.
- Saving stores a trigger credential; it does **not** call the endpoint.
- `advanced-test-claude` — an explicit **Start one test run** confirmation says
  this consumes one Claude routine run/subscription usage. Testing is not a
  harmless validation request.
- `advanced-claude-connected` — Run now, last trigger receipt, Rotate/remove
  connection. Warn that provider retries are not idempotent; Dayfold must not
  imply duplicate-proof execution.
- Include invalid/paused/quota-limited test results without exposing token text.

### ChatGPT

- `advanced-run-chatgpt-unavailable` — “ChatGPT Scheduled Tasks don't currently
  provide an external trigger.” Keep native scheduling and the Dayfold connector
  active. No token or URL field.
- A small **Managed workspace** footnote may mention that published Workspace
  Agents have a separate administrator-oriented trigger setup. Do not merge that
  higher-friction flow into consumer onboarding and do not imply Plus/Pro support.

## 5. Copy anchors

Use these exact or near-exact anchors wherever the statement appears:

- Hero: **“Let your AI subscription keep Dayfold useful.”**
- Supporting: “Claude or ChatGPT can turn the sources you've connected there
  into Dayfold drafts for your family.”
- Plan usage: **“Runs count against your {provider} plan.”**
- Handoff: **“You'll finish setup in {provider}.”**
- Source boundary: “Your email and files stay connected to {provider}. Dayfold
  receives only the updates the routine submits.”
- Source setup: **“You'll connect this Google account in {provider}.”**
- Source status: **“Requested”** vs **“Observed in a successful run.”**
- Source repair: “Open {provider} to review or reconnect this source.”
- Dayfold boundary: **“Selected Dayfold content is shared with {provider} for
  this routine.”**
- Default mode: **“Review drafts first.”**
- Schedule: **“Requested schedule · managed in {provider}.”**
- Unknown health: **“We haven't heard from this routine recently.”**
- No-op success: **“No new updates this time.”**
- Partial result: “This draft used {observed sources}. {missing sources} need
  attention.”
- Transient recovery: **“Keeping your last briefing · retrying.”**
- Conflict: “Dayfold changed since this draft was prepared. Refresh it before
  applying.”
- Support: **“Copy support code.”**
- Revoke: **“Revoke Dayfold access now.”**
- Provider cleanup: “Remove the scheduled task in {provider} to stop future
  attempts.”

Avoid:

- “One tap setup,” “fully connected,” or “we found your routines.”
- “Sign in with Claude/ChatGPT” as if it grants Dayfold provider-account access.
- “Gmail connected” or “Drive connected” based only on returning from provider
  setup; Dayfold cannot inspect those grants.
- A Google account picker or Google OAuth consent screen inside a Dayfold frame.
- “Webhook” in mainstream copy; use **Run from Dayfold** and **Claude
  connection**. Technical details may say **API trigger**.
- “Secure” without naming what is protected, and any absolute privacy promise.
- Sparkle-heavy AI visuals, anthropomorphic assistant language, streaks, scores,
  red urgency badges, or setup confetti.

## 6. Visual direction

- Reuse Dayfold's coral/teal/violet Material 3 Expressive roles, Outfit headings,
  Figtree body, rounded Material Symbols, surface-container hierarchy, and
  expressive-but-restrained motion.
- The feature belongs in **Settings → Devices & Connections**, not bottom nav.
- Use provider marks as restrained identifiers, not co-branded hero art. Keep
  the Dayfold mark and Dayfold language dominant.
- Make the data flow understandable through compact rows/cards, not a technical
  network diagram in the phone UI.
- Make progressive disclosure do the work: ordinary family language first;
  scopes, fingerprint, trigger token, and provider limitations under details.
- Default-off, review-first, least privilege, reversible. “Not now” and Cancel
  are calm peers, never visually punished.
- All tap targets >=48dp, readable contrast, reduced-motion alternatives, and
  dynamic type resilience.

## 7. Provider-owned surfaces

Do **not** recreate Claude or ChatGPT screens as if Dayfold controls them.
Instead, produce `Journey.dc.html`, an annotated cross-surface storyboard with:

```text
Dayfold consent
  -> external provider confirmation
  -> external Google connector OAuth/sync
  -> provider initiates OAuth to Dayfold
  -> Dayfold scope approval
  -> back to provider to save schedule
  -> first safe source read
  -> provider routine reports observed sources + calls dayfold_run_finish
  -> Dayfold Active / first-draft review
```

External cards may use the provider name/logo and concise annotations, but must
be visibly outside the Dayfold phone frame. Call out which steps Dayfold can
observe and which it can only infer from callbacks.

## 8. Output files and conventions

Create a new surface folder rather than modifying the signed-off Settings/Auth
mockups in place:

- `designs/routine-integration/Smart-Briefings-Phone.dc.html` — parameterized
  phone component with `mode`, `view`, and where useful `provider` props.
- `designs/routine-integration/Index.dc.html` — gallery mounting all required
  Dayfold-owned states in light + dark with short captions.
- `designs/routine-integration/Journey.dc.html` — annotated two-surface journey.
- `designs/routine-integration/NOTES.md` — decisions, unresolved provider-owned
  UI dependencies, state/view inventory, and a matrix mapping each recovery state
  to its closed phase/reason, CTA, durable receipt, and content-free analytics hook.
- `designs/routine-integration/support.js` only if the standard local runtime
  requires it.
- Update `designs/README.md` and `designs/Index.dc.html` to link the new gallery.

Token parity is mandatory: reuse `Design-System.dc.html`; do not invent a new
palette or component system. Reuse the existing Settings navigation and Auth
approval grammar without silently changing those approved source files.

## 9. Definition of done

- The default journey is understandable without CLI/API/OAuth vocabulary.
- Every §3 Dayfold-owned state exists in light + dark.
- `Journey.dc.html` clearly shows the unavoidable provider handoff and callback.
- Claude and ChatGPT limitations are represented honestly; no OAuth discovery or
  provider-task management is invented.
- Native provider scheduling is the default. Claude manual API-trigger setup is
  optional, advanced, one-paste, and accurately warns that Test starts a paid/
  quota-consuming run.
- The privacy review plainly shows both data directions and names the provider
  that receives selected Dayfold plaintext.
- Source-intent selection, provider-owned Google OAuth, organization-admin
  blocks, Drive syncing, first-safe-read verification, requested-vs-observed
  status, reauthorization, and provider-managed disconnect are all represented.
- Activation is backed by an idempotent `dayfold_run_finish` receipt; Dayfold-only
  and zero-result probes complete honestly, while partial source sets require
  repair or explicit review-only continuation.
- Provider denial/cancel, wrong context, lost return, duplicate/late callbacks,
  no-op success, partial results, conflicts, apply retry/failure, auto-pause, and
  revoke pending/failure all have distinct calm recovery states.
- Every displayed failure is derived from a closed phase/reason/action contract;
  raw provider errors and source content never enter copy or support details.
- Active failures preserve the last good content, automatic retries are bounded,
  and all Retry/Resume/Revoke actions are idempotent.
- `NOTES.md` maps every golden/sad-path transition to the durable state and
  content-free event hook; expected user/provider conditions are not labeled as
  Sentry defects.
- The mock never implies that Dayfold stores Google tokens or can inspect/revoke
  a Claude/ChatGPT Google connector.
- Hub scope, restricted-resource behavior, review-first default, adult read-only
  state, revocation, incomplete setup, and unknown-health states are all visible.
- Provider cleanup after Dayfold revocation is explicit; the UI never promises
  Dayfold deleted an external task.
- Root design index + README are updated, prototypes render, and operator sign-off
  is requested per ADR 0008. Sign-off gates deeper planning and implementation.
