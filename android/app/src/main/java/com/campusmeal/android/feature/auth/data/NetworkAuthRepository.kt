package com.campusmeal.android.feature.auth.data

import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.DeactivationReason
import com.campusmeal.android.core.session.SessionStorage
import com.campusmeal.android.core.session.SessionTokens
import com.campusmeal.android.feature.auth.domain.AccountIdentifier
import com.campusmeal.android.feature.auth.domain.AuthRepository
import com.campusmeal.android.feature.auth.domain.AuthResult
import com.campusmeal.android.feature.auth.domain.IdentificationResult
import com.campusmeal.android.feature.auth.domain.RefreshOutcome
import com.campusmeal.android.feature.auth.domain.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

/**
 * Login, registration, identification, refresh and logout. Every transition goes through the shared
 * [AccountSessionCoordinator]; no network call runs while its lock is held.
 */
class NetworkAuthRepository(
    private val api: AuthApi,
    private val storage: SessionStorage,
    private val coordinator: AccountSessionCoordinator,
    private val accountIdentifier: AccountIdentifier,
    /** Application scope for the best-effort remote logout, which local logout never waits for. */
    private val backgroundScope: CoroutineScope,
) : AuthRepository {

    private val expired =
        MutableStateFlow(false)

    /*
     * Only one refresh request may run at a time.
     * Other simultaneous HTTP 401 requests wait here.
     */
    private val refreshMutex =
        Mutex()

    /** One `auth/me` identification at a time; each trigger makes at most one request. */
    private val identifyMutex =
        Mutex()

    /**
     * Access tokens issued to the session of [generation], newest last. They let a 401 be traced to
     * the session that sent it, so a request from an ended session is never refreshed or retried with
     * the new session's token. Memory only; never logged.
     */
    private class TokenLineage(val generation: Long, val accessTokens: List<String>)

    @Volatile
    private var lineage: TokenLineage? = null

    override val sessionStatus:
            Flow<SessionStatus> =
        combine(
            storage.session,
            coordinator.sessionState,
            expired,
        ) { tokens, state, isExpired ->

            when {
                isExpired ->
                    SessionStatus.Expired

                tokens == null ->
                    SessionStatus.SignedOut

                else ->
                    state.scope
                        ?.let(SessionStatus::Authenticated)
                        ?: SessionStatus.AccountNotIdentified
            }
        }.distinctUntilChanged()

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

    override suspend fun identifyAccount(): IdentificationResult {
        val state = coordinator.currentState()
        if (storage.session.first() == null) return IdentificationResult.NotAuthenticated
        state.scope?.let { return IdentificationResult.Identified(it) }
        return identify(state.generation)
    }

    /**
     * Local logout completes inside one coordinator transition: the departing tokens are captured
     * and cleared, the generation advances and the account's read data is deleted. The backend is
     * then asked to revoke the captured session in the background; its outcome changes nothing here.
     */
    override suspend fun logout() {
        val departing =
            coordinator.endSession(
                expectedGeneration = null,
                reason = DeactivationReason.LOGOUT,
            ) {
                val tokens = storage.session.first()
                storage.clear()
                expired.value = false
                tokens
            }

        lineage = null
        departing?.let(::revokeInBackground)
    }

    override suspend fun refreshSession(
        rejectedAccessToken: String,
    ): RefreshOutcome =
        refreshMutex.withLock {

            val generation =
                generationOf(rejectedAccessToken)
                    ?: return@withLock RefreshOutcome.NotCurrentSession

            val current =
                storage.session.first()
                    ?: return@withLock RefreshOutcome.NotCurrentSession

            /*
             * Another request of the same session may already have refreshed while this one was
             * waiting for the Mutex. Its new token is reused only if that session is still active.
             */
            if (current.accessToken != rejectedAccessToken) {
                return@withLock if (coordinator.currentState().generation == generation) {
                    RefreshOutcome.Refreshed(bearer(current.accessToken), generation)
                } else {
                    RefreshOutcome.NotCurrentSession
                }
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
                        // An unusable answer says nothing about the stored credentials.
                        RefreshOutcome.TemporarilyUnavailable
                    } else {
                        val saved =
                            coordinator.runIfGeneration(generation) {
                                storage.save(
                                    SessionTokens(
                                        accessToken =
                                            tokens.accessToken,
                                        refreshToken =
                                            tokens.refreshToken,
                                    ),
                                )
                                remember(generation, tokens.accessToken)
                            }

                        // The session ended while refreshing: its new tokens are dropped, never stored.
                        if (saved == null) {
                            RefreshOutcome.NotCurrentSession
                        } else {
                            RefreshOutcome.Refreshed(bearer(tokens.accessToken), generation)
                        }
                    }
                }

                /*
                 * The backend rejected the refresh token itself (401), or answered 400 because the
                 * stored token is unusable: the session can no longer be trusted.
                 */
                ApiResult.Unauthorized -> {
                    invalidate(generation)
                    RefreshOutcome.Rejected
                }

                is ApiResult.HttpError ->
                    if (result.code == 400) {
                        invalidate(generation)
                        RefreshOutcome.Rejected
                    } else {
                        /*
                         * 408, 429 and 5xx are temporary. Any other status is unexpected for this
                         * endpoint and is treated conservatively as a protocol problem, not as a
                         * credential rejection, so the user is not signed out by a server bug.
                         */
                        RefreshOutcome.TemporarilyUnavailable
                    }

                /*
                 * No connection or an unreadable answer says nothing about the session: keep it, so
                 * the user is not signed out offline.
                 */
                is ApiResult.NetworkUnavailable,
                is ApiResult.InvalidResponse ->
                    RefreshOutcome.TemporarilyUnavailable
            }
        }

    override suspend fun expireSession(generation: Long) {
        invalidate(generation)
    }

    override fun acknowledgeExpiredSession() {
        expired.value = false
    }

    /** Ends only the session of [generation]; local data stays for the same account's reauthentication. */
    private suspend fun invalidate(generation: Long) {
        val ended =
            coordinator.endSession(
                expectedGeneration = generation,
                reason = DeactivationReason.EXPIRED,
            ) {
                expired.value = true
                storage.clear()
            }

        if (ended != null) lineage = null
    }

    private suspend fun authenticate(
        call:
        suspend () -> TokenResponseDto,
    ): AuthResult {
        // A late answer of an attempt superseded by a newer transition must not install its tokens.
        val attempt =
            coordinator.currentState().generation

        return when (
            val response =
                apiCall(call)
        ) {

            is ApiResult.Success -> {
                val dto =
                    response.data

                if (
                    dto.accessToken.isBlank() ||
                    dto.refreshToken.isBlank()
                ) {
                    return AuthResult.Unavailable(
                        null,
                    )
                }

                val tokens =
                    SessionTokens(
                        accessToken =
                            dto.accessToken,
                        refreshToken =
                            dto.refreshToken,
                    )

                val generation =
                    coordinator.replaceCredentials(attempt) {
                        expired.value = false
                        storage.save(tokens)
                    }

                if (generation == null) {
                    revokeInBackground(tokens)
                    return AuthResult.Unavailable(
                        null,
                    )
                }

                lineage = TokenLineage(generation, listOf(tokens.accessToken))

                // If this fails the session stays AccountNotIdentified and is retried from that screen.
                identify(generation)

                AuthResult.Success
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

    private suspend fun identify(generation: Long): IdentificationResult =
        identifyMutex.withLock {
            val state = coordinator.currentState()
            when {
                state.generation != generation -> IdentificationResult.Superseded
                state.scope != null -> IdentificationResult.Identified(checkNotNull(state.scope))
                else -> accountIdentifier.identify(generation)
            }
        }

    /** The generation of the session that sent [accessToken], or null when that session is no longer active. */
    private suspend fun generationOf(accessToken: String): Long? {
        val state = coordinator.currentState()
        val known = lineage
        if (known != null && known.generation == state.generation && accessToken in known.accessTokens) {
            return state.generation
        }
        // After a restart, or an account change that kept the same tokens, only the stored token is known.
        return state.generation.takeIf { storage.session.first()?.accessToken == accessToken }
    }

    private fun remember(generation: Long, accessToken: String) {
        val known = lineage?.takeIf { it.generation == generation }?.accessTokens.orEmpty()
        lineage = TokenLineage(generation, (known + accessToken).takeLast(MAX_REMEMBERED_TOKENS))
    }

    /** Best-effort revocation with the departing session's own credentials, never another account's. */
    private fun revokeInBackground(tokens: SessionTokens) {
        backgroundScope.launch {
            // auth/logout is excluded from refresh, so the interceptor never swaps in a newer token.
            apiCall {
                api.logout(
                    authorization = bearer(tokens.accessToken),
                    request = LogoutRequestDto(refreshToken = tokens.refreshToken),
                )
            }
        }
    }

    private fun bearer(accessToken: String) = "Bearer $accessToken"

    private companion object {
        const val MAX_REMEMBERED_TOKENS = 5
    }
}
