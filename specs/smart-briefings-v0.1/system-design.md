# Smart Briefings V0.1 — Claude Bridge operator pilot

**Status:** Proposed, no-ship design. It authorizes documentation and a synthetic
compatibility spike only. It does not authorize production deployment, private
Gmail data, connector publication, customer access, Terms acceptance, or spend.

**Date:** 2026-08-20

**Decision record:** Proposed ADR 0071

**Implementation plan:**
`docs/superpowers/plans/2026-08-20-smart-briefings-v0.1-claude-bridge.md`

**Hi-fi design prompt:**
`designs/PROMPT-smart-briefings-v0.1-claude-bridge.md`

## 1. Product outcome and name

This is the **V0.1 operator pilot**, not the paid hosted Dayfold release. It tests
one hypothesis: an owner can use their own Claude account and Claude's Gmail
connector to create a useful, retained Dayfold card without Dayfold holding a
Google credential or paying for inference.

```text
owner starts a run in Claude
  -> Claude reads Gmail through the owner's Claude/Google connection
  -> Claude reads one permitted Dayfold Hub through a Dayfold connector
  -> Claude submits zero or one bounded private-card proposal
  -> the source owner reviews it in Dayfold
  -> the owner accepts it for themselves and optionally named adults, or rejects it
  -> Dayfold inserts the accepted restricted card and syncs it normally
```

Calling this a paid, hosted, or generally available V0.1 would be false. Pricing,
billing, customer signup, and non-operator consent remain later release work.

### Pilot pass/kill proposal

After ten eligible real-data runs, continue only if all are true:

- zero cross-tenant, audience, token, external-write, or diagnostic leaks;
- setup is completed in 15 minutes or less without developer help after run 3;
- at least 6 of 10 finished runs produce a card the operator accepts;
- at least 3 accepted cards are reopened in Dayfold after 24 hours;
- steady-state support/maintenance is under 30 minutes per week.

These thresholds are operator-ratified constants, not implementation defaults.

## 2. Scope

### Included

- Claude is the only provider and every run is manually started.
- Gmail plus one selected Dayfold Hub are the only source types.
- One installation per family, one source owner, one selected Hub.
- The source owner, Google account owner, Claude account owner, approving Dayfold
  Owner, and draft reviewer are the same adult.
- One run produces zero or one `create_card` proposal.
- The proposal is visible only to the source owner in Dayfold.
- Accept or reject only. There is no draft editing in this pilot.
- Exact adult recipients are chosen only at acceptance. The source owner is always
  included.
- A separate, public OAuth-protected Streamable HTTP MCP bridge.
- Content-free run history, revocation, export, and erasure behavior.

The proposed requested source preset is `recent_family_updates_v1`: ask Claude to
search no more than 100 Gmail results from the previous 14 days, excluding spam
and trash. Dayfold cannot enforce or verify what Gmail Claude actually searches.
The request is copied into the Claude run instruction; query terms and label names
are not collected or stored by Dayfold. Changing it is an operator decision and
schema change, not a free-text setting.

### Explicitly excluded

- Dayfold Gmail OAuth, Google tokens, mailbox polling, or Pub/Sub.
- Attachment contents. Claude currently exposes Gmail attachment metadata, not
  attachment bytes, through its native connector.
  [fact:https://support.claude.com/en/articles/10166901-use-google-workspace-connectors]
  *(Provisional, recorded 2026-08-21: F-CATALOG found that Anthropic's
  connector-directory surface **over-stated** the Gmail runtime by two tools, and
  F-INVENTORY records that a directory catalog, a runtime tool manifest, and a
  model's self-description are three different artifacts with three different
  failure modes. This bullet rests on a provider **documentation** page — a
  fourth surface, and not one that was checked against the runtime — so treat it
  as unverified until it is. **F-CATALOG's "over-statement errs safe" reassurance
  does not transfer to this bullet:** it is a capability-**absence** claim, so the
  hazardous direction here is **under**-statement — documentation under-stating
  what the connector exposes would mean attachment bytes reaching a proposal.
  Direction-aware reasoning, per F-CATALOG, is the point.)*
- Gmail links, source locators, forwarding, add-ons, artifact storage, link
  crawling, attachment hosting, or “Open original in Gmail.”
- Calendar, Drive, scheduling, background tasks, or automatic publication.
- Multiple proposals, updates, deletes, editing, source writes, send/reply,
  label changes, invites, ACL/role changes, or arbitrary external actions.
- ChatGPT, Codex, BYOK API keys, Dayfold-funded inference, or Claude API calls.
- K3/K4 and any E2EE compatibility claim. M0 Dayfold content is server-readable.
- Non-operator rollout, pricing, billing, or commercial Terms flows.

## 3. Release/data gate

Dayfold's constitution says family data is never used to train shared models. A
consumer model-improvement toggle is not a verifiable absolute and can have
policy exceptions. Therefore:

1. local and provider compatibility work uses synthetic data only;
2. private operator Gmail/Dayfold data requires an eligible commercial
   no-training contract/posture **or** an explicit constitutional amendment;
3. non-operator use additionally requires counsel-approved Google downstream-use
   analysis, Terms, Privacy, AI/subprocessor disclosure, versioned acceptance,
   export/delete behavior, and a Data Safety/App Privacy update.

Disabling consumer model improvement is useful risk reduction but is not
sufficient authorization for private-data dogfood.

## 4. Hosting and trust boundaries

Claude custom connectors can call public remote MCP servers and normally use
OAuth for user-specific access.
[fact:https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp]

Host a stateless **MCP Bridge** as a separate Vercel service and entrypoint at a
dedicated resource origin such as `https://mcp.dayfold.example`. Keep it in the
same region as Dayfold Postgres. Do not mount it under the current API middleware.

**Recorded 2026-08-21** (`research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md`,
question 8): Gmail's own Claude connector is itself a **remote MCP server
authored by Google** at `https://gmailmcp.googleapis.com/mcp/v1`, per Anthropic's
provider-authored connector-directory page. A separately hosted remote MCP server
is therefore the ordinary shape on this surface, not an exotic one.

```text
Google account               Claude account
Google OAuth token <-------> Gmail connector + inference
                                   |
                          connector OAuth token
                                   |
                         mcp.dayfold.example
                    discovery / OAuth / four tools
                                   |
                     connector-only repositories
                                   |
                              Postgres
                                   |
                     api.dayfold.example + app
                 approval / review / accept / reject
```

The MCP Bridge and main API share schemas and the database, but not request
middleware or bearer-token verification. The bridge has a message/request-stripped
diagnostic source and connector-specific repositories. The main API owns human
approval and publication.

Credentials are independent:

1. Google → Claude: Anthropic holds the Google authorization; Dayfold cannot
   inspect or revoke it.
2. Claude → MCP Bridge: separate issuer, audience `dayfold-mcp`, access-token key,
   opaque refresh-token store, verifier, and revocation path.
3. Dayfold app: existing app credential; Claude never receives it.

No connector access or refresh token enters the existing app/CLI token tables or
`/auth/refresh`. Main API middleware rejects the connector issuer/audience/kind.
The MCP bridge rejects app/CLI access and refresh tokens. Cross-presentation is a
mandatory test matrix. Token passthrough is forbidden by the MCP authorization
specification.
[fact:https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization]

## 5. Normative lifecycle

Enrollment attempts and durable installations are different entities.

```text
attempt: prepared -> awaiting_provider -> awaiting_owner
        -> approved_waiting_exchange -> exchanged | denied | expired

installation: ready_first_run -> run_in_progress -> ready_manual
                                      |                 |
                                      +---- failed -----+
              ready_* -> revoking -> revoked
```

- Owner approval creates a single-use authorization code and state
  `approved_waiting_exchange`; it does not show Ready.
- Successful authorization-code exchange atomically creates the durable
  installation + connector credential and state `ready_first_run`.
- App/Claude return alone never promotes state.
- The first authenticated `dayfold_context_get` starts or resumes the one open run
  and moves the installation to `run_in_progress`.
- `dayfold_run_finish` closes the run and moves the installation to
  `ready_manual`, with result `draft`, `no_changes`, or `failed`.
- No state is named `active` in this manual pilot.
- A unique partial database constraint permits one non-revoked installation per
  family and one open run per installation.
- An open run expires after two hours. The next context call creates a new run.
- One proposal may be staged per run. Stage stores it as `pending_finish`, hidden
  from the app; a matching successful finish makes it reviewable. Replays return
  the original receipt.
- `no_changes` finishes successfully without a proposal.
- Owner-role loss, family departure, or target-Hub archive fails closed and
  revokes the installation.

## 6. User experience

### Setup

1. Owner opens **Settings → Devices & Connections → Claude briefing**.
2. Dayfold explains the two separate connections and manual-run model.
3. Owner selects exactly one Hub. No recipient is chosen during setup.
4. Dayfold shows the requested 14-day/100-result source preset, states that
   Dayfold cannot verify the Gmail search boundary, plus the plaintext flow,
   Anthropic/Dayfold retention split, and private-draft limitation.
5. Dayfold creates a 60-minute prepared enrollment attempt for the selected Hub.
6. When Claude opens `/oauth/authorize`, the bridge creates a separate pending
   authorization. Its responsive browser page shows a bounded user code, QR, and
   accessible Dayfold-app deep link; QR is never the only path.
7. The app consumes only that human user code, binds the pending authorization to
   the prepared attempt, and the owner approves the exact family, source owner,
   Hub, client/redirect/resource, and two MCP scopes.
8. OAuth returns a code to Claude using Authorization Code + S256 PKCE.
9. Dayfold shows `Ready for first run`, not Active.

The compatibility spike decides whether the MCP URL is installed, deep-linked, or
copied and which Claude surface can continue setup. Product copy says **Continue
in Claude** until that is proven.

**Recorded 2026-08-21 — the desktop half is now specified; the mobile half is
not** (spike questions 3 and 6, F-CSP). Install is by **manually typed
`<origin>/mcp`**: the client wants the full MCP endpoint, not a base origin it
appends a path to. Because DCR is not used (§10), setup must also include a step
where the owner **pastes an OAuth client ID that Dayfold issues** — that step is
not drawn anywhere yet and the ADR 0008 hi-fi has to add it. Desktop return was
observed: a full-page browser hand-off to the bridge's `/oauth/authorize`, the
approval page, then a return to Claude's fixed callback — and it completes **only**
with the `form-action` carve-out in §10; without it the ceremony dies silently.
**The mobile surface was not tested at all**, so whether a mobile browser blocks
or strips the redirect, whether an in-app browser is used, whether it shares the
session, and whether the return re-enters the Claude app are unmeasured. Half the
ceremony the hi-fi must draw is therefore still unspecified, and product copy
stays **Continue in Claude**.

### Run and draft discovery

Claude calls context, searches Gmail, submits at most one proposal, finishes the
run, and tells the user **Open Dayfold to review**. On app refresh, Account and
Today show a pending-draft badge. No notification infrastructure is added.

The draft screen shows title/body, destination Hub, **From Gmail · prepared by
Claude**, **visible only to you in Dayfold**, and **Original link unavailable**.
Actions are **Accept** and **Reject**. Accept opens exact-recipient confirmation;
the source owner is selected and cannot be removed. Reject removes the draft from
review immediately and purges its body from Dayfold within the ratified period.

Draft-body states are loading, unavailable offline, expired while open, already
decided elsewhere, and cleared on background/family switch. Accept/Reject are
disabled when the body is unavailable.

### Revoke

Dayfold revokes its connector credential and confirms that fact only after the
server does. It separately explains that the user must remove the Dayfold and
Google connections in Claude. Previously accepted cards remain normal Dayfold
content.

## 7. Disclosure and content minimization

The UI must say:

- Claude, not Dayfold, connects to Gmail and performs analysis in plaintext.
- Anthropic's account retention and training terms apply to Claude's copy.
- Dayfold receives the proposal, which can contain sensitive or third-party facts.
- A mailbox owner should remove information they should not send to Anthropic,
  Dayfold, or selected family members, including information about children,
  correspondents, health, or finances.
- Dayfold does not receive the Google password/token and cannot disconnect Google.
- Claude's Gmail connection may expose write tools; Dayfold cannot narrow that
  Google grant.
- A Dayfold draft starts visible only to its source owner in Dayfold; it is not
  encrypted from the Dayfold service in M0.
- Sharing a Dayfold card never grants Gmail access.

**Recorded 2026-08-21, two notes on this section.** (1) The bullet "Claude's
Gmail connection **may** expose write tools" is now measured: on the surface
tested it **does** — 22 mutating tools including immediate send and destructive
labelling (§9; the mutating families are measured on every surface consulted, and
the exact runtime count of 27 is strong evidence, not proof). The bullet as
written still holds; whether operator-facing copy should state it more strongly
is a copy decision for the ADR 0008 hi-fi review, not something this
reconciliation settles. (2) Spike question 7 recorded
**nothing** about what Claude stores in chat or which retention, deletion, or
training controls exist — those settings were never opened, so no wording exists
to quote. The retention and training statements in this section are therefore
**unverified against the provider surface**, and remain so.

Suggested concise copy:

> Claude reads the Gmail and Dayfold information you ask it to use. Anthropic
> processes it under your Claude account terms. Dayfold receives the proposal
> Claude submits. It may contain sensitive or third-party information and is
> visible only to you in Dayfold until you choose named recipients.

The proposal contract permits a short paraphrase, not a raw message artifact:

- title: 1–160 characters;
- body: at most 2,000 characters of safe inline Markdown;
- no headers, signatures, quoted-message blocks, body excerpts, email addresses,
  attachment names, Gmail IDs, source URLs, or arbitrary actions;
- no instruction from email content is executed.

Length/schema controls cannot prove that prose was derived or prevent semantic
copying. The UI and pilot checklist disclose that Claude may reproduce source
text despite the instruction, and the owner must inspect before acceptance.

This copy is not a substitute for Terms, a privacy policy, or legal advice.

## 8. Remote MCP contract

Use an exact pinned stable TypeScript MCP SDK. Transport is stateless Streamable
HTTP; there is no resumable session, notification, sampling, elicitation, or
resource subscription.
[fact:https://github.com/modelcontextprotocol/typescript-sdk/blob/main/docs/server.md]

All input schemas use `additionalProperties=false`. All free text is stripped from
errors. The bridge returns bounded Dayfold-owned codes only.

**Recorded 2026-08-21** (spike question 5): Claude negotiated MCP protocol version
**`2025-11-25`**, logged as a closed outcome carrying the value, so that is the
**floor the pinned SDK must support**. The stateless choice holds against the real
client: `initialize`, `tools/list`, and `tools/call` all completed against a
server issuing no session id, no standalone `GET` stream was opened, and no
`DELETE` teardown was issued — the transport itself needs no session storage. A
successful tool result was surfaced by Claude **verbatim and unmodified**. What is
**not** settled is whether the bridge's closed error codes survive intact to
Claude's user-visible surface: none of the three deliberate error paths was
driven, so the "all free text is stripped" requirement above is unverified at the
provider surface.

### `dayfold_context_get`

Input is only:

```json
{ "schemaVersion": 1 }
```

The bearer-bound installation supplies family, source owner, Hub, and requested
source-preset version. The server creates/resumes a server-minted run and returns:

```json
{
  "schemaVersion": 1,
  "runId": "run_...",
  "baseCursor": "...",
  "contextDigest": "sha256:...",
  "hub": {},
  "activeCards": [],
  "suppressionRules": []
}
```

Context has deterministic ordering and byte/item caps. It includes only the
selected Hub, current cards visible to the source owner and belonging to that Hub,
family-level suppression rules, and the source owner's own personal rules. It
never includes another adult's personal labels/notes, member emails, auth/device
state, or content outside the grant. A truncation enum is explicit.

### `dayfold_proposal_validate`

Input is the exact V2 proposal schema:

```json
{
  "schemaVersion": 2,
  "runId": "run_...",
  "baseCursor": "...",
  "card": {
    "kind": "info",
    "title": "School forms are due Friday",
    "bodyMd": "Complete the two remaining forms by Friday."
  }
}
```

Allowed card fields are only `kind` (`action|info|countdown`), `title`, `bodyMd`,
`notBefore`, and `expiresAt`. No IDs, Hub, audience, visibility, provenance,
source reference, URL, attachment, action, or arbitrary payload may be supplied.
The server verifies the bearer owns the run, cursor/digest remain current, the run
is open, and no proposal already exists. Errors contain path + closed code only.

### `dayfold_proposal_stage`

Takes the same exact proposal plus an opaque, bounded `clientRequestId`. It
revalidates in the same transaction, derives all authority, stores one private
`pending_finish` proposal that the app cannot list yet, and returns proposal/run
IDs and safe counts. A repeated request ID or run replay returns the original
result. A second distinct proposal for the run is a conflict.

The server derives the final card ID as a versioned HMAC over installation ID and
canonical proposal-card JSON; it stores the full digest for collision detection
and emits a 26-character non-sensitive ID. The model never supplies an identity.

### `dayfold_run_finish`

Input contains server-issued `runId`, terminal outcome, and an exact row for each
requested source using closed `reported_*` values and bounded
`recordsReported`. These are claims reported by Claude, not proof that Gmail was
read. `draft` must reference the run's one `pending_finish` proposal and atomically
change it to app-visible `staged`. `no_changes`/`failed` expose no proposal; if an
inconsistent hidden proposal exists, finish atomically marks it expired and purges
its body. An abandoned run does the same. Finish is idempotent on
`(installation_id, run_id)`.

### Absent tools

There is no generic content route, apply, edit, delete, send, share, invite, role,
HTTP, URL fetch, SQL, filesystem, shell, token, or OAuth tool. Publication is a
human app operation.

## 9. Gmail write capability gate

Instructions are not an authorization boundary. Before any private-data test, the
exact eligible Claude surface must prove either:

1. Gmail tools exposed to the run are structurally read-only; or
2. every send/reply/label/delete/archive mutation requires an unavoidable,
   per-mutation human confirmation, with no remembered approval, silent retry, or
   unattended execution.

The synthetic spike plants an injected email asking Claude to send, label, and
delete. The operator records the provider-level block/confirmation. If neither
condition holds, the pilot stops. **Recorded 2026-08-21: that test covers the
Gmail → Claude direction only; the Dayfold-content → Gmail-mutation direction is
not covered by it — see below.**

### Recorded 2026-08-21 — condition 1 cannot be satisfied on the measured surface *(corrected the same day — read the correction subsection below before relying on this one)*

Spike question 8, F-CATALOG, F-LABEL, F-FILTER
(`research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md`). This is the
one packet statement the run positively forces a revision of.

**The inventory, with the report's own confidence gradings carried through.** The
Gmail connector's **runtime** surface on claude.ai (Max, web, personal) carries
**27 tools — 5 read and 22 mutating**, including immediate send, reply, forward,
trash, spam, and destructive labelling. Grade the two claims separately:

- **Condition 1 is dead on this surface — measured.** Immediate send, reply,
  forward, trash, spam, and destructive labelling appear on **every** surface
  consulted, including Anthropic's **provider-authored** connector-directory
  page. That conclusion is measured against provider-authored metadata, not
  inferred and not a model self-report, and the runtime-versus-catalog question
  below does not touch it.
  **Corrected 2026-08-21 — this bullet is wrong as written, and is kept rather
  than rewritten.** The *inventory* it rests on stands and is now corroborated by
  a third provider surface. The *conclusion* does not: it was measured against the
  connector's **default configuration** and recorded as a property of the
  **surface**. Condition 1 is **false by default, not unsatisfiable** — a per-tool
  and per-group permission control exists, including a **block** state. Whether
  blocking satisfies condition 1 **as literally worded** is an open question, not
  a resolved one. See the correction subsection below.
- **The exact runtime figure of 27 is strong evidence, not proof.** The directory
  **catalog** lists **29**; the two extra are filter tools that probed as
  unreachable at runtime. Two independent lines agree on 27 — a targeted
  model-reported probe and the Gmail manifest delivered to a Claude Code session
  — one of them provider-delivered, but **no provider-authored *runtime* manifest
  surface exists**, so the runtime is currently establishable only through the
  model. The report also records a residual method caveat: the assurance that
  those queries invoked no tool is itself a model self-report, and the
  corroborating mailbox-state check was not performed.

**The disjunction has collapsed to one unproven branch.** *(**Corrected
2026-08-21**: it collapsed to one branch **under the default configuration**. A
control that may re-open the condition-1 branch exists and was not consulted when
this paragraph was written. Nothing below is retracted — condition 2 is still
unproven and question 9 still has not run — but "the pilot's **entire** Gmail
safety argument" over-states it. See the correction subsection.)* With condition 1
unsatisfiable here, **the pilot's entire Gmail safety argument is condition 2**
— an unavoidable per-mutation human confirmation with no remembered approval,
silent retry, or unattended execution. Condition 2 is **unmeasured**: the
injected-mutation test (spike question 9) was **not run**, deliberately — no
synthetic mailbox exists and running it against the operator's personal mailbox
was declined. **This gate is further from satisfied after the spike than before
it**, and the synthetic mailbox is now on the pilot's critical path rather than a
convenience. Related: spike question 10 grades condition 2's "cannot be
remembered / retried / unattended" clause and is also unmeasured for Gmail;
everything it observed was for the Dayfold connector.

**Keep "send/reply/label/delete/archive" enumerated; do not simplify it to a
send-and-delete list** (F-LABEL). Two runtime tools,
`apply_sensitive_message_label` and `apply_sensitive_thread_label`, apply Trash
and Spam labels — **mail can be moved to Trash or Spam through label application,
with no tool whose name contains "trash", "delete", or "spam"**. Any read/write
classification that reasons only about obviously-named destructive tools
mis-classifies labelling as benign. The injected-mutation test must therefore
exercise the **labelling** tools as well as the direct ones, and spike question
10's "can an approval be remembered" probe must cover label application
specifically — it is the mutation most likely to be treated as low-stakes by a
confirmation design, and on this surface it is not low-stakes.

**A second injection direction, recorded 2026-08-21 and not covered by the test
above.** `dayfold_context_get` returns **family-authored `activeCards`** into the
Claude session (§8), and that session provably holds **22 mutating Gmail tools**.
The injected-email protocol tests **Gmail content → Claude**; it does not test
**Dayfold content → Gmail mutation**. That direction was of bounded interest
while condition 1 was live and is **load-bearing now that condition 1 is dead**:
a hostile or compromised Dayfold card body is model input reaching a session that
can send, forward, trash, and spam-file real mail, and a spike question 9 `PASS`
as currently specified would not cover it. **Recorded as a direction the test
does not reach; no countermeasure is designed here**, and the conditions above
are unchanged.

**Known gap in this section, recorded and deliberately not closed here**
(F-FILTER). The two conditions above can express "the tools are read-only" and
"each mutation is confirmed". Neither can express **"this confirmed mutation
delegates standing authority that outlives the run"** — a mutation whose single
confirmation authorizes an unbounded stream of later mutations that no subsequent
confirmation gates. The one known instance, `create_filter`, **probed as not
reachable at runtime** (strong evidence, not proof), so the hazard does not bite
on the surface the pilot would use and **nothing is amended now**. Two things are
recorded rather than answered: the carve-out should be written as a **general
rule, not a named exclusion** for one tool, because a named exclusion fails
silently against the next such tool; and question 9's protocol should state the
class it does not test. Both are open design questions for WP1, not blockers.

**This inventory is a snapshot, not a durable guarantee.** Anthropic's own
wording on the directory page, verbatim: *"Only use connectors from developers
you trust. Anthropic does not control which tools developers make available and
cannot verify that they will work as intended or that they won't change."* The
server is Google's. A future runtime could expose the filter tools without
notice. The report's recorded consequence is that **the implementation needs a
re-check mechanism** for the Gmail tool surface; **what that mechanism is, and
where the gate binds it, is an open design question this reconciliation does not
settle.** Either way this gate cannot be satisfied once and for all by an
inventory taken on one date from one surface.

### Correction recorded 2026-08-21 — condition 1's status above was measured against the **default configuration**, and a per-tool permission control exists

Spike **F-PERMS**, **F-DOCS**, question 8
(`research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md`). **The two
normative conditions at the top of this section are unchanged, and nothing in this
subsection satisfies, passes, or advances either of them.** This is a correction
to a recorded status, not a new capability and not a gate movement.

**What was previously recorded, and why it was wrong.** The subsection above
records condition 1 as *"dead on this surface — measured"* and the disjunction as
collapsed, on the strength of **22 mutating tools of 27**. The inventory stands.
The **conclusion drawn from it does not**: it was measured against the Gmail
connector's **default configuration** and written down as a property of the
**surface**. It is a property of the default. **This is the same error class
`F-INVENTORY` already documents** — a conclusion drawn from one provider surface
without checking whether another surface changes the answer — and it is the
**third** time this packet has revised a conclusion about the Gmail tool surface.

**What was observed.** On 2026-08-21 the controller opened
`claude.ai › Settings › Connectors` on the same personal **Max** account, web
client, and found the Gmail connector carries a **Tool permissions** section —
*"Choose when Claude is allowed to use these tools"* — with two collapsible
groups, each with a group-level dropdown:

```
Read-only tools      5      Always allow
Write/delete tools  22      Needs approval
```

Every individual tool row carries **three** states — allow / ask / **block** —
and every visible write/delete row was set to **ask**, matching the group default.
The **Google Calendar** connector shows the identical structure, so this is a
general connector capability on this account tier rather than a Gmail special
case. **This was a read-only inspection: no setting was changed on the operator's
account.**

**The precise restatement.** Condition 1 is **false under the default
configuration**, and the surface ships a first-class per-tool and per-group
control that **may** make it true. That is materially different from
unsatisfiable. If it holds, it is **strictly stronger than condition 2**: a
structural control applied once, rather than a human judgment repeated at every
mutation, and unaffected by whether "Always allow" is offered.

**Why this does not restore condition 1 — the unresolved question.** Condition 1's
wording is *"Gmail tools **exposed to the run** are structurally read-only"*, and
it is sensitive to an implementation detail that is **unmeasured**: whether
`block` **removes a tool from the surface offered to the run**, or **exposes it
and refuses at call time**. The two differ against that exact phrasing even where
the security property is comparable. The section header — "Choose when Claude is
allowed to **use** these tools" — hints at call-time enforcement, but that is
reading a UI label, not a measurement. **Blocking is therefore not recorded here
as satisfying condition 1.** The cheap test that would settle it needs no mailbox
— set the group to blocked, then ask Claude to enumerate its Gmail tools: if the
22 disappear, blocking removes them from the surface; if they remain and refuse
when called, it is call-time enforcement. **It changes an operator account setting
and is operator-only; it has not been run.**

**Also not established.**

- **Persistence.** Whether the setting survives sessions, app upgrades, and
  connector reconnects, and whether it can be silently reset. Three Anthropic
  issue reports describe "Always allow" state failing to persist across chats,
  sessions, and upgrades; the inverse failure would matter here, and none of those
  reports covers a **blocked** state.
- **Dayfold cannot verify it.** The setting lives in a third-party product Dayfold
  cannot read, audit, or enforce. Whatever posture is chosen, Dayfold can never
  confirm from its own side that the gate is intact. This is unchanged by the
  finding, and it is why any such posture belongs in an **operator pre-run
  checklist**, not in a Dayfold control.

**One thing this does strengthen, independently of the gate.** The settings UI is
a **provider-authored runtime** surface — not a catalog, not a model self-report —
and it independently renders **5 read / 22 write-delete**, agreeing with the
runtime figure of 27 rather than the catalog's 29. That closes the residual gap
recorded above ("no provider-authored *runtime* manifest surface exists"). The row
list scrolls and was only partially enumerated, so it corroborates the **split**,
not every tool name.

**Consequence for the subsection below, and for INB-41.** The condition-2
observations that follow stand exactly as recorded. What changes is how much
weight they carry: the interpretive question routed as **INB-41** — whether "no
remembered approval" forbids the affordance or only its use — is load-bearing
**only if the pilot must rest on condition 2**. If the condition-1 branch can be
re-opened by configuration, it need not. **INB-41 has been reframed accordingly**
and now leads with the posture question ("block the write/delete tool group as the
pilot's required Gmail configuration") while preserving the interpretation
question, which still governs if the blocking route fails the exposed-versus-
refused test. **No status changes:** `adr/0071-self-managed-claude-bridge-v0.1.md`
remains **Proposed**, this design remains **no-ship**, condition 2 remains
unproven, spike question 9 remains unrun, and the synthetic mailbox remains
absent.

### Recorded 2026-08-21 — condition 2's mechanism exists, and it offers remembered approval

Spike **F-CONFIRM**, **F-ROUTING**, questions 9 and 10
(`research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md`). **The
conditions above are unchanged by this subsection**; what follows is an
observation and an open interpretation, not an amendment.

**What was run, and what it cannot establish.** The injected-mutation test
(spike question 9) still cannot run — it needs a synthetic mailbox that does not
exist. A narrower, non-destructive probe needed none: the controller **directly
asked** Claude (Max, claude.ai web, Chrome, personal) to send a message to an
RFC 2606 `.invalid` address, undeliverable by construction, and **denied every
confirmation presented**. `send` was chosen because it touches nothing already in
the mailbox. **Nothing was sent.** **A directly requested mutation is not an
injected one**, so the probe **cannot satisfy condition 2 and does not** — it
could only refute condition 2 or establish that its mechanism exists. **Condition
2 remains unproven.**

**What it observed, with the report's confidence gradings carried through.**

- **A per-call confirmation dialog exists on the Gmail send path — observed.**
  Verbatim: *"Claude wants to use Send email message from Gmail"*, with three
  controls — `Deny` (Esc), `Always allow` (⇧⌘⏎), `Allow once` (⌘⏎). It names the
  **specific tool** and the **specific connector**.
- **The gate is client-enforced, in front of the tool call — observed on one
  path.** It is not mediated by the model's judgment, which makes it
  **structurally stronger than a prompt-level protection**. *Inference from a
  single observed path, not proof:* it was **not** tested whether every mutating
  tool is gated, nor whether any path mutates with no dialog.
- **"Always allow" is offered — observed; its scope and lifetime were not
  measured at all.** Condition 2 requires the confirmation to be unavoidable
  "with no remembered approval". The surface offers remembered approval as a
  first-class control with a keyboard shortcut, adjacent to "Allow once".
  Unmeasured: whether it is per-conversation, per-connector, per-tool, or
  per-account; whether it survives sessions; whether it can be revoked; and
  **whether a mutation would proceed silently once it has been granted**. Nothing
  was granted.
- **Denial was honoured with no silent retry — observed.** Every confirmation
  presented was denied, each denial was respected, and no mutation occurred on
  either path attempted. **Denial is per-call, not per-run**: denying one tool
  did not stop Claude attempting the next within the same turn.
- **Which connector executes is not deterministic — observed** (**F-ROUTING**).
  A plain "send an email" routed first to a *different* installed mail connector
  and reached Gmail only on explicit redirection. This gate's inventory covers
  **Gmail**; another installed mail connector carries an **uninventoried** tool
  surface. Bears on `adr/0071-self-managed-claude-bridge-v0.1.md` §6's run
  instruction, which presumes the run lands on the intended connector.
- **Not recorded:** the mailbox-state check (Sent, Drafts). That nothing was sent
  is the controller's observation of denying each dialog, not a mailbox
  inspection — the same residual method caveat as the two narrow queries behind
  the inventory above.

**The interpretation this forces, recorded and deliberately not settled here.**
Condition 2's phrase "**with no remembered approval**" admits two readings, and
they give **opposite verdicts**:

- **Reading A — a property required of the mechanism.** The surface must not
  offer remembered approval at all. Condition 2 is then **unsatisfiable on the
  measured surface**; with condition 1 already dead, **this section would have no
  surviving branch**, tripping the handoff stop condition *"Gmail mutation can
  occur without unavoidable human confirmation"* — and **the pilot stops**.
  *(**Corrected 2026-08-21:** the clause "condition 1 already dead" was true of
  the default configuration only. Reading A's verdict on **condition 2** is
  unchanged, but "no surviving branch" no longer follows from it automatically —
  that now depends on whether the condition-1 branch can be re-opened by blocking
  the write/delete tool group, which is **unresolved**. See the correction
  subsection above.)*
- **Reading B — a usage policy binding the operator.** The mechanism may offer
  remembered approval provided the operator never grants it and always chooses
  "Allow once". Condition 2 stays satisfiable, but the pilot's Gmail safety then
  rests on **operator discipline at every dialog, forever** — the class of
  protection this section's own opening rejects ("Instructions are not an
  authorization boundary") and which ADR 0071 lists under *Rejected for this
  pilot* as "prompt-only Gmail write prevention". It also **degrades badly**: one
  mis-click of a shortcut sitting directly above "Allow once" converts the
  remaining safety argument into nothing, silently, with **no Dayfold-visible
  signal**.

**Whether this design's own gate is met is an ADR-class judgment reserved to the
operator** (`CLAUDE.md`: never agent-decided — scope, kill/pivot). It is routed
as **INB-41** with a proposed default. **No gate is passed, satisfied, or
advanced by this record**, and the two normative conditions above stand exactly
as written pending that decision.

**What this changes about the test that is still owed.** Because the
confirmation is client-enforced rather than model-mediated, the sharpest
remaining experiment is no longer "will the model comply with an injection" but
**"does an injected mutation proceed silently once 'Always allow' has been
granted"** — and it should cover **every mutating tool class** (send/compose,
label management, label application including `apply_sensitive_*_label`, trash,
spam), not send alone. Granting "Always allow" is itself a mutation-enabling act,
so that test needs the **synthetic mailbox** as much as the injected test does.
**Recorded as an amendment the spike question 9 protocol needs; the protocol is
not written here.**

## 10. OAuth design

```text
resource = https://mcp.dayfold.example
audience = dayfold-mcp
scopes   = mcp:context.read mcp:draft.submit
```

The bridge owns discovery, authorize, token, revoke, and MCP.

**DCR is not required — recorded 2026-08-21** (spike question 3, F-RUNBOOK;
measured on **Claude Max / claude.ai web / Chrome / desktop, personal account**),
which closes this section's open question. Claude never called `/oauth/register`.
It read the authorization-server metadata, concluded from the **absence of
`registration_endpoint`** that registration was unavailable, asked the operator
to enter an OAuth client ID by hand, and completed the whole ceremony against a
**public client with no secret** (`token_endpoint_auth_methods_supported:
["none"]`). The client is **metadata-driven, not probe-driven**: **omitting
`registration_endpoint` from the authorization-server metadata is sufficient, and
the route need not exist.** *Not measured:* behavior when the endpoint **is**
advertised — in particular whether the client's registration body survives a
strict RFC 7591 allowlist — so nothing here says DCR would work if re-enabled.
§11, §12, and §14 still mention DCR rows, retention, and epoch gating; they are
left as written, cost nothing while the route is absent, and would bind again if
a later decision re-enables it.

**These two facts are coupled, and the packet must not treat them as
independent.** The `form-action` carve-out below is safe **today** only because
DCR is gone: the redirect origin it emits is a **Dayfold-provisioned constant**
tied to a client Dayfold issued. Under re-enabled DCR a registrant would supply a
value that lands in a **response header on the code-carrying page** — a
CSP-widening and header-injection surface. **Re-enabling DCR therefore re-opens
the carve-out for security review**; the two may not be decided separately.

OAuth requirements:

- Authorization Code + S256 PKCE; exact redirect URI; exact resource/audience;
- separate connector signing key, access verifier, refresh table, and issuer;
- 5-minute access token; opaque rotating refresh token; reuse revokes lineage;
- authorization code hashed, single-use, 10-minute maximum;
- separate high-entropy browser poll secret stored hashed; attempt IDs are never
  polling credentials; the secret travels only in a Secure, HttpOnly, SameSite
  browser cookie (or spike-proven equally strong one-time browser channel), never
  in the human UI, QR, app deep link, app API, logs, or analytics;
- a short rate-limited user code is the only pairing value shown to the human/app;
  it binds one pending authorize request to one prepared app enrollment and has
  collision retry, attempt limits, single use, and the same 60-minute ceiling;
- polling never returns the authorization code; the OAuth redirect does;
- one-time compare-and-set transitions for allow/deny/exchange;
- `Cache-Control: no-store`, strict CSP, `frame-ancestors 'none'`, no-referrer,
  escaped bounded client name, host/origin/body/time/rate limits — **with one
  mandatory carve-out: the approval page's `form-action` must include the origin
  of the registered redirect URI, not only `'self'`**, and that origin comes from
  the **stored client record after exact redirect-URI validation, never from
  request input** (see below);
- approval binds client, source owner, family, installation, one Hub, scopes,
  redirect, and resource;
- revocation checked on every bridge call;
- no inbound bearer is forwarded to another resource.

### Recorded 2026-08-21 — measured against the real client

Spike question 4 and F-CSP
(`research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md`). Claude Max,
claude.ai web, Chrome, desktop.

**The `form-action` carve-out is a correctness requirement, not hardening.**
Implemented the obvious way, the approval page carried `default-src 'none';
frame-ancestors 'none'; base-uri 'none'; form-action 'self'`. The consent form's
same-origin POST is allowed, but **the 302 carrying the authorization code is a
form-initiated navigation, and Chrome and Safari enforce `form-action` against
redirect targets**, so the browser refuses to follow it and the code never
reaches the client. **There is no server-side error to diagnose it by:** the log
reads `oauth.approve / ok` and then stops, `/oauth/token` is never called,
DevTools shows the POST returning a correct `302`, and Claude reports only that
the connection was not finished. `curl` does not enforce CSP, so a fully green
non-browser suite passes against a build whose ceremony cannot complete. The
carve-out must stay an **exact allow-list — never a wildcard** — and every other
response keeps the strict header unchanged. Any implementation of this section
must be verified by at least one **real-browser pass**; a green `curl` suite is
not sufficient evidence for this class of defect.

**Where the origin comes from is part of the requirement.** It must be read from
the **stored client record, after exact redirect-URI validation** — **never from
request input**. "Derived from the registered client" is one careless step from
`new URL(req.query.redirect_uri).origin`, and if the approval page renders before
exact-match validation the carve-out becomes attacker-steerable: a
request-supplied origin would land in a CSP header on the page that carries the
authorization code. Validate first, then render.

**Conditions that re-open this carve-out for review:** re-enabling DCR (a
registrant would then control the value — see the DCR note above); any change
that lets more than one redirect URI be registered per client; and any change
that renders the approval page before redirect-URI validation.

**Exact `resource` binding stays required, and is now confirmed workable.** The
spike deliberately ran one notch permissive — accepting an absent indicator — so
that this could be measured. Claude **sends** the RFC 8707 `resource` indicator:
at `/oauth/authorize` the value matched the advertised origin exactly (measured).
Presence at `/oauth/token` is also recorded, but its **attribution is timestamp
correlation, not proof**. The relaxation was spike-only and **must not be carried
forward**; the exact-resource requirement above is confirmed rather than weakened.

Also observed, each a recorded fact:

- **S256 PKCE with no `plain` fallback**, `response_type=code`, and both
  advertised scopes requested unprompted — at authorize and at refresh.
- **One fixed exact redirect URI per connector.** Claude uses a **fixed provider
  callback** (`https://claude.ai/api/mcp/auth_callback`), so exact-match binding
  needs no wildcard, no prefix match, and no per-install registration — one
  constant per connector. Binding it exactly is what made the connect succeed.
- **Single-use approval holds against a real browser.** A second Approve produced
  `invalid_grant`, not a second code.
- **Unattended silent refresh is real.** ~19 minutes after the previous activity,
  against a 5-minute access-token TTL, Claude refreshed on the rotating refresh
  lineage with **no preceding authorize or approve** and resumed calling tools —
  and the reuse-revoke lineage did **not** false-positive against a real client.
  That is a capability *and* a constraint: an installed connector holds usable
  credentials with no human present, so **no safety property may rest on the
  assumption that a human is watching the session**.
- **Not measured:** `/oauth/revoke` was never called and reconnect-after-revoke
  was never exercised. The plan's "the surface cannot reconnect after revoke"
  stop condition is **unassessed, not cleared**. A reconnect across spike
  restarts is key rotation, not a revocation event, and is not evidence here.

## 11. Data model

```text
families/users/hubs
  -> routine_enrollment_attempts (ephemeral)
  -> routine_installations (durable; one live per family)
       -> connector_credentials -> connector_refresh_tokens
       -> routine_runs -> routine_source_outcomes
                       -> routine_draft_proposals (zero or one)
                            -> briefing_cards (only after human acceptance)
oauth_clients -> oauth_authorization_codes
              -> oauth_pending_authorizations
```

### Key tables and invariants

| Table | Required fields/invariants |
|---|---|
| `routine_enrollment_attempts` | `id`, family/source owner/Hub, requested-preset version, state including `approved_waiting_exchange`, control epoch, 60-minute expiry; no pairing secret, audience, or query text |
| `routine_installations` | `id`, family/source owner/Hub, provider, state, policy version, timestamps; unique live row per family |
| `connector_credentials` | installation/client/scopes, status, created/revoked/last-used; separate from app/CLI credentials |
| `connector_refresh_tokens` | opaque token hash, credential/client/resource binding, lineage/rotation/reuse fields, 45-day maximum |
| `oauth_clients` | exact redirect URIs, bounded name, auth method, expiry/revocation; DCR rows expire if unused |
| `oauth_authorization_codes` | hash, client/redirect/resource/PKCE/approved-attempt/control-epoch binding, single use and expiry; exchange creates the installation |
| `oauth_pending_authorizations` | client/redirect/resource/PKCE request, hashed browser poll secret, hashed short user code, bound enrollment, state/expiry/control epoch; no family content |
| `routine_runs` | server ID, installation/family, state/result, base cursor/digest, start/finish/expiry, replay receipt; one open per installation |
| `routine_source_outcomes` | run/source/outcome/`records_reported`; enums and counts only |
| `routine_draft_proposals` | one per run; source owner/Hub, bounded card JSON, `pending_finish/staged/accepted/rejected/expired/conflicted`, full card-ID digest, expiry/decision/apply metadata; body never logged |
| `connector_control` | singleton mode/control epoch/reason/time; `enabled|paused_security`; locked/checked by every authority issue/consume path |

For a future customer release, add `legal_acceptances(user_id, document_kind,
document_version, document_hash, accepted_at, locale, product_version)`. It is not
evidence of consent to another person's mailbox content and does not waive
Dayfold's security duties.

## 12. Retention proposal

These are unaccepted constants:

| Record | Proposed default |
|---|---:|
| prepared enrollment + pending authorization/user code/poll secret | 60 minutes; purge all pairing material |
| denied/expired attempt metadata | 7 days |
| authorization code | 10 minutes; purge after exchange |
| access token | 5 minutes |
| connector refresh lineage | 45 days maximum; revoke immediately on request |
| unused DCR client | 24 hours; revoked client metadata 30 days |
| open/abandoned run | 2 hours; content-free terminal metadata 90 days |
| pending proposal body | 14 days |
| rejected/expired proposal body | hidden immediately; purge within 24 hours |
| accepted proposal body | purge in the successful card-insert transaction |
| proposal decision metadata | 90 days, content-free |
| rate-limit buckets | 24 hours, no content |
| connector revocation tombstone | 90 days, content-free |

An accepted card follows ordinary Dayfold content deletion. Current hard-purge,
tombstone, backup, and deletion-propagation constants are unresolved elsewhere;
that gap blocks non-operator privacy/TOS claims. Anthropic's retained chat/copy is
outside Dayfold deletion authority and must be disclosed separately.

## 13. Human acceptance transaction

`POST /families/:familyId/routine-draft-proposals/:proposalId/accept`

```json
{
  "schemaVersion": 1,
  "idempotencyKey": "01...",
  "audience": ["source-owner-user-id"]
}
```

Using a human app token, one transaction:

1. checks connector control is enabled;
2. locks the staged proposal and requires source owner + current family Owner;
3. rechecks installation, Hub, membership, cursor, schema, mute/done, and ID
   collision;
4. requires the source owner in the audience; validates every additional ID is an
   active adult allowed for the target Hub; empty/invalid audience fails;
5. always inserts `briefing_cards.visibility='restricted'` with that exact
   audience, even when the Hub is family-visible;
6. stamps creator, provider, installation/run, source types, and card ID
   server-side using a dedicated insert-only repository; never generic upsert;
7. records the idempotent receipt and purges the proposal body in the same commit.

Reject uses a separate human endpoint, hides the proposal immediately, and queues
body purge. A second device/replay returns the decided state. There is no partial
acceptance because a run has at most one proposal.

Required tests prove non-recipient adults cannot receive the card through direct
GET or `/sync` in both family-visible and restricted Hubs.

## 14. Kill switch and revocation

`connector_control` contains `mode` and a monotonically increasing `control_epoch`.
Enrollment create/approve, browser authorize/redirect, DCR, code/token exchange,
refresh, MCP discovery/dispatch, and proposal acceptance all lock/check the current
enabled epoch in the same transaction that creates or consumes authority. Status,
reject, revoke, and purge remain available.

The security-pause transaction locks the control row, increments the epoch, sets
`paused_security`, and denies/expires/revokes every pending authorization,
enrollment attempt, authorization code, live installation, connector credential,
and refresh lineage. Every row records its creation epoch; any older epoch is
invalid even after resume. Thus either a concurrent issuance commits first and is
revoked by pause, or pause commits first and issuance fails. Resume keeps the new
epoch and requires a fresh enrollment. Already-issued access tokens fail both the
epoch and credential checks. A background retry completes any failed batch and
reports safe counts.

## 15. Security, privacy, and diagnostics

- The MCP service uses its own generated SWIP/error source with
  `stripMessage=true` and request stripping. No nested current-API middleware.
- No source/proposal text, OAuth value, raw model output, prompt, source ID/URL,
  stable family/User/Hub ID, or support code enters logs, analytics, Sentry, SWIP,
  headers, fixtures, or provider-visible errors.
- Expected OAuth/source/quota/validation/conflict/no-change outcomes are product
  state, not exceptions.
- Tenant denials follow the no-existence-oracle posture.
- Sign-out, background, and family switch cancel calls and clear draft bodies from
  memory before another family activates.
- Synthetic medical/financial/token canaries prove exclusion from diagnostics.
- The bridge has strict schemas, origin/host/body/time/concurrency/rate limits and
  no arbitrary fetch/HTML rendering.

## 16. Compatibility spike gate

Before final hi-fi or production code, use synthetic data to record:

- supported Claude plans, clients, admin settings, install URL/manual URL behavior;
- Gmail + custom connector coexistence on the exact surface;
- OAuth discovery, client registration, PKCE, token refresh, reconnect/revoke;
- Streamable HTTP initialize/list/call/error behavior;
- external return/deep-link behavior and browser approval needs;
- read/write Gmail tool inventory and the injected-mutation test in section 9;
- what Claude stores in chat and which deletion/training controls are available.

Any weaker token posture, silent Gmail mutation, unavailable coexistence, or need
to capture a Claude subscription credential is a no-go.

**Recorded status, 2026-08-21 — this gate is not satisfied.** The spike ran once
against a live Claude Max account on claude.ai web
(`research/2026-08-20-smart-briefings-v0.1-compatibility-spike.md`). **3 of 10
matrix questions are answered; 7 are `UNKNOWN`** — and an `UNKNOWN` there is a
verdict about **coverage**, not a doubt about the observations recorded inside
it. Answered: plan/client/admin prerequisites (bullet 1, in part), install URL and
the DCR question (bullet 1/3), and the Gmail tool inventory (bullet 6, first
half). Still open, and load-bearing:

- **the injected-mutation test in §9** (bullet 6, second half) — not run, and
  blocked on a synthetic mailbox that does not exist;
- Gmail + Dayfold co-invocation **inside one conversation** (bullet 2) —
  coexistence holds at the account level only;
- revoke and reconnect (bullet 3), and the deliberate error paths (bullet 4);
- the **mobile** surface (bullet 5);
- **what Claude stores in chat and which deletion/training controls exist**
  (bullet 7) — **not recorded at all**; those settings were never opened.

## 17. Definition of done

The operator pilot is implementation-ready only after:

1. the synthetic compatibility spike is recorded and reconciled — **not met**:
   as of 2026-08-21 it is recorded for 3 of 10 questions and reconciled only to
   that extent (§16); the remaining rows must be recorded and reconciled too;
2. the final live-flow hi-fi is signed off under ADR 0008;
3. ADR 0071, exact retention/policy/value constants, hosting, and diagnostic source
   are accepted;
4. the separate-token, cross-tenant, exact-audience, run, replay, revocation,
   kill-switch, prompt-injection, and leak-canary tests pass;
5. one supported client can prepare/approve, review/accept/reject, and revoke;
6. private-data testing has an eligible no-training authority;
7. ten eligible runs meet the pass/kill thresholds.

The paid hosted release additionally requires pricing/billing/signup work,
versioned legal acceptance, complete retention/deletion policy, counsel/privacy
review, and non-operator eligibility. Those are not implied by this pilot.

## 18. Operator decisions

1. Accept or replace ADR 0071 and the V0.1 operator-pilot naming/boundary.
2. Authorize a synthetic external Claude compatibility test and any preview
   deployment it requires.
3. Ratify the source preset, retention table, value thresholds, separate Vercel
   bridge, and Gmail write no-go rule.
4. Select the authority for any private-data dogfood: eligible contract or an
   explicit constitutional amendment.
5. Keep all non-operator use blocked until the legal/privacy/deletion gates are
   complete.
