package com.campusmeal.android.core.session

import com.campusmeal.android.feature.auth.domain.AuthRepository
import com.campusmeal.android.feature.auth.domain.RefreshOutcome
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Recovers protected requests after an expired access token.
 *
 * Flow:
 *
 * request
 *   → HTTP 401
 *   → single-flight refresh of the session that sent the request
 *   → Refreshed: retry the original request once with the new token
 *       → still 401: that session is expired (only that one)
 *   → TemporarilyUnavailable: throw [SessionRefreshUnavailableException] (offline path, data kept)
 *   → Rejected: the session was ended; the 401 reaches the caller
 *   → NotCurrentSession: the request belongs to an ended session; the 401 reaches the caller
 *
 * HTTP 403 is an authorization answer, not an expired session: it is never refreshed.
 *
 * `runBlocking` runs on OkHttp's thread, never on the main thread, and only waits for the refresh.
 */
class SessionRefreshInterceptor(
    private val authRepository:
        () -> AuthRepository,
) : Interceptor {

    override fun intercept(
        chain: Interceptor.Chain,
    ): Response {

        val request =
            chain.request()

        val response =
            chain.proceed(request)

        val rejectedAccessToken =
            request.header("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        if (
            response.code != 401 ||
            rejectedAccessToken == null ||
            isCredentialEndpoint(request.url.encodedPathSegments)
        ) {
            return response
        }

        val outcome =
            runBlocking {
                authRepository()
                    .refreshSession(
                        rejectedAccessToken =
                            rejectedAccessToken,
                    )
            }

        return when (outcome) {

            is RefreshOutcome.Refreshed -> {
                /*
                 * We are going to retry, therefore the original response
                 * must be closed.
                 */
                response.close()

                val retryResponse =
                    chain.proceed(
                        request
                            .newBuilder()
                            .header("Authorization", outcome.authorization)
                            .build(),
                    )

                /*
                 * Only one retry occurs here. A 401 with freshly refreshed tokens means the backend
                 * rejects this session: expire it (and only it, by generation) instead of looping.
                 */
                if (retryResponse.code == 401) {
                    runBlocking {
                        authRepository().expireSession(outcome.generation)
                    }
                }

                retryResponse
            }

            RefreshOutcome.TemporarilyUnavailable -> {
                response.close()
                throw SessionRefreshUnavailableException()
            }

            RefreshOutcome.Rejected,
            RefreshOutcome.NotCurrentSession ->
                response
        }
    }

    /**
     * Login, registration, refresh and logout give 401 its own meaning and must never trigger a
     * refresh. Every other route under `auth/`, such as `GET auth/me`, is a protected call.
     */
    private fun isCredentialEndpoint(segments: List<String>): Boolean {
        val auth = segments.lastIndexOf("auth")
        return auth >= 0 && auth == segments.lastIndex - 1 && segments.last() in CREDENTIAL_ROUTES
    }

    private companion object {
        val CREDENTIAL_ROUTES = setOf("login", "register", "refresh", "logout")
    }
}
