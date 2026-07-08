package com.sloopworks.dayfold.client

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatCountdownTest {
  @Test fun `formats M SS floored at zero`() {
    assertEquals("14:32", formatCountdown(14 * 60 + 32))
    assertEquals("1:05", formatCountdown(65))
    assertEquals("0:00", formatCountdown(-3))
  }
}

@OptIn(ExperimentalTestApi::class)
class InviteScreenTest {
  private val fixedNow = kotlin.time.Instant.parse("2026-07-07T00:00:00Z")

  // onMint defaults to {} so the auto-mint LaunchedEffect is a no-op — no loop.
  @Test fun `shows the title`() = runComposeUiTest {
    setContent { DayfoldTheme { InviteScreen(AppState(route = Route.Invite, inviteMode = "qr"), now = fixedNow) } }
    onNodeWithText("Invite a member").assertExists()
  }

  @Test fun `shows the QR when a qr invite is minted`() = runComposeUiTest {
    val s = AppState(
      route = Route.Invite, inviteMode = "qr",
      mintedInvite = MintedInvite("i", "TOK", "https://x/invite/TOK", "adult", "qr", "2099-01-01T00:00:00Z"),
    )
    setContent { DayfoldTheme { InviteScreen(s, now = fixedNow) } }
    onNodeWithContentDescription("Invite QR code").assertExists()
  }

  @Test fun `shows the copy-link button for a link invite`() = runComposeUiTest {
    val s = AppState(
      route = Route.Invite, inviteMode = "link",
      mintedInvite = MintedInvite("i", "TOK", "https://x/invite/TOK", "adult", "link", "2099-01-01T00:00:00Z"),
    )
    setContent { DayfoldTheme { InviteScreen(s, now = fixedNow) } }
    onNodeWithText("Copy link").assertExists()
  }

  @Test fun `shows revoke for an outstanding invite`() = runComposeUiTest {
    val s = AppState(
      route = Route.Invite,
      outstandingInvites = listOf(Invite(id = "inv1", mode = "link", maxUses = 5, usedCount = 0, expiresAt = "2099-01-01T00:00:00Z")),
    )
    setContent { DayfoldTheme { InviteScreen(s, now = fixedNow) } }
    onNodeWithText("Revoke").assertExists()
  }

  @Test fun `shows a rate-limit error with retry`() = runComposeUiTest {
    setContent { DayfoldTheme { InviteScreen(AppState(route = Route.Invite, mintError = "ratelimited"), now = fixedNow) } }
    onNodeWithText("Try again").assertExists()
  }
}
