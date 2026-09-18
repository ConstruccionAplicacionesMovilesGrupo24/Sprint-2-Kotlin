package com.campusmeal.android.feature.auth.data

import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.SessionStorage
import com.campusmeal.android.core.session.SessionTokens
import com.campusmeal.android.feature.auth.domain.AuthRepository
import com.campusmeal.android.feature.auth.domain.AuthResult
import com.campusmeal.android.feature.auth.domain.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/*
 * INTERIM authentication data layer, written so the Issue #5 screens can run end to end. It covers
 * login, registration, logout and session expiry only. The full layer — refresh after HTTP 401,
 * coordination of simultaneous 401s, Keystore-backed storage, `GET me` and restoration at start —
 * replaces this file behind the same `AuthRepository` contract.
 */

interface AuthApi {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequestDto): TokenResponseDto

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequestDto): TokenResponseDto

    @POST("auth/logout")
    suspend fun logout(@Header("Authorization") authorization: String, @Body request: LogoutRequestDto)
}

@Serializable
data class LoginRequestDto(val email: String, val password: String)

@Serializable
data class RegisterRequestDto(val fullName: String, val email: String, val password: String)

@Serializable
data class LogoutRequestDto(val refreshToken: String)

/** Provisional: registration is assumed to sign the user in, like login. */
@Serializable
data class TokenResponseDto(val accessToken: String, val refreshToken: String) {
    override fun toString(): String = "TokenResponseDto(<redacted>)"
}

class NetworkAuthRepository(
    private val api: AuthApi,
    private val storage: SessionStorage,
) : AuthRepository {

    private val expired = MutableStateFlow(false)

    override val sessionStatus: Flow<SessionStatus> = combine(storage.session, expired) { tokens, isExpired ->
        when {
            isExpired -> SessionStatus.EXPIRED
            tokens != null -> SessionStatus.AUTHENTICATED
            else -> SessionStatus.SIGNED_OUT
        }
    }

    // The password only travels in the request body; it is never stored or kept in a field.
    override suspend fun login(email: String, password: String): AuthResult =
        authenticate { api.login(LoginRequestDto(email, password)) }

    override suspend fun register(fullName: String, email: String, password: String): AuthResult =
        authenticate { api.register(RegisterRequestDto(fullName, email, password)) }

    override suspend fun logout() {
        val tokens = storage.session.first()
        if (tokens != null) {
            // Best effort: the local session is removed even if the backend cannot be reached.
            apiCall { api.logout("Bearer ${tokens.accessToken}", LogoutRequestDto(tokens.refreshToken)) }
        }
        storage.clear()
        expired.value = false
    }

    override suspend fun expireSession() {
        // Ignore late 401s for a session that is already gone, so logout never turns into "expired".
        if (storage.session.first() == null) return
        storage.clear()
        expired.value = true
    }

    override fun acknowledgeExpiredSession() {
        expired.value = false
    }

    private suspend fun authenticate(call: suspend () -> TokenResponseDto): AuthResult =
        when (val response = apiCall(call)) {
            is ApiResult.Success -> {
                val tokens = response.data
                if (tokens.accessToken.isBlank() || tokens.refreshToken.isBlank()) {
                    AuthResult.Unavailable(null)
                } else {
                    storage.save(SessionTokens(tokens.accessToken, tokens.refreshToken))
                    expired.value = false
                    AuthResult.Success
                }
            }
            ApiResult.Unauthorized -> AuthResult.InvalidCredentials
            is ApiResult.HttpError -> when (response.code) {
                409 -> AuthResult.EmailAlreadyRegistered
                400, 422 -> AuthResult.RejectedInput
                else -> AuthResult.Unavailable(response.code)
            }
            is ApiResult.NetworkUnavailable -> AuthResult.Unavailable(null)
            is ApiResult.InvalidResponse -> AuthResult.Unavailable(null)
        }
}

/**
 * Ends the session when an authenticated request is rejected with HTTP 401. `auth/` calls are
 * excluded: a 401 there means wrong credentials, not an expired session. The token refresh belongs
 * before this step: only a request that is still rejected after it should end the session.
 */
class SessionExpiryInterceptor(private val onSessionRejected: suspend () -> Unit) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val authenticated = request.header("Authorization") != null
        val authEndpoint = request.url.encodedPath.contains("/auth/")
        if (response.code == 401 && authenticated && !authEndpoint) {
            // OkHttp runs interceptors on its own background threads, so blocking here is safe.
            runBlocking { onSessionRejected() }
        }
        return response
    }
}
