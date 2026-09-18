package com.campusmeal.android.core.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAuthorizationHeaderProviderTest {

    private val storage = InMemorySessionStorage()
    private val provider = SessionAuthorizationHeaderProvider(storage)

    @Test
    fun buildsBearerHeaderFromAccessTokenOnly() = runTest {
        storage.save(SessionTokens(accessToken = "access-value", refreshToken = "refresh-value"))

        val header = provider.getAuthorizationHeader()

        assertTrue("Unexpected Authorization value", header == "Bearer access-value")
    }

    @Test
    fun returnsNullWithoutSession() = runTest {
        assertNull(provider.getAuthorizationHeader())
    }

    @Test
    fun returnsNullForBlankAccessToken() = runTest {
        storage.save(SessionTokens(accessToken = " ", refreshToken = "refresh-value"))

        assertNull(provider.getAuthorizationHeader())
    }
}
