package com.sloopworks.dayfold.client

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * ULID minter (ADR 0038). SHARED (packages/linkrules, srcDir'd into client commonMain
 * AND the CLI) — checklist item ids / egress `op_id`s are **client-minted** (the server
 * can't mint them at M1 because the payload is ciphertext) so the CLI and app must mint
 * byte-identical ids; a single source keeps them interchangeable.
 *
 * Output is Crockford base32, 26 chars (10-char 48-bit time prefix + 16-char 80-bit
 * randomness), matching the schema's `$defs.ulid` pattern `^[0-9A-HJKMNP-TV-Z]{26}$`.
 * The time prefix makes ids lexicographically time-sortable; the random tail makes
 * collision across independent minters negligible.
 */
object Ulid {
  // Crockford base32 — excludes I, L, O, U (matches the schema ulid pattern exactly).
  private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
  private const val TIME_LEN = 10
  private const val RANDOM_LEN = 16

  /** Pure encoder — deterministic for a fixed [timeMs] + [random]; the seam tests use. */
  fun encode(timeMs: Long, random: Random): String {
    require(timeMs >= 0 && timeMs < (1L shl 48)) { "timeMs out of 48-bit range: $timeMs" }
    val sb = StringBuilder(TIME_LEN + RANDOM_LEN)
    var t = timeMs
    val time = CharArray(TIME_LEN)
    for (i in TIME_LEN - 1 downTo 0) {
      time[i] = ENCODING[(t and 0x1F).toInt()]
      t = t ushr 5
    }
    sb.append(time)
    repeat(RANDOM_LEN) { sb.append(ENCODING[random.nextInt(ENCODING.length)]) }
    return sb.toString()
  }

  /** Mint a fresh ULID from the wall clock (kotlin.time.Clock — KMP-portable, no JVM API). */
  @OptIn(ExperimentalTime::class)
  fun next(): String = encode(Clock.System.now().toEpochMilliseconds(), Random.Default)
}
