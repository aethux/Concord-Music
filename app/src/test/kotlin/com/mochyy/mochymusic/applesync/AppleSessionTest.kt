package com.mochyy.mochymusic.applesync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt is only as good as this mapping: a 401 from any read or write is wrapped as
 * AppleSyncException(signedOut = true), and that is the one thing the account row listens for.
 */
class AppleSessionTest {

    @Test
    fun aSignedOutFailureExpiresTheSession() {
        AppleSession.clear()
        AppleSession.onFailure(AppleSyncException("Apple answered 401", signedOut = true))
        assertTrue(AppleSession.expired.value)
    }

    @Test
    fun anOrdinaryFailureLeavesItAlone() {
        AppleSession.clear()
        AppleSession.onFailure(AppleSyncException("Apple answered 500"))
        assertFalse(AppleSession.expired.value)
    }

    @Test
    fun aGoodRunClearsIt() {
        AppleSession.onFailure(AppleSyncException("Apple answered 401", signedOut = true))
        AppleSession.clear()
        assertFalse(AppleSession.expired.value)
    }
}
