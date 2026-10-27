package com.campusmeal.android.core.session

import kotlinx.coroutines.flow.first

/**
 * Supplies the `Authorization` header value for CampusMeal API calls. Features depend on this
 * instead of [SessionStorage], so they never see the refresh token.
 *
 * The header is only returned while the expected session is still active, so an operation that
 * started under one account can never be sent with another account's token.
 *
 * The returned value contains the access token: never log it or include it in exceptions.
 */
interface AuthorizationHeaderProvider {

    /** Returns `Bearer <access-token>` while [scope] is active, or null otherwise. */
    suspend fun getAuthorizationHeader(scope: AccountSessionScope): String?

    /** For a session that may not be identified yet (`auth/me` during identification). */
    suspend fun getAuthorizationHeader(generation: Long): String?
}

class SessionAuthorizationHeaderProvider(
    private val sessionStorage: SessionStorage,
    private val coordinator: AccountSessionCoordinator,
) : AuthorizationHeaderProvider {

    override suspend fun getAuthorizationHeader(scope: AccountSessionScope): String? =
        coordinator.runIfCurrent(scope) { currentHeader() }

    override suspend fun getAuthorizationHeader(generation: Long): String? =
        coordinator.runIfGeneration(generation) { currentHeader() }

    private suspend fun currentHeader(): String? =
        sessionStorage.session.first()
            ?.accessToken
            ?.takeIf { it.isNotBlank() }
            ?.let { "Bearer $it" }
}
