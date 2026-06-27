package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertTrue

// `dayfold help` / -h / --help prints USAGE to stdout + exits 0 (help is not an error);
// misuse prints it to stderr + exits 2. The text must list every command.
class UsageTest {
  @Test fun `USAGE lists every command incl the destructive + update ones`() {
    // delete (#180) + update (ADR 0037) were added after help shipped — assert them
    // explicitly so a future command can't land undiscoverable (delete is destructive).
    listOf("login", "logout", "whoami", "push", "pull", "template", "delete", "update", "version", "help")
      .forEach { assertTrue(USAGE.contains(it), "USAGE missing \"$it\"") }
    assertTrue(USAGE.startsWith("usage: dayfold"))
  }

  @Test fun `USAGE explains content-modifying push behavior (--no-linkify)`() {
    // push auto-rewrites body_md phone/email into links (#196) — content-modifying, so the
    // help must explain it + the opt-out, not just list the flag in the syntax line.
    assertTrue(USAGE.contains("--no-linkify"), "USAGE missing the --no-linkify opt-out")
    assertTrue(USAGE.contains("auto-linked"), "USAGE doesn't explain the auto-link default")
  }
}
