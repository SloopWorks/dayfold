package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertTrue

// `dayfold help` / -h / --help prints the index (USAGE) to stdout + exits 0 (help is not an
// error); misuse prints it to stderr + exits 2. The index must list every command.
class UsageTest {
  @Test fun `USAGE lists every command incl the destructive + update ones`() {
    // delete (#180) + update (ADR 0037) were added after help shipped — assert them
    // explicitly so a future command can't land undiscoverable (delete is destructive).
    listOf("login", "logout", "whoami", "push", "pull", "template", "delete", "update", "version", "help")
      .forEach { assertTrue(USAGE.contains(it), "USAGE missing \"$it\"") }
    assertTrue(USAGE.startsWith("usage: dayfold"))
  }

  @Test fun `push help explains the content-modifying auto-link behavior (--no-linkify)`() {
    // push auto-rewrites body_md phone/email into links (#196) — content-modifying, so the
    // help must explain it + the opt-out. This detail now lives in the push command help
    // (the index stays a one-line-per-command summary).
    val push = renderCommand(commandByToken("push")!!)
    assertTrue(push.contains("--no-linkify"), "push help missing the --no-linkify opt-out")
    assertTrue(push.contains("auto-wrap"), "push help doesn't explain the auto-link default")
  }
}
