# dayfold CLI — complete command reference

All commands. Assumes `dayfold` is on PATH.

## First-time setup (if not already signed in)

```
dayfold login
```
Starts the RFC 8628 device-grant flow. Displays a QR code (interactive terminals)
and a short code. The family owner must approve the code on their phone. On success,
the 45-day refresh token is stored in the OS keychain (macOS Keychain / libsecret).

```
dayfold login --allow-env-key
```
Headless / CI fallback: stores the refresh token in a 0600 file at
`~/.config/dayfold/credentials.json`. Prints a warning. Use on CI or hosts without
a keychain.

```
dayfold logout
```
Clears local credentials and revokes the server-side session.

## Check auth state (always run this first)

```
dayfold whoami
```
Prints `family=<id> api=<url> (device|legacy)` when signed in, or
`not signed in — run: dayfold login (or set DAYFOLD_API + FAMILY_ID + HOUSEHOLD_SECRET)`
when not. Also prints `scope=<grant1,grant2,...>` (the resolved server-side grants).

**Prereq gate:** if `whoami` shows `not signed in` or empty family, STOP and tell the
operator to run `dayfold login`. Do not author without a resolved family.

## Read current state

```
dayfold pull                 # {"cards":[...],"hubs":[...]}
dayfold pull --hub <hubId>   # that hub's full section/block tree
```

Pull before authoring updates to get existing ids (use them for updates; new ids for
new content). Ids are 26-char Crockford base32 ULIDs.

## Get a starter body

```
dayfold template <type>      # prints starter JSON to stdout
```
`<type>` ∈ card types `file link invite contact geo email`
        + hub-tree bodies `hub section block`

Redirect to a file: `dayfold template invite > card.json`.
Always start from a template — starters include required `kind` + `provenance.at`
fields that bare hand-written stubs often omit, causing local validator failures.

## Push (PUT) content to the server

```
# Briefing card (local validation with --type, highly recommended)
dayfold push <cardId> card.json --type <type>

# Briefing card (no local validation)
dayfold push <cardId> card.json

# Hub tree (always-on structural pre-check)
dayfold push <hubId>     hub.json     --hub
dayfold push <sectionId> section.json --section   # body carries hubId
dayfold push <blockId>   block.json   --block      # body carries sectionId
```

**Output:** `push <resource>/<id> -> <httpStatus>`
Non-200 prints the server body to stderr and exits 1. The server is the authority —
fix and re-push; never suppress a server rejection.

**`--type` flag:** runs local structural validation against the generated schema BEFORE
the network. Catches wrong payload variant / unknown field / type mismatch. Without
`--type`, a card is sent unchanged (no local pre-check). Hub/section/block always
run a pre-check.

**Path id overrides body id:** the `<id>` you pass to `push` becomes the resource id
server-side; the body's `id` field (e.g. `REPLACE_WITH_CARD_ID`) is ignored.

## Version

```
dayfold --version   # or: dayfold version
```
Prints `dayfold <semver>`. Useful for bug reports.

## Notes

- Generate stable ULIDs for new ids client-side (26-char Crockford base32,
  `^[0-9A-HJKMNP-TV-Z]{26}$`). Reuse an existing id (from `dayfold pull`) to update.
- There is **no** `dayfold delete` / `create` / `list`. Update-by-push only.
- Legacy env path (no `dayfold login`): set `DAYFOLD_API`, `FAMILY_ID`,
  `HOUSEHOLD_SECRET` in the environment. `whoami` will show `(legacy)`.
- The server URL defaults to `https://family-ai-dashboard.vercel.app`. Override with
  `DAYFOLD_API=http://localhost:3000 dayfold login` (dev/local API).
