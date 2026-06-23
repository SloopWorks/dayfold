package com.sloopworks.dayfold.cli

// AUTH-S6-D step 7b — the CLI's long-lived (45-day) refresh token belongs in the
// OS keychain, not plaintext `~/.config/dayfold/credentials.json`. The access
// token + non-secret config stay in the file (0600). On a machine with no
// keychain (headless/CI), the file fallback is allowed only behind
// `--allow-env-key` + a loud warning (07-cli DX).
//
// This is a plain-JVM CLI (one target) → runtime OS detection, not expect/actual.
// macOS = Keychain via `/usr/bin/security`; Linux = libsecret via `secret-tool`;
// other/absent = none (caller falls back to the file or refuses).

private const val SERVICE = "dayfold-cli"
internal const val KEYCHAIN_ACCOUNT = "refresh-token"

interface SecretStore {
  fun get(account: String): String?
  fun put(account: String, secret: String)
  fun delete(account: String)
}

// Result of running an external command.
internal data class ProcResult(val code: Int, val stdout: String)

// Injectable so the keychain impls are unit-testable without touching the OS.
internal fun interface CommandRunner {
  fun run(cmd: List<String>, stdin: String?): ProcResult
}

internal val realRunner = CommandRunner { cmd, stdin ->
  val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
  if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()); it.flush() }
  val out = p.inputStream.readBytes().decodeToString()
  p.errorStream.readBytes()                       // drain so the child never blocks on a full stderr pipe
  ProcResult(p.waitFor(), out)
}

private fun commandExists(name: String, runner: CommandRunner): Boolean =
  runCatching { runner.run(listOf("/bin/sh", "-c", "command -v $name"), null).code == 0 }.getOrDefault(false)

// macOS Keychain via the `security` tool. NOTE: `-w <secret>` puts the secret in
// argv (visible to `ps` on a multi-user host) — acceptable on a single-user dev
// Mac; hardening to a stdin/file handoff is a follow-up. find/delete are quiet.
internal class MacKeychain(private val runner: CommandRunner = realRunner) : SecretStore {
  fun available(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac") &&
      java.io.File("/usr/bin/security").exists()

  override fun get(account: String): String? {
    val r = runner.run(listOf("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-w"), null)
    return if (r.code == 0) r.stdout.trim().ifEmpty { null } else null
  }

  override fun put(account: String, secret: String) {
    // -U updates an existing item instead of erroring on duplicate.
    val r = runner.run(listOf("/usr/bin/security", "add-generic-password", "-U", "-s", SERVICE, "-a", account, "-w", secret), null)
    if (r.code != 0) error("keychain write failed (security exit ${r.code})")
  }

  override fun delete(account: String) {
    runner.run(listOf("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", account), null)
  }
}

// Linux Secret Service via libsecret's `secret-tool`. The secret is fed on stdin
// (no argv exposure).
internal class LibSecret(private val runner: CommandRunner = realRunner) : SecretStore {
  fun available(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().let { it.contains("linux") || it.contains("nix") } &&
      commandExists("secret-tool", runner)

  override fun get(account: String): String? {
    val r = runner.run(listOf("secret-tool", "lookup", "service", SERVICE, "account", account), null)
    return if (r.code == 0) r.stdout.trim().ifEmpty { null } else null
  }

  override fun put(account: String, secret: String) {
    val r = runner.run(listOf("secret-tool", "store", "--label=Dayfold CLI", "service", SERVICE, "account", account), secret)
    if (r.code != 0) error("keychain write failed (secret-tool exit ${r.code})")
  }

  override fun delete(account: String) {
    runner.run(listOf("secret-tool", "clear", "service", SERVICE, "account", account), null)
  }
}

/** The OS keychain for this host, or null if none is available. */
internal fun resolveKeychain(runner: CommandRunner = realRunner): SecretStore? =
  MacKeychain(runner).takeIf { it.available() }
    ?: LibSecret(runner).takeIf { it.available() }

// ── Creds ⇄ keychain glue (keeps the refresh token out of the file) ──
// With a keychain: the refresh token lives in the keychain and the file's
// refreshToken is blanked. Without one: the file holds it (legacy / --allow-env-key).

internal fun saveCreds(store: Credentials, keychain: SecretStore?, creds: Creds) {
  if (keychain != null) {
    keychain.put(KEYCHAIN_ACCOUNT, creds.refreshToken)
    store.save(creds.copy(refreshToken = ""))     // file keeps access + config only
  } else {
    store.save(creds)
  }
}

internal fun loadCreds(store: Credentials, keychain: SecretStore?): Creds? {
  val c = store.load() ?: return null
  if (keychain == null) return c
  // keychain wins; fall back to a non-blank file value (migration from a pre-7b file)
  val refresh = keychain.get(KEYCHAIN_ACCOUNT)?.takeIf { it.isNotEmpty() } ?: c.refreshToken
  return c.copy(refreshToken = refresh)
}

internal fun deleteCreds(store: Credentials, keychain: SecretStore?) {
  store.delete()
  keychain?.delete(KEYCHAIN_ACCOUNT)
}
