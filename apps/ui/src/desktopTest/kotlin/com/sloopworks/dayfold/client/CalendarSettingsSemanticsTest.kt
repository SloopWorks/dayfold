package com.sloopworks.dayfold.client

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.sloopworks.dayfold.client.features.calendar.CalendarSettingsOnScreen
import com.sloopworks.dayfold.client.theme.DayfoldTheme
import kotlin.test.Test

// WI-447 (ADR 0063 §1/§8/§31) — settings-on semantics smoke: calendar names and their MASKED
// account labels must actually be announced (accessibility + visible text), not just present in
// the golden PNG. Mirrors ProximitySettingsTest's semantics-assertion style.
@OptIn(ExperimentalTestApi::class)
class CalendarSettingsSemanticsTest {
  private val calendars = listOf(
    DeviceCalendar("family", "Family", "#7B9E6B", "Google · p•••@gmail.com"),
    DeviceCalendar("personal", "Personal", "#5B7DB1", "Google · p•••@gmail.com"),
  )

  @Test fun `settings-on announces calendar names and masked accounts`() = runComposeUiTest {
    setContent {
      DayfoldTheme {
        Column {
          CalendarSettingsOnScreen(
            includedCalendars = calendars,
            lastCheckLabel = "Today, 8:02 AM",
            onBack = {}, onToggleOff = {}, onChangeCalendars = {}, onEventTimeAlerts = {}, onResetLocalMatches = {}, onTurnOff = {},
          )
        }
      }
    }
    onNodeWithText("Family").assertIsDisplayed()
    onNodeWithText("Personal").assertIsDisplayed()
    onAllNodesWithText("Google · p•••@gmail.com").assertCountEquals(2)
  }
}
