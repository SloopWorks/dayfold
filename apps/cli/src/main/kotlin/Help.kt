package com.sloopworks.dayfold.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// dayfold CLI help — a single command registry ([COMMANDS]) is the source of truth for
// the index, per-command help, and the machine-readable `--json` output. Every command,
// alias, positional arg, and option carries a description so `dayfold help`,
// `dayfold <command> --help`, and `dayfold help --json` fully describe the tool (for a
// person AND the dayfold-curator agent). Adding a command means adding a registry entry —
// HelpModelTest fails if any command/arg/option lacks a description or example.

@Serializable
data class HelpArg(val name: String, val description: String, val required: Boolean = true)

@Serializable
data class HelpOption(val flag: String, val arg: String? = null, val description: String)

@Serializable
data class HelpCommand(
  val name: String,
  val aliases: List<String> = emptyList(),
  val synopsis: String,
  val summary: String,
  val details: List<String> = emptyList(),
  val args: List<HelpArg> = emptyList(),
  val options: List<HelpOption> = emptyList(),
  val examples: List<String> = emptyList(),
)

@Serializable data class HelpEnv(val name: String, val description: String)
@Serializable data class HelpExit(val code: Int, val meaning: String)

@Serializable
data class HelpModel(
  val name: String = "dayfold",
  val version: String,
  val summary: String,
  val commands: List<HelpCommand>,
  val env: List<HelpEnv>,
  val exitCodes: List<HelpExit>,
)

// ── Registry (source of truth) ──────────────────────────────────────────────

private const val DEFAULT_API = "https://family-ai-dashboard.vercel.app"

val COMMANDS: List<HelpCommand> = listOf(
  HelpCommand(
    name = "login",
    synopsis = "login [--allow-env-key]",
    summary = "Sign in this device; store the refresh token in the OS keychain.",
    details = listOf(
      "Runs the device-grant flow against DAYFOLD_API (default $DEFAULT_API) and saves the " +
        "refresh token in the OS keychain. On a host with no keychain, login refuses unless " +
        "--allow-env-key, then falls back to a plaintext 0600 file at " +
        "~/.config/dayfold/credentials.json (headless / CI).",
      "The credential's scope is fixed at login time: content:read / content:write (family-wide) " +
        "or hub:<id>:read / hub:<id>:write (per-hub, ADR 0029). There is no in-place re-scope — " +
        "run `login` again to change it. See `dayfold whoami` for the resolved scope.",
    ),
    options = listOf(
      HelpOption("--allow-env-key", description =
        "Permit the plaintext-file credential fallback when no OS keychain is available (headless / CI)."),
    ),
    examples = listOf("dayfold login", "dayfold login --allow-env-key"),
  ),
  HelpCommand(
    name = "logout",
    synopsis = "logout",
    summary = "Sign out: revoke the session and clear the stored credential + keychain token.",
    examples = listOf("dayfold logout"),
  ),
  HelpCommand(
    name = "whoami",
    synopsis = "whoami",
    summary = "Show the current sign-in: family, api, device/legacy, and resolved scope.",
    details = listOf(
      "Prints `scope=<grants>` for the signed-in credential — content:read / content:write " +
        "(family-wide) or hub:<id>:read / hub:<id>:write (per-hub, ADR 0029). With no sign-in it " +
        "prints how to set one up.",
    ),
    examples = listOf("dayfold whoami"),
  ),
  HelpCommand(
    name = "pull",
    synopsis = "pull [--hub <id>]",
    summary = "Read content back — all cards + hubs, or one hub's full tree.",
    details = listOf(
      "No flag: prints {\"cards\":[...],\"hubs\":[...]}. Use this before authoring to see what " +
        "already exists and to reuse ids (see `push` — checklist-item ids).",
    ),
    options = listOf(
      HelpOption("--hub", "<id>", "Print just that hub's tree (its sections + blocks) instead of the cards+hubs list."),
    ),
    examples = listOf("dayfold pull", "dayfold pull --hub 01J000000000000000000HUB"),
  ),
  HelpCommand(
    name = "push",
    synopsis = "push <id> <file.json> [--hub|--section|--block] [--type <t>] [--no-linkify]",
    summary = "Author a briefing card (default) or a hub-tree node.",
    args = listOf(
      HelpArg("id", "ULID of the card/hub/section/block to write (PUT is idempotent — same id overwrites)."),
      HelpArg("file.json", "Path to the JSON body. Start from `dayfold template <type>`."),
    ),
    options = listOf(
      HelpOption("--hub", description = "Author a hub (the top of a hub tree) instead of a briefing card."),
      HelpOption("--section", description = "Author a section (its body carries `hubId`)."),
      HelpOption("--block", description = "Author a block (its body carries `sectionId`)."),
      HelpOption("--type", "<t>",
        "Run local typed-card validation against the generated schema before the server. " +
          "t ∈ file|link|invite|contact|geo|email. Cards only."),
      HelpOption("--no-linkify", description =
        "Don't auto-wrap bare phone/email in body_md as tappable links (the default rewrites them)."),
    ),
    details = listOf(
      "Default target is a briefing card; --hub/--section/--block author a hub tree (PUT " +
        "/families/:fid/{cards|hubs|sections|blocks}/:id).",
      "Linkify (ADR 0015, content-blind server): body_md phone/email are auto-wrapped into " +
        "allowlisted tappable links at author time; --no-linkify opts out. The rewritten body is " +
        "what gets stored.",
      "Checklist ids (ADR 0038): checklist items without an `id` get a ULID stamped client-side. " +
        "REUSE the ids returned by `pull` on a re-push — do not hand-author fresh ids each time, or " +
        "you mint a new interactive item and lose members' prior toggle state.",
      "Visual enrichment (ADR 0036): card `media` = {thumbnailUrl,imageFit,imageAlt,icon,accentColor}; " +
        "hub `media` also has {heroUrl,heroFit} (a card has no hero). Image URLs must be https on an " +
        "allowed host (upload.wikimedia.org). icon ∈ {school,luggage,medical,move,party,baby,calendar," +
        "location,link,document,contact,budget,travel,car,food,pet,sport,list}; accentColor #RRGGBB. " +
        "Surface the chosen image to the operator before pushing.",
      "Visibility (ADR 0030/0038): a card/hub body may set visibility=family (default) or " +
        "visibility=restricted + audience=[userId,...] (only those members see it; everyone else gets " +
        "a uniform 404). On a CARD these fields are outside the generated schema, so pushing with " +
        "--type REJECTS them — push a restricted/audience card WITHOUT --type.",
      "A 403 on `push --section`/`--block` into a hub you don't own is the per-hub role gate (ADR " +
        "0053), independent of login/scope: a Viewer can't write, a Contributor can't manage people. " +
        "Only the hub owner / a Co-owner can promote you in-app — re-login will NOT fix it.",
    ),
    examples = listOf(
      "dayfold push 01J…CARD card.json --type file",
      "dayfold push 01J…HUB hub.json --hub",
      "dayfold push 01J…SECT section.json --section",
    ),
  ),
  HelpCommand(
    name = "delete",
    aliases = listOf("rm"),
    synopsis = "delete <id> [--card|--block]",
    summary = "Remove a hub (default; cascades its sections + blocks), a card, or a single block.",
    args = listOf(
      HelpArg("id", "ULID of the hub (default), card, or block to remove."),
    ),
    options = listOf(
      HelpOption("--card", description = "Delete a briefing card instead of a hub."),
      HelpOption("--block", description = "Delete a single block instead of a hub."),
    ),
    details = listOf(
      "There is no section-delete route (MVP): to drop a stray section, delete its hub and re-push the tree.",
    ),
    examples = listOf("dayfold delete 01J…HUB", "dayfold delete 01J…CARD --card"),
  ),
  HelpCommand(
    name = "template",
    synopsis = "template <type>",
    summary = "Print a starter JSON body to fill in and push.",
    args = listOf(
      HelpArg("type", "Which starter to print: file|link|invite|contact|geo|email (briefing-card " +
        "bodies) or hub|section|block|timeline (hub-tree bodies). `timeline` is a hub body carrying a " +
        "Hub.timeline (ADR 0045) — push it with --hub."),
    ),
    examples = listOf("dayfold template hub", "dayfold template file", "dayfold template timeline"),
  ),
  HelpCommand(
    name = "update",
    aliases = listOf("upgrade"),
    synopsis = "update",
    summary = "Update to the latest dayfold (brew upgrade) and re-check the version.",
    details = listOf(
      "The CLI also auto-checks for a newer version at most once / 24h after `pull`/`push`; set " +
        "DAYFOLD_NO_UPDATE_CHECK to disable that nudge.",
    ),
    examples = listOf("dayfold update"),
  ),
  HelpCommand(
    name = "version",
    aliases = listOf("--version", "-v"),
    synopsis = "version",
    summary = "Print the CLI version.",
    examples = listOf("dayfold version"),
  ),
  HelpCommand(
    name = "help",
    aliases = listOf("-h", "--help"),
    synopsis = "help [<command>] [--json]",
    summary = "Show help. Add a command name (or `<command> --help`) for one command; --json for machine-readable.",
    examples = listOf("dayfold help", "dayfold push --help", "dayfold help push", "dayfold help --json"),
  ),
)

private val ENV: List<HelpEnv> = listOf(
  HelpEnv("DAYFOLD_API", "Server base URL. Default $DEFAULT_API. Also the legacy-auth API (with FAMILY_ID + HOUSEHOLD_SECRET)."),
  HelpEnv("FAMILY_ID", "Legacy auth (pre-device-grant): the provisioned family id, used when no stored credential exists."),
  HelpEnv("HOUSEHOLD_SECRET", "Legacy auth: the provisioned household token, used when no stored credential exists."),
  HelpEnv("DAYFOLD_NO_UPDATE_CHECK", "Set to any value to disable the throttled once/24h update nudge."),
)

private val EXITS: List<HelpExit> = listOf(
  HelpExit(0, "success (or explicit help)"),
  HelpExit(1, "the server/session rejected the request (login/session-expired, non-2xx, validation " +
    "failure, or a per-hub role-gate 403 on push — see `push --help`); usually fixed by `dayfold login` " +
    "or fixing the payload"),
  HelpExit(2, "local misuse: bad flags/args, missing env, unreadable file, or no keychain without --allow-env-key"),
)

fun helpModel(): HelpModel = HelpModel(
  version = cliVersion(),
  summary = "Author and manage dayfold content (cards + hub trees) from the command line.",
  commands = COMMANDS,
  env = ENV,
  exitCodes = EXITS,
)

// ── Lookup + routing ────────────────────────────────────────────────────────

/** Resolve a token (a command name OR an alias, e.g. `rm`, `upgrade`, `-v`) to its command. */
fun commandByToken(token: String?): HelpCommand? =
  token?.let { t -> COMMANDS.firstOrNull { it.name == t || t in it.aliases } }

private val HELP_TOKENS = setOf("help", "-h", "--help")

/** A resolved help request: [command] null → the top-level index; [json] → machine-readable. */
data class HelpRequest(val command: HelpCommand?, val json: Boolean)

/**
 * Decide whether [args] is a help invocation, and which help to show — BEFORE the normal dispatch.
 *  - `help` / `-h` / `--help` as args[0] → top-level help; a following command token scopes it.
 *  - a known command as args[0] plus a `-h`/`--help` anywhere → that command's help.
 * `--json` anywhere in a help invocation selects the machine-readable output. Returns null when
 * [args] is a normal (non-help) invocation.
 */
fun helpInvocation(args: Array<String>): HelpRequest? {
  val first = args.getOrNull(0)
  val json = args.contains("--json")
  if (first in HELP_TOKENS) {
    val scoped = args.drop(1).firstNotNullOfOrNull { commandByToken(it) }
    return HelpRequest(scoped, json)
  }
  val cmd = commandByToken(first)
  if (cmd != null && args.any { it == "-h" || it == "--help" }) {
    return HelpRequest(cmd, json)
  }
  return null
}

/** Render the help a [HelpRequest] asks for (text or JSON, index or one command). */
fun renderHelp(req: HelpRequest): String = when {
  req.json && req.command != null -> renderJsonCommand(req.command)
  req.json -> renderJson(helpModel())
  req.command != null -> renderCommand(req.command)
  else -> renderIndex(helpModel())
}

// ── Text renderers ──────────────────────────────────────────────────────────

fun renderIndex(m: HelpModel): String {
  val sb = StringBuilder()
  sb.append("usage: dayfold <command> [args] [flags]\n")
  sb.append(m.summary).append("\n\n")
  sb.append("commands:\n")
  val width = m.commands.maxOf { label(it).length }
  for (c in m.commands) {
    sb.append("  ").append(label(c).padEnd(width)).append("  ").append(c.summary).append("\n")
  }
  sb.append("\n")
  sb.append("Run `dayfold <command> --help` for details on one command,\n")
  sb.append("or `dayfold help --json` for machine-readable help.\n\n")
  sb.append("environment:\n")
  for (e in m.env) sb.append("  ").append(e.name.padEnd(24)).append(e.description).append("\n")
  sb.append("\n")
  sb.append("exit codes:\n")
  for (x in m.exitCodes) sb.append("  ").append(x.code.toString().padEnd(3)).append(x.meaning).append("\n")
  return sb.toString().trimEnd('\n')
}

/** The index label for a command: `name` plus any aliases in parentheses. */
private fun label(c: HelpCommand): String =
  if (c.aliases.isEmpty()) c.name else "${c.name} (${c.aliases.joinToString(", ")})"

fun renderCommand(c: HelpCommand): String {
  val sb = StringBuilder()
  sb.append("dayfold ").append(c.synopsis).append("\n")
  sb.append(c.summary).append("\n")
  if (c.aliases.isNotEmpty()) sb.append("\naliases: ").append(c.aliases.joinToString(", ")).append("\n")
  if (c.args.isNotEmpty()) {
    sb.append("\narguments:\n")
    val w = c.args.maxOf { it.name.length }
    for (a in c.args) {
      val name = if (a.required) a.name else "[${a.name}]"
      sb.append("  ").append(name.padEnd(w + 2)).append(wrapInto(a.description, w + 4)).append("\n")
    }
  }
  if (c.options.isNotEmpty()) {
    sb.append("\noptions:\n")
    val labels = c.options.map { optLabel(it) }
    val w = labels.maxOf { it.length }
    c.options.forEachIndexed { i, o ->
      sb.append("  ").append(labels[i].padEnd(w + 2)).append(wrapInto(o.description, w + 4)).append("\n")
    }
  }
  if (c.details.isNotEmpty()) {
    sb.append("\n")
    for (d in c.details) sb.append(wrapInto(d, 0)).append("\n\n")
  }
  if (c.examples.isNotEmpty()) {
    sb.append(if (c.details.isEmpty()) "\n" else "").append("examples:\n")
    for (e in c.examples) sb.append("  ").append(e).append("\n")
  }
  return sb.toString().trimEnd('\n')
}

private fun optLabel(o: HelpOption): String = if (o.arg == null) o.flag else "${o.flag} ${o.arg}"

/** Soft-wrap [text] to ~92 cols, indenting continuation lines by [indent] spaces. */
private fun wrapInto(text: String, indent: Int): String {
  val max = 92
  val pad = " ".repeat(indent)
  val out = StringBuilder()
  var lineLen = indent
  var first = true
  for (word in text.split(' ')) {
    val add = if (first) word.length else word.length + 1
    if (!first && lineLen + add > max) { out.append("\n").append(pad); lineLen = indent; out.append(word); lineLen += word.length }
    else { if (!first) { out.append(' '); lineLen++ }; out.append(word); lineLen += word.length }
    first = false
  }
  return out.toString()
}

// ── JSON renderers ──────────────────────────────────────────────────────────

private val PRETTY = Json { prettyPrint = true; encodeDefaults = true }

fun renderJson(m: HelpModel): String = PRETTY.encodeToString(m)
fun renderJsonCommand(c: HelpCommand): String = PRETTY.encodeToString(c)
