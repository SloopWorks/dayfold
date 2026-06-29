package com.sloopworks.dayfold.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// `isOlder` drives the `dayfold update` nudge ("X is up to date" vs "a newer stable exists").
// It compares X.Y.Z components NUMERICALLY — the property that matters and the one a naive
// rewrite would break (string compare makes "1.10.0" < "1.2.0"). Pin it, plus the fail-safe:
// a non-semver string never reports "older", so the CLI never nags on a dev/edge build.
class UpdateVersionTest {
  @Test fun `numeric component compare not lexical, so 1-10-0 is newer than 1-2-0`() {
    assertTrue(isOlder("1.2.0", "1.10.0"))    // a string compare would wrongly say false
    assertFalse(isOlder("1.10.0", "1.2.0"))   // and wrongly say true
  }

  @Test fun `older across each component`() {
    assertTrue(isOlder("0.9.9", "1.0.0"))     // major
    assertTrue(isOlder("1.1.9", "1.2.0"))     // minor
    assertTrue(isOlder("1.2.3", "1.2.4"))     // patch
  }

  @Test fun `equal or newer is not older`() {
    assertFalse(isOlder("1.2.3", "1.2.3"))    // equal
    assertFalse(isOlder("2.0.0", "1.9.9"))    // newer major
    assertFalse(isOlder("1.2.4", "1.2.3"))    // newer patch
  }

  @Test fun `a non-semver string never reports older — fail-safe, no nag on dev or edge builds`() {
    assertFalse(isOlder("dev", "1.2.3"))
    assertFalse(isOlder("1.2", "1.2.3"))      // 2-component → not SEMVER
    assertFalse(isOlder("1.2.3", "v1.2.4"))   // leading 'v' → not SEMVER (tag prefix is stripped upstream)
    assertFalse(isOlder("1.2.3", ""))
  }
}
