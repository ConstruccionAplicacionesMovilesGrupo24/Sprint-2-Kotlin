package com.campusmeal.android

import com.campusmeal.android.core.common.UiState
import com.campusmeal.android.core.network.NetworkConfig
import com.campusmeal.android.core.session.InMemorySessionStorage
import com.campusmeal.android.core.session.SessionRepository
import com.campusmeal.android.core.session.SessionTokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationSmokeTest {

    @Test
    fun uiStateRepresentsEveryFoundationState() {
        val states: List<UiState<String>> = listOf(
            UiState.Initial,
            UiState.Loading,
            UiState.Content("menu"),
            UiState.Empty,
            UiState.OfflineWithCache("cached menu", lastUpdatedEpochMillis = null),
            UiState.Error(message = "Network failure"),
            UiState.Unauthorized,
            UiState.PermissionDenied(listOf("android.permission.ACCESS_COARSE_LOCATION")),
        )

        val labels = states.map { state ->
            when (state) {
                UiState.Initial -> "initial"
                UiState.Loading -> "loading"
                is UiState.Content -> "content"
                UiState.Empty -> "empty"
                is UiState.OfflineWithCache -> "offline"
                is UiState.Error -> "error"
                UiState.Unauthorized -> "unauthorized"
                is UiState.PermissionDenied -> "permission"
            }
        }

        assertEquals(states.size, labels.toSet().size)
    }

    @Test
    fun sessionStorageRoundTripsWithoutExposingTokens() = runTest {
        val storage = InMemorySessionStorage()
        val repository = SessionRepository(storage)
        val tokens = SessionTokens(accessToken = "access-secret", refreshToken = "refresh-secret")

        assertFalse(repository.isAuthenticated.first())

        storage.save(tokens)
        assertEquals(tokens, storage.session.first())
        assertTrue(repository.isAuthenticated.first())
        assertFalse(tokens.toString().contains("secret"))

        repository.signOut()
        assertNull(storage.session.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun networkConfigRejectsMissingBaseUrl() {
        NetworkConfig(baseUrl = "", httpLoggingEnabled = false)
    }
}
