package com.hsd.universal

import org.junit.Assert.*
import org.junit.Test

class ActivationExitEvidenceTest {
    @Test fun matchesOnlyLiveAccessInTheKilledProcessOfThisInstallationAndBoot() {
        assertTrue(ActivationExitEvidence.matches(25156, 3000, 25156, true, 2900, 1000))
        assertFalse(ActivationExitEvidence.matches(25156, 3000, 37, true, 2900, 1000))
        assertFalse(ActivationExitEvidence.matches(25156, 3000, 25156, false, 2900, 1000))
        assertFalse(ActivationExitEvidence.matches(25156, 3000, 25156, true, 900, 1000))
        assertFalse(ActivationExitEvidence.matches(25156, 3000, 25156, true, 3100, 1000))
    }
}
