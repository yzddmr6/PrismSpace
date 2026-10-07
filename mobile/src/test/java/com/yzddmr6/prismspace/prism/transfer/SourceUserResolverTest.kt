package com.yzddmr6.prismspace.prism.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceUserResolverTest {

    private fun probes(vararg results: ProbeResult): (Int) -> ProbeResult {
        val asked = mutableListOf<Int>()
        return { index -> asked += index; results.getOrElse(index) { ProbeResult.NotFound } }
    }

    @Test fun userQualifiedAuthorityNamesTheSourceUser() {
        assertEquals(10, userIdFromAuthority("10@media"))
        assertNull(userIdFromAuthority("media"))
        assertNull(userIdFromAuthority("@media"))
        assertNull(userIdFromAuthority("x@media"))
        assertNull(userIdFromAuthority(null))

        assertEquals(10 to SourceUserRule.QualifiedAuthority, resolveSourceUser("10@media", 0, 10, probes(ProbeResult.Readable)))
    }

    @Test fun qualifiedUriIsNeverRequalified() {
        val asked = mutableListOf<Int>()
        val result = resolveSourceUser("10@media", 0, 22) { asked += it; ProbeResult.Denied }

        assertNull(result)
        assertEquals(listOf(0), asked)
    }

    @Test fun readableReceivedUriBelongsToTheCurrentUser() {
        assertEquals(0 to SourceUserRule.CurrentUser, resolveSourceUser("media", 0, 22, probes(ProbeResult.Readable)))
    }

    @Test fun pairedUserQualificationIsTriedWhenTheReceivedUriIsNotReadable() {
        assertEquals(
            22 to SourceUserRule.PairedUser,
            resolveSourceUser("media", 0, 22, probes(ProbeResult.Denied, ProbeResult.Readable)),
        )
    }

    @Test fun emptyAnswerDoesNotBeatARealRowInThePairedUser() {
        // MediaProvider hides rows the caller cannot see instead of throwing.
        assertEquals(
            22 to SourceUserRule.PairedUser,
            resolveSourceUser("media", 0, 22, probes(ProbeResult.Empty, ProbeResult.Readable)),
        )
        assertEquals(
            0 to SourceUserRule.CurrentUser,
            resolveSourceUser("provider", 0, 22, probes(ProbeResult.Empty, ProbeResult.Denied)),
        )
        assertEquals(
            22 to SourceUserRule.PairedUser,
            resolveSourceUser("provider", 0, 22, probes(ProbeResult.NotFound, ProbeResult.Empty)),
        )
    }

    @Test fun nothingReadableIsUnreadable() {
        assertNull(resolveSourceUser("media", 0, 22, probes(ProbeResult.Denied, ProbeResult.Denied)))
        assertNull(resolveSourceUser("media", 0, null, probes(ProbeResult.Denied)))
    }

    @Test fun withoutPairedUserOnlyTheReceivedUriIsProbed() {
        val asked = mutableListOf<Int>()
        resolveSourceUser("media", 0, null) { asked += it; ProbeResult.Denied }

        assertEquals(listOf(0), asked)
    }

    @Test fun sourceAuthorityFallbackIsLimitedToOneVerifiedPairedUser() {
        assertEquals(
            "18@com.android.fileexplorer.myprovider",
            SourceUriPlanner.qualifiedAuthority("com.android.fileexplorer.myprovider", 18),
        )
        assertEquals(null, SourceUriPlanner.qualifiedAuthority("18@com.android.fileexplorer.myprovider", 0))
        assertEquals(null, SourceUriPlanner.qualifiedAuthority("com.example.provider", null))
        assertEquals(null, SourceUriPlanner.qualifiedAuthority("com.example.provider", -1))
        assertEquals(null, SourceUriPlanner.qualifiedAuthority(null, 18))
    }
}
