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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApi {

    @POST("auth/login")
    suspend fun login(
        @Body request: LoginRequestDto,
    ): TokenResponseDto

    @POST("auth/register")
    suspend fun register(
        @Body request: RegisterRequestDto,
    ): TokenResponseDto

    @POST("auth/refresh")
    suspend fun refresh(
        @Body request: RefreshRequestDto,
    ): TokenResponseDto

    @POST("auth/logout")
    suspend fun logout(
        @Header("Authorization")
        authorization: String,
        @Body request: LogoutRequestDto,
    )
}

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
)

@Serializable
data class RegisterRequestDto(
    val fullName: String,
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequestDto(
    val refreshToken: String,
)

@Serializable
data class LogoutRequestDto(
    val refreshToken: String,
)

@Serializable
data class TokenResponseDto(
    val accessToken: String,
    val refreshToken: String,
) {
    override fun toString(): String =
        "TokenResponseDto(<redacted>)"
}

class NetworkAuthRepository(
    private val api: AuthApi,
    private val storage: SessionStorage,
) : AuthRepository {

    private val expired =
        MutableStateFlow(false)

    /*
     * Only one refresh request may run at a time.
     * Other simultaneous HTTP 401 requests wait here.
     */
    private val refreshMutex =
        Mutex()

    override val sessionStatus:
            Flow<SessionStatus> =
        combine(
            storage.session,
            expired,
        ) { tokens, isExpired ->

            when {
                isExpired ->
                    SessionStatus.EXPIRED

                tokens != null ->
                    SessionStatus.AUTHENTICATED

                else ->
                    SessionStatus.SIGNED_OUT
            }
        }

    override suspend fun login(
        email: String,
        password: String,
    ): AuthResult =
        authenticate {
            api.login(
                LoginRequestDto(
                    email = email,
                    password = password,
                ),
            )
        }

    override suspend fun register(
        fullName: String,
        email: String,
        password: String,
    ): AuthResult =
        authenticate {
            api.register(
                RegisterRequestDto(
                    fullName = fullName,
                    email = email,
                    password = password,
                ),
            )
        }

    override suspend fun logout() {
        val tokens =
            storage.session.first()

        if (tokens != null) {
            /*
             * Local secrets are always deleted.
             */
            apiCall {
                api.logout(
                    authorization =
                        "Bearer ${tokens.accessToken}",
                    request =
                        LogoutRequestDto(
                            refreshToken =
                                tokens.refreshToken,
                        ),
                )
            }
        }

        storage.clear()
        expired.value = false
    }

    override suspend fun refreshSession(
        rejectedAccessToken: String?,
    ): Boolean =
        refreshMutex.withLock {

            val current =
                storage.session.first()
                    ?: return@withLock false

            /*
             * Another request may already have refreshed while
             * this request was waiting for the Mutex.
             *
             * If the stored access token is different from the one
             * that received the 401, no second refresh is needed.
             */
            if (
                rejectedAccessToken != null &&
                current.accessToken !=
                rejectedAccessToken
            ) {
                return@withLock true
            }

            when (
                val result =
                    apiCall {
                        api.refresh(
                            RefreshRequestDto(
                                refreshToken =
                                    current.refreshToken,
                            ),
                        )
                    }
            ) {

                is ApiResult.Success -> {
                    val tokens =
                        result.data

                    if (
                        tokens.accessToken.isBlank() ||
                        tokens.refreshToken.isBlank()
                    ) {
                        invalidateSession()
                        false
                    } else {
                        storage.save(
                            SessionTokens(
                                accessToken =
                                    tokens.accessToken,
                                refreshToken =
                                    tokens.refreshToken,
                            ),
                        )

                        expired.value = false

                        true
                    }
                }

                else -> {
                    /*
                     * A failed refresh means the local session can
                     * no longer be trusted.
                     */
                    invalidateSession()
                    false
                }
            }
        }

    override suspend fun expireSession() {
        if (
            storage.session.first() ==
            null
        ) {
            return
        }

        invalidateSession()
    }

    override fun acknowledgeExpiredSession() {
        expired.value = false
    }

    private suspend fun invalidateSession() {
        storage.clear()
        expired.value = true
    }

    private suspend fun authenticate(
        call:
        suspend () -> TokenResponseDto,
    ): AuthResult =
        when (
            val response =
                apiCall(call)
        ) {

            is ApiResult.Success -> {
                val tokens =
                    response.data

                if (
                    tokens.accessToken.isBlank() ||
                    tokens.refreshToken.isBlank()
                ) {
                    AuthResult.Unavailable(
                        null,
                    )
                } else {
                    storage.save(
                        SessionTokens(
                            accessToken =
                                tokens.accessToken,
                            refreshToken =
                                tokens.refreshToken,
                        ),
                    )

                    expired.value = false

                    AuthResult.Success
                }
            }

            ApiResult.Unauthorized ->
                AuthResult.InvalidCredentials

            is ApiResult.HttpError ->
                when (
                    response.code
                ) {
                    409 ->
                        AuthResult
                            .EmailAlreadyRegistered

                    400,
                    422 ->
                        AuthResult
                            .RejectedInput

                    else ->
                        AuthResult.Unavailable(
                            response.code,
                        )
                }

            is ApiResult.NetworkUnavailable ->
                AuthResult.Unavailable(
                    null,
                )

            is ApiResult.InvalidResponse ->
                AuthResult.Unavailable(
                    null,
                )
        }
}