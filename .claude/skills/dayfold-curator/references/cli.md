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

`dayfold logout` clears the stored credential. `dayfold update` / `dayfold
version` (or `--version`) are maintenance commands, not part of the authoring
flow — mention them only if the operator asks about upgrading the CLI itself.

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
        + hub-tree bodies `hub section block`.
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
