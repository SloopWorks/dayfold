# dayfold CLI — command cheatsheet

The skill drives ONLY these commands. Assume `dayfold` is on PATH.

## Prereq — sign-in gate

```
dayfold whoami      # family=<id> api=<url> (device|legacy); prints scope=...
```
If it shows `(legacy)` with empty family or errors → the operator (not the
skill) must run:

```
dayfold login [--allow-env-key]
```
This is an interactive RFC 8628 device-grant flow (prints a code, the family
owner approves it in the app) — the skill should tell the operator to run it
and wait, never attempt it itself. The refresh token is stored in the OS
keychain; on a host with no keychain, `login` refuses unless
`--allow-env-key`, then falls back to a plaintext `0600` file at
`~/.config/dayfold/credentials.json` (headless/CI only).

`dayfold logout` clears the stored credential. `dayfold update` (alias
`upgrade`) / `dayfold version` (alias `--version`, `-v`) are maintenance
commands, not part of the authoring flow — mention them only if the operator
asks about upgrading the CLI itself. `update` auto-checks at most once/24h;
set `DAYFOLD_NO_UPDATE_CHECK` (any value) to disable the background check.

**`(legacy)` mode / no device login at all** — the CLI also accepts a
pre-device-grant fallback: set all three of `DAYFOLD_API`, `FAMILY_ID`, and
`HOUSEHOLD_SECRET` as env vars and every command (`push`/`pull`/`delete`)
works without ever running `login` — no refresh, no keychain, one shared
per-family secret. This is what a bare `whoami` prints `(legacy)` for. It
predates the per-device credential model and exists today for headless/CI
scripts already wired to it — if the skill sees `(legacy)`, tell the operator
either mode works, but device login (`dayfold login`) is the current
recommended path for a new setup.

**Scope (ADR 0029).** `whoami`'s `scope=...` is one of `content:read` /
`content:write` (global) or `hub:<id>:read` / `hub:<id>:write` (a single hub).
A device-granted credential's scope is fixed at login time — there is no
in-place re-scope; a 403 from insufficient scope means the operator must
`dayfold logout` and `dayfold login` again with broader access approved on
the phone, not something the skill can work around.

**Exit codes** (all commands): `0` = success (including `help`); `1` = the
server rejected the request (non-200 — see Push below); `2` = local misuse
(bad flags, an unreadable input file, or `login` refusing a keychain-less host
without `--allow-env-key`). A `2` means fix the local invocation, not retry
against the server.

## Read current state (Phase C, and to get ids before push)

```
dayfold pull                 # {"cards":[...],"hubs":[...]}
dayfold pull --hub <hubId>   # that hub's full section/block tree
```

## Get a starter body

```
dayfold template <type>      # prints starter JSON to stdout
```
`<type>` ∈ card types `file link invite contact geo email`
        + hub-tree bodies `hub section block timeline`.
`timeline` is not its own push resource — it's a starter for the `Hub.timeline`
field (ADR 0045), embedded in a hub body and pushed with `--hub` like any
other hub edit.
Redirect to a file to edit: `dayfold template invite > card.json`.

## Push (PUT) — card by default, hub tree with a flag

```
dayfold push <cardId> card.json --type <type>     # briefing card, local-validated
dayfold push <hubId> hub.json --hub               # hub
dayfold push <sectionId> section.json --section   # section (body carries hubId)
dayfold push <blockId> block.json --block         # block (body carries sectionId)
```
- `--type` runs local structural validation against the generated schema BEFORE
  the network — catches wrong payload variant / unknown field / type mismatch.
  Without `--type`, a card is sent unchanged (no local validation).
- Hub/section/block pushes (via `--hub`, `--section`, `--block`) run an always-on
  structural pre-check with no flag — the server is the authority for hub-tree shape.
- By default `push` auto-links bare phone/email in every `body_md` to tappable
  `tel:`/`mailto:` links and prints a diff of what changed — so author plain text, not
  hand-rolled markdown links. `--no-linkify` stores the body verbatim.
- Any checklist item lacking an `id` gets one stamped client-side before the request
  (ADR 0038 — members need a stable per-item key to toggle). **Re-pushing an edited
  checklist must reuse the ids from `dayfold pull`**, not hand-author fresh ones —
  a new id looks like a new item to members and drops their prior checked/unchecked
  state.
- The path `<id>` overwrites the body `id` server-side — the body `id` can stay
  `REPLACE_WITH_CARD_ID`.
- Output: `push <resource>/<id> -> <httpStatus>`. Non-200 prints the server body
  to stderr and exits 1 — the server is the authority; fix and re-push.

## Delete — remove a hub, card, or block

```
dayfold delete <id>            # hub (default): cascades its sections+blocks
dayfold delete <id> --card     # card
dayfold delete <id> --block    # a single block
dayfold rm <id>                # alias for delete
```
- Destructive and cascading (a hub delete takes its whole section/block tree with
  it) — see guardrail 9, propose-confirm applies here too, naming exactly what
  will be removed.
- No section delete route (MVP); to drop a stray section, delete its hub and
  re-push the tree.

## Notes

- Generate stable ulids for new ids client-side (26-char Crockford base32). Reuse
  an existing id (from `dayfold pull`) to update rather than create.
- There is no `dayfold create` / `list` — `push` both creates (new id) and updates
  (existing id); `pull` is the read path.
