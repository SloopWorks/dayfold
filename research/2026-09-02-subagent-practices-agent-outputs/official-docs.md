# Claude Code Subagents & Agent Configuration — Official Reference

Based on the official Claude Code documentation, here is a precise, citation-backed reference covering subagents, agents, skills, settings, and related agent infrastructure.

---

## 1. Subagent Definition File Format

**Source:** https://code.claude.com/docs/en/sub-agents.md

### File Locations & Precedence

| Scope | Location | Priority | Checked Into VCS? |
|-------|----------|----------|------------------|
| Managed (org-wide) | Organization-deployed | 1 (highest) | N/A |
| Session CLI flag | `--agents` JSON on launch | 2 | N/A |
| Project | `.claude/agents/` | 3 | **Yes** |
| User (all projects) | `~/.claude/agents/` | 4 | N/A |
| Plugin agents | Plugin's `agents/` directory | 5 (lowest) | Plugin-dependent |

Files are scanned recursively within each directory. `--add-dir` also loads agents from additional directories' `.claude/agents/` when you set `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1`.

### Complete YAML Frontmatter Specification

**Required fields:**
- **`name`** — Unique identifier (lowercase letters and hyphens only; no `:` or leading `-`). Used for delegation and invocation.
- **`description`** — Brief guidance (≤15,000 tokens total across all agent descriptions) on when Claude should delegate to this agent.

**Optional configuration fields:**

| Field | Type | Values | Default | Purpose |
|-------|------|--------|---------|---------|
| `tools` | string list | Comma-or-space-separated tool names | (inherit all tools) | Allowlist of tools available to agent |
| `disallowedTools` | string list | Tool names; supports `mcp__*` patterns | (none) | Denylist; removes from inherited pool |
| `model` | string | `sonnet`, `opus`, `haiku`, `fable`, full ID like `claude-opus-5`, or `inherit` | (session model) | Model for this agent |
| `permissionMode` | string | `default`, `acceptEdits`, `auto`, `dontAsk`, `bypassPermissions`, `plan` | (session mode) | Permission mode for agent's tool use |
| `maxTurns` | integer | 1–∞ | (no limit) | Max agentic turns before stop |
| `skills` | string array | Skill directory names | (none) | Skill names to preload at startup |
| `mcpServers` | object/array | Inline definitions or references | (none) | MCP servers scoped to this agent |
| `hooks` | object | Hook type → matcher + hook array | (none) | Lifecycle hooks (PreToolUse, PostToolUse, etc.) |
| `memory` | string | `user`, `project`, `local` | (no memory) | Persistent memory scope |
| `background` | boolean | `true` or `false` | `false` | Keep in background even if Claude requests foreground |
| `effort` | string | `low`, `medium`, `high`, `xhigh`, `max` | (inherit) | Reasoning effort override |
| `isolation` | string | `worktree` | (none) | Run in isolated git worktree |
| `color` | string | `red`, `blue`, `green`, `yellow`, `purple`, `orange`, `pink`, `cyan` | (automatic) | UI display color |
| `initialPrompt` | string | Any markdown text | (none) | Auto-submitted first turn (main session only) |
| `experimental` | object | e.g., `{ cacheTtl: "5m" }` | (none) | Experimental features |

### Complete Example Subagent File

```yaml
---
name: security-code-reviewer
description: >
  Analyzes code for security vulnerabilities, OWASP violations, authentication/authorization patterns, and hardcoded secrets.
  Use PROACTIVELY when security-sensitive code changes (auth, crypto, data handling) are being reviewed.
tools: Read, Grep, Glob, Bash
disallowedTools: Write, Edit
model: opus
permissionMode: plan
maxTurns: 15
skills:
  - security-patterns
  - owasp-guidelines
mcpServers:
  - github  # Reference existing server
memory: project
color: red
hooks:
  PreToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "./scripts/validate-safe-commands.sh"
---

You are a senior security engineer specializing in code review. Your role is to:

1. Identify potential security vulnerabilities (CWE/CVE class)
2. Check OWASP Top 10 violations
3. Verify proper authentication/authorization patterns
4. Flag hardcoded secrets, plaintext credentials, or sensitive data exposure
5. Review cryptographic usage for common mistakes
6. Recommend security improvements with severity ratings

Always:
- Reference patterns from your preloaded skills
- Update your agent memory with new vulnerability patterns and fixes you discover
- Be thorough but concise; prioritize findings by severity
- Flag assumptions about trust boundaries
```

---

## 2. System Prompt Body & Delegation

**Source:** https://code.claude.com/docs/en/sub-agents.md

### What Goes in the Body

The markdown content after the `---` frontmatter becomes the subagent's **system prompt instructions**. This is what the subagent reads when it starts.

**How description drives automatic delegation:**
- The `description` field is Claude's cue for *when* to delegate. Keep it concise (under 200 chars for best triggering).
- Use **PROACTIVELY** language: "Use PROACTIVELY when...", "Use for...", "Delegate to... when..."
- Examples:
  - Good: "Analyzes code for security issues. Use PROACTIVELY for auth, crypto, or data-handling changes."
  - Weak: "Code reviewer" (Claude may never delegate)

### Context at Subagent Startup

A subagent receives:
- **System prompt:** its own markdown body + environment context (not the main session's system prompt)
- **Task message:** Claude's delegation description
- **CLAUDE.md hierarchy:** full tree of CLAUDE.md files (except built-in Explore/Plan skip these for speed)
- **Git status:** repository snapshot (except Explore/Plan skip)
- **Preloaded skills:** full content of skills listed in frontmatter
- **Sibling roster:** names of other agents in the session (v2.1.206+)

**What it does NOT see:**
- Main conversation history
- Main conversation's output style preferences
- Main session auto memory (exception: forks inherit parent's auto memory)
- Previously invoked skills (must discover via Skill tool)

### Results Return

- Subagent returns a **summary** to the main session, not full transcript
- Preserves main conversation context by handling verbose work in isolation
- Results include tool call history if Claude asks for details

---

## 3. Built-In Subagents

**Source:** https://code.claude.com/docs/en/sub-agents.md

| Agent | Purpose | Model | Tools | Context | When Used |
|-------|---------|-------|-------|---------|-----------|
| **Explore** | Fast, read-only codebase exploration | Inherits (capped at Opus on API) | Read-only only | Skips CLAUDE.md, git status | File discovery, code search, codebase analysis |
| **Plan** | Gather context before implementation plan | Inherits | Read-only only | Skips CLAUDE.md, git status | Research for planning mode |
| **General-purpose** | Complex multi-step tasks | `CLAUDE_CODE_SUBAGENT_MODEL` env or session model | All subagent tools available | Full context | Complex exploration + action |
| **claude** | Catch-all default | Session model | Session tools | Full context | Background sessions, fallback |
| **statusline-setup** | Configure `/statusline` | Sonnet | Tools needed for CLI | Full | `/statusline` configuration |
| **claude-code-guide** | Claude Code feature questions | Haiku | Read-only | Full | Help questions about Claude Code |

### Disable Built-Ins

```json
// .claude/settings.json
{
  "permissions": {
    "deny": ["Agent(Explore)", "Agent(Plan)"]
  }
}
```

Or environment variables:
```bash
export CLAUDE_CODE_DISABLE_EXPLORE_PLAN_AGENTS=1
export CLAUDE_AGENT_SDK_DISABLE_BUILTIN_AGENTS=1  # Non-interactive/SDK mode
```

---

## 4. Subagent Memory Feature

**Source:** https://code.claude.com/docs/en/sub-agents.md + https://code.claude.com/docs/en/memory.md

### Memory Scopes

Set `memory:` in subagent frontmatter to one of:

| Scope | Location | Shared | Use Case |
|-------|----------|--------|----------|
| `user` | `~/.claude/agent-memory/<name>/` | All projects | Cross-project learnings |
| `project` | `.claude/agent-memory/<name>/` | Version control | Project-specific patterns |
| `local` | `.claude/agent-memory-local/<name>/` | This machine only | Experimental, not VCS'd |

### How Memory Loads

- **`MEMORY.md`** (the index) — first 200 lines or 25 KB loaded at startup
- Topic files (e.g., `user_role.md`, `vulnerability_patterns.md`) — loaded on demand when agent reads them
- Agent can Read, Write, Edit memory files automatically
- Agent must keep index under limit; Claude Code warns if approaching threshold

### Example Subagent with Memory

```yaml
---
name: api-design-reviewer
description: Reviews API design for consistency, REST conventions, and developer ergonomics
memory: project
skills:
  - api-conventions
  - error-responses
---

Check your memory for established patterns in this codebase before reviewing.
Update memory with any new patterns, conventions, or anti-patterns you discover.
```

---

## 5. Hooks Inside Subagent Frontmatter

**Source:** https://code.claude.com/docs/en/sub-agents.md + https://code.claude.com/docs/en/hooks-guide.md

### Format

```yaml
hooks:
  <HookType>:
    - matcher: "<agent-name-or-pattern>"
      hooks:
        - type: command | script | http
          command: "..."
          # ... hook-specific fields
```

### Example: PreToolUse for Read-Only Enforcement

```yaml
---
name: db-reader
description: Execute read-only database queries
tools: Bash
hooks:
  PreToolUse:
    - matcher: "Bash"
      hooks:
        - type: command
          command: "./scripts/validate-readonly-query.sh"
---
```

Script (`./scripts/validate-readonly-query.sh`):
```bash
#!/bin/bash
INPUT=$(cat)
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

# Block write operations
if echo "$COMMAND" | grep -iE '\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE)\b' > /dev/null; then
  echo "Blocked: Only SELECT queries allowed" >&2
  exit 2
fi
exit 0
```

### Available Hook Types (via settings.json for subagents)

```json
{
  "hooks": {
    "SubagentStart": [
      { "matcher": "^db-agent$", "hooks": [{ "type": "command", "command": "./setup-db.sh" }] }
    ],
    "SubagentStop": [
      { "hooks": [{ "type": "command", "command": "./cleanup-db.sh" }] }
    ]
  }
}
```

**Matcher rules:**
- Exact match for hyphenated names (v2.1.195+)
- Earlier versions treat as unanchored regex; anchor with `^` / `$`
- Plugin-scoped agents: `^plugin-name:agent-name$`

---

## 6. Best Practices

**Source:** https://code.claude.com/docs/en/sub-agents.md

1. **Single responsibility** — one subagent per clear, focused task
2. **Restrict tools appropriately** — balance capability with safety; use `disallowedTools` to deny dangerous ones
3. **Organize project agents** — store in `.claude/agents/` for team collaboration
4. **Preload critical skills** — inject domain knowledge at startup with `skills:`
5. **Enable memory for learning** — use `memory: project` to accumulate insights
6. **Use hooks for validation** — enforce constraints before operations execute
7. **Test isolation modes** — `isolation: worktree` for safe experiments
8. **Keep descriptions brief** — combined descriptions >15,000 tokens trigger warning
9. **Use meaningful names** — lowercase-hyphenated format for clarity

### When to Use Subagents vs. Skills vs. Slash Commands

**Source:** https://code.claude.com/docs/en/skills.md

| Mechanism | Best For | Invocation | Output |
|-----------|----------|-----------|--------|
| **Subagent** | Self-contained work requiring isolated context; verbose output | Claude delegates, `/resume`, `@"name (agent)"` mention, `--agent` flag | Summary returned to session |
| **Skill** | Reusable multi-step procedures; reference knowledge | `/skill-name` or Claude invokes auto | Injected into conversation; persists across turns |
| **Slash command** | Quick, single-turn actions (e.g., `/commit`, `/send-slack-message`) | You type `/command` | Immediate result |
| **Hook** | Deterministic automation at lifecycle events (before/after file edits) | Executes automatically at event | Side effect; doesn't inject into conversation |

---

## 7. Skills vs. Agents vs. Commands — Frontmatter & Usage

**Source:** https://code.claude.com/docs/en/skills.md

### Skill Frontmatter Fields

| Field | Type | Purpose |
|-------|------|---------|
| `description` | string | When Claude should auto-invoke the skill |
| `allowed-tools` | string list | Pre-approve specific tools (e.g., `Bash(git *)`) |
| `disallowed-tools` | string list | Remove tools from inherited pool |
| `disable-model-invocation` | boolean | `true` = only you can invoke (blocks auto); useful for side-effect skills like `/deploy` |
| `user-invocable` | boolean | `false` = only Claude can invoke (not a user command); for reference/context skills |
| `context: fork` | boolean (as string "fork") | Run in isolated subagent; skill content becomes the prompt |
| `agent` | string | Subagent type to execute forked skill (e.g., `agent: Explore`) |
| `background` | boolean | `true` = run forked skill in background (default for fork) |
| `shell` | string | `bash` or `powershell`; required if skill runs inline shell |
| `arguments` | string array | Named arguments for skill invocation |
| `license`, `metadata`, `compatibility`, `name` | (metadata) | For skill marketplaces and portability |

### Example Skill with Fork & Agent

```yaml
---
description: Perform a security audit of the codebase
context: fork
agent: Explore
disable-model-invocation: true
allowed-tools: Bash(grep *)
---

# Skill content becomes the subagent's prompt

Audit every file in src/ for hardcoded secrets using patterns in your memory.
```

### Visibility Control Matrix

| Field Setting | Claude Auto-Invokes? | You Can Invoke? | In Skill Listing? |
|---------------|----------------------|-----------------|-------------------|
| Default | Yes | Yes | Yes |
| `disable-model-invocation: true` | No | Yes | Yes |
| `user-invocable: false` | Yes | No | Yes (description only) |
| Both flags | No | No | Hidden |

---

## 8. Settings: Agent & Skill Configuration

**Source:** https://code.claude.com/docs/en/settings-reference.md

### Agent-Related Settings Keys

```json
{
  "agent": "code-reviewer",                      // Start every session with this subagent
  "disableAgentView": false,                     // Turn off background agents / agent view
  "subagentPromptCacheTtl": "5m",                // Cache lifetime for subagent requests (default 5m)
  "subagentStatusLine": "your-script",           // Custom status-line output for subagents
  "teammatMode": "in-process",                   // "auto", "in-process", "tmux", "iterm2"
  "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1"   // Enable agent teams (env var or settings.json `env`)
}
```

### Skill-Related Settings Keys

```json
{
  "disableBundledSkills": false,                 // Turn off built-in skills/workflows
  "skillOverrides": {                            // Hide/collapse skills from settings
    "skill-name": "hidden"                       // or "user-invocable-only"
  },
  "skillListingBudgetFraction": 0.2,             // Context reserved for skill listing
  "skillListingMaxDescChars": 500,               // Cap per-skill description length
  "disableSkillShellExecution": false,           // Block inline shell in skills
  "syncClaudeAiSkills": true                     // Download skills from claude.ai account
}
```

### Permission Settings (Relevant to Agents)

```json
{
  "permissions": {
    "defaultMode": "auto",                       // Session permission mode
    "allow": [
      "Agent(security-reviewer)",                // Allow specific subagent
      "Bash(npm test *)"                         // Pre-approve tools
    ],
    "deny": ["Agent(Explore)", "Edit(secrets.*)"],
    "additionalDirectories": ["/shared/config"]
  },
  "autoMode": {                                  // Auto mode classifier rules
    "rules": []
  }
}
```

### Environment Variables in Settings

```json
{
  "env": {
    "CLAUDE_CODE_SUBAGENT_MODEL": "haiku",
    "CLAUDE_CODE_SUBAGENT_MODEL_FORCE": "1",    // Override all model sources (v2.1.257+)
    "CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH": "2", // Nesting limit (default 3)
    "CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS": "15",  // Concurrent limit (default 20)
    "CLAUDE_CODE_DISABLE_BACKGROUND_TASKS": "1"
  }
}
```

### Settings File Precedence (Highest to Lowest)

1. **Local** (`.claude/settings.local.json`) — personal, not VCS'd
2. **Project** (`.claude/settings.json`) — team-shared
3. **User** (`~/.claude/settings.json`) — all projects
4. **Managed** (organization deployment) — enterprise-wide
5. **Defaults** (built-in)

---

## 9. Agent Teams & Fleet Features

**Source:** https://code.claude.com/docs/en/agent-teams.md

### Enable Agent Teams

```json
{
  "env": {
    "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1"
  }
}
```

Agent teams are **experimental** and disabled by default.

### How They Work

- **Lead** — main session that spawns and coordinates teammates
- **Teammates** — separate Claude Code instances, each with own context window
- **Task list** — shared work items at `~/.claude/tasks/{team-name}/`
- **Mailbox** — JSON-based messaging at `~/.claude/teams/{team-name}/inboxes/{agent-name}.json`

### Using Subagent Definitions as Teammate Roles

```yaml
---
name: security-reviewer
description: ...
tools: Read, Grep, Bash
model: opus
---
```

Then spawn teammate:
```text
Spawn a teammate using the security-reviewer agent type to audit the auth module.
```

The subagent definition's `tools`, `model`, and body (system prompt) apply to the teammate. MCP servers apply in split-pane mode only.

### Key Hooks for Team Quality Gates

```json
{
  "hooks": {
    "TeammateIdle": [
      { "hooks": [{ "type": "command", "command": "./check-quality.sh" }] }
    ],
    "TaskCreated": [],
    "TaskCompleted": []
  }
}
```

Exit with code `2` to send feedback and keep the agent working.

### Compared to Subagents

| Aspect | Subagents | Agent Teams |
|--------|-----------|-------------|
| Shared context | Return to caller | Fully independent |
| Communication | Return result to caller | Message each other directly |
| Coordination | Lead manages all work | Self-coordinate via task list |
| Best for | Focused tasks, quick results | Complex work needing discussion |
| Cost | Lower (results summarized) | Higher (each teammate = separate session) |

---

## 10. Workflow Orchestration

**Source:** https://code.claude.com/docs/en/workflows.md

### What a Workflow Is

A **dynamic workflow** is a JavaScript script Claude writes that orchestrates many subagents at once. Useful for:
- Codebase-wide audits
- Large migrations (500+ files)
- Cross-checked research
- Parallel independent reviews

### Trigger Keywords

In your prompt:
```text
ultracode: audit every API endpoint under src/routes/ for missing auth checks
```

Or set effort level:
```bash
/effort ultracode
```

The keyword `ultracode` only triggers in prompts you type directly (not `-p`, webhooks, or scheduled tasks).

### Workflow Script Structure

```javascript
export const meta = {
  name: 'audit-routes',
  description: 'Audit route handlers for auth checks',
}

const found = await agent('List every .ts file under src/routes/.', {
  schema: { type: 'object', required: ['files'], properties: { files: { type: 'array' } } },
})

const audits = await pipeline(found.files, file =>
  agent(`Audit ${file} for missing auth checks.`, { label: file }),
)

return audits.filter(Boolean)
```

**Functions available in scripts:**
- `agent(prompt, options)` — spawn one subagent
- `pipeline(items, fn)` — spawn one agent per item, sequentially
- `parallel(tasks)` — run tasks concurrently
- `phase(title)` — group following agents under a phase in UI
- `log(message)` — log above phases
- `args` — input passed via `/workflow-name <args>`

### Manage Runs

```bash
/workflows                  # List and view runs
# In /workflows view:
# ↑/↓ select, Enter drill, p pause/resume, x stop, s save, r restart
```

Resume a paused run: the runtime replays completed agents from cache, reruns failed ones and everything after.

---

## 11. Memory Management

**Source:** https://code.claude.com/docs/en/memory.md

### CLAUDE.md vs. Auto Memory

| | CLAUDE.md | Auto Memory |
|---|----------|------------|
| Who writes | You | Claude |
| Content | Instructions, rules | Learnings, patterns |
| Scope | Project / user / org | Per repository |
| Loaded | Every session | First 200 lines or 25 KB |
| Use for | Coding standards, workflows | Your preferences, corrections |

### CLAUDE.md File Locations

| Scope | Path | Load Order |
|-------|------|-----------|
| Organization | `/Library/Application Support/ClaudeCode/CLAUDE.md` (macOS) | First |
| User (all projects) | `~/.claude/CLAUDE.md` | Second |
| Project | `./CLAUDE.md` or `./.claude/CLAUDE.md` | Third |
| Local (personal) | `./CLAUDE.local.md` | Fourth (add to `.gitignore`) |

Files in subdirectories load on demand when Claude reads matching files.

### Auto Memory Storage

```
~/.claude/projects/<project>/memory/
├── MEMORY.md              # Index (loaded at startup)
├── user_role.md           # Topic file (loaded on demand)
├── feedback_testing.md    # Topic file
└── project_architecture.md
```

**Limits:**
- `MEMORY.md`: first 200 lines or 25 KB loaded; agent warns if approaching
- Topic files: loaded on demand (no limit)
- Main conversation auto memory: not loaded into subagents (exception: forks inherit parent's)

---

## Summary Table: Frontmatter Across Subagents, Skills, and Settings

| Concept | Location | Trigger | Invocation |
|---------|----------|---------|-----------|
| **Subagent definition** | `.claude/agents/agent-name.md` or `~/.claude/agents/...` | `description:` field | Claude delegation, `/resume`, `@"name (agent)"` mention, `--agent` flag |
| **Skill** | `.claude/skills/skill-name/SKILL.md` | `description:` for auto; `disable-model-invocation:` to block | `/skill-name` or Claude auto-invoke |
| **Fork skill (in subagent)** | `.claude/skills/skill-name/SKILL.md` with `context: fork` + `agent:` | Same as skill | Same as skill |
| **Workflow** | `.claude/workflows/script-name.js` with `export const meta` | `/script-name` or `ultracode` keyword | Manual invocation or ultracode |
| **Hook** | settings.json `hooks:` or `.claude/hooks/` + subagent frontmatter | Lifecycle event | Automatic at event |

---

## Key URLs for Reference

1. Subagents — https://code.claude.com/docs/en/sub-agents.md
2. Skills — https://code.claude.com/docs/en/skills.md
3. Settings Reference — https://code.claude.com/docs/en/settings-reference.md
4. Memory — https://code.claude.com/docs/en/memory.md
5. Hooks Guide — https://code.claude.com/docs/en/hooks-guide.md
6. Agent Teams — https://code.claude.com/docs/en/agent-teams.md
7. Workflows — https://code.claude.com/docs/en/workflows.md
8. Memory / Agent Memory — https://code.claude.com/docs/en/memory.md#enable-persistent-memory
