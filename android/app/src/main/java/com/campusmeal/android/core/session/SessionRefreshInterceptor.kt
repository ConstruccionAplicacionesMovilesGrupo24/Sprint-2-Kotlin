package com.campusmeal.android.core.session

import com.campusmeal.android.feature.auth.domain.AuthRepository
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
 *   → refresh once
 *   → obtain new access token
 *   → retry original request once
 *
 * Authentication endpoints are excluded to avoid refresh loops.
 */
class SessionRefreshInterceptor(
    private val authRepository:
        () -> AuthRepository,
    private val authorizationHeaderProvider:
        () -> AuthorizationHeaderProvider,
) : Interceptor {

    override fun intercept(
        chain: Interceptor.Chain,
    ): Response {

        val request =
            chain.request()

        val response =
            chain.proceed(request)

        /*
         * Login/register/refresh 401 responses have their own
         * meaning and must never trigger another refresh.
         */
        val authEndpoint =
            request.url
                .encodedPath
                .contains("/auth/")

        val authorization =
            request.header(
                "Authorization",
            )

        if (
            response.code != 401 ||
            authorization == null ||
            authEndpoint
        ) {
            return response
        }

        val rejectedAccessToken =
            authorization
                .removePrefix(
                    "Bearer ",
                )
                .trim()
                .takeIf {
                    it.isNotBlank()
                }

        val refreshed =
            runBlocking {
                authRepository()
                    .refreshSession(
                        rejectedAccessToken =
                            rejectedAccessToken,
                    )
            }

        if (!refreshed) {
            return response
        }

        val newAuthorization =
            runBlocking {
                authorizationHeaderProvider()
                    .getAuthorizationHeader()
            } ?: return response

        /*
         * We are going to retry, therefore the original response
         * must be closed.
         */
        response.close()

        val retryRequest =
            request
                .newBuilder()
                .header(
                    "Authorization",
                    newAuthorization,
                )
                .build()

        /*
         * Only one retry occurs here.
         * If the new token also receives HTTP 401, that response is
         * returned to the caller.
         */
        return chain.proceed(
            retryRequest,
        )
    }
}