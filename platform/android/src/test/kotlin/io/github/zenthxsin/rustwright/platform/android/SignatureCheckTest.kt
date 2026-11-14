package io.github.zenthxsin.rustwright.platform.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureCheckTest {
    private val releaseId = "io.github.workbench"
    private val debugId = "$releaseId.debug"

    @Test fun manifestDebuggableCannotEnableReleaseBypass() {
        assertFalse(SignatureCheck.developmentBuild(false, releaseId, true))
        assertFalse(SignatureCheck.developmentBuild(false, debugId, true))
        assertFalse(SignatureCheck.developmentBuild(true, releaseId, true))
    }

    @Test fun developmentBypassRequiresAllThreeConditions() {
        assertTrue(SignatureCheck.developmentBuild(true, debugId, true))
        assertFalse(SignatureCheck.developmentBuild(true, debugId, false))
        assertFalse(SignatureCheck.developmentBuild(true, "other.debug", true))
    }

    @Test fun onlyTheSinglePinnedCurrentSignerIsTrusted() {
        val official = "a".repeat(64)
        val foreign = "b".repeat(64)
        assertTrue(SignatureCheck.trustedSigners(listOf(official), official))
        assertFalse(SignatureCheck.trustedSigners(emptyList(), official))
        assertFalse(SignatureCheck.trustedSigners(listOf(foreign), official))
        assertFalse(SignatureCheck.trustedSigners(listOf(foreign, official), official))
        assertFalse(SignatureCheck.trustedSigners(listOf(official, foreign), official))
        assertFalse(SignatureCheck.trustedSigners(listOf(""), ""))
    }
}
