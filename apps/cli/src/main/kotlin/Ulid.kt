package com.sloopworks.dayfold.cli

import kotlin.random.Random

/**
 * ULID minter (ADR 0038). Checklist item ids are **client-minted** — the server
 * can't mint them at M1 because the payload is ciphertext (it never sees the items).
 * The CLI/skill/app each own minting; this is the CLI's.
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

  /** Mint a fresh ULID from the wall clock. */
  fun next(): String = encode(System.currentTimeMillis(), Random.Default)
}
