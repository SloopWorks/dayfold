# Raw agent outputs — subagent practices review (2026-09-02)

Per `processes/research-workflow.md` step 5, the per-agent outputs behind
`research/2026-09-02-subagent-practices-review.md`. Two research subagents,
run in parallel from the same session; text is as returned (angle brackets
the harness had escaped are restored; nothing else edited). Session-review
statistics in the synthesis came from the main session's own `git log` /
GitHub PR-list analysis, not from an agent.

- `official-docs.md` — `claude-code-guide` agent: the official Claude Code
  subagent/skills/settings/memory/hooks/teams/workflows contract, with URLs.
  ~65K tokens, 10 tool uses.
- `community-practices.md` — `general-purpose` agent with web search:
  community collections, Anthropic engineering posts, GitHub issues, failure
  modes, placement/sync patterns. ~170K tokens, 82 tool uses.
