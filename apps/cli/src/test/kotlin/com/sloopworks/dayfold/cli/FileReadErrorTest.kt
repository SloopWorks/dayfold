package com.sloopworks.dayfold.cli

import java.io.IOException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals

class FileReadErrorTest {
  @Test fun `a missing file is reported by name, not as a stack trace`() {
    assertEquals("file not found: card.json", fileReadError("card.json", NoSuchFileException("card.json")))
  }

  @Test fun `a generic IO failure includes the path and the cause`() {
    assertEquals("could not read card.json: disk on fire", fileReadError("card.json", IOException("disk on fire")))
  }

  @Test fun `an IO failure with no message still names the path`() {
    assertEquals("could not read card.json: I/O error", fileReadError("card.json", IOException()))
  }
}
