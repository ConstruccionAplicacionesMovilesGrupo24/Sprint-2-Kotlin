package com.campusmeal.android.feature.auth.domain

import com.campusmeal.android.core.session.AccountSessionScope
import kotlinx.coroutines.flow.Flow

/**
 * Contract between the authentication experience (screens, `AuthViewModel`, protected navigation)
 * and the authentication data layer. The UI depends only on this interface.
 *
 * Every session transition runs through the shared `AccountSessionCoordinator`.
 */
interface AuthRepository {

    /** Drives protected navigation: only [SessionStatus.Authenticated] opens the main graph. */
    val sessionStatus: Flow<SessionStatus>

    suspend fun login(email: String, password: String): AuthResult

    suspend fun register(fullName: String, email: String, password: String): AuthResult

    /**
     * Calls `GET auth/me` for the current, not yet identified session. Used for the explicit retry
     * on the "account not identified" screen and when the app returns to the foreground or online.
     */
    suspend fun identifyAccount(): IdentificationResult

    /**
     * Ends the session locally right away, then asks the backend to revoke it (best effort, never
     * awaited). Local logout never depends on the network.
     */
    suspend fun logout()

    /**
     * Single-flight refresh after a protected request got HTTP 401 with [rejectedAccessToken].
     * Only the session that issued that token can be refreshed or invalidated.
     */
    suspend fun refreshSession(rejectedAccessToken: String): RefreshOutcome

    /**
     * Ends the session of [generation] after the backend rejected a protected request even with
     * freshly refreshed tokens. A newer session is never affected. Moves to [SessionStatus.Expired].
     */
    suspend fun expireSession(generation: Long)

    /** The user saw the Session Expired screen and chose to log in again. */
    fun acknowledgeExpiredSession()
}

sealed interface SessionStatus {

    data object SignedOut : SessionStatus

    /**
     * Credentials exist but `auth/me` has not identified the account yet (for example, it was
     * offline right after login). No account-owned data may be read in this state.
     */
    data object AccountNotIdentified : SessionStatus

    data class Authenticated(val scope: AccountSessionScope) : SessionStatus

    /** The session was ended by the backend, not by the user. Shows the Session Expired screen. */
    data object Expired : SessionStatus
}

/** Result of a token refresh, as seen by `SessionRefreshInterceptor`. */
sealed interface RefreshOutcome {

    /** New tokens are stored (or another request had already stored them); retry once with [authorization]. */
    data class Refreshed(val authorization: String, val generation: Long) : RefreshOutcome

    /** No connection, timeout, 408, 429, 5xx, an unreadable answer or an unexpected status: keep everything. */
    data object TemporarilyUnavailable : RefreshOutcome

    /** The backend definitively rejected the refresh token (401, or 400 for an unusable token). The session is ended. */
    data object Rejected : RefreshOutcome

    /** The rejected token belongs to a session that is no longer active: neither refresh nor retry. */
    data object NotCurrentSession : RefreshOutcome
}

sealed interface IdentificationResult {
    data class Identified(val scope: AccountSessionScope) : IdentificationResult

    /** No connection or the service is unavailable; the session stays unidentified and can be retried. */
    data object Unavailable : IdentificationResult

    /** The credentials were rejected, or there is no session to identify. */
    data object NotAuthenticated : IdentificationResult

    /** Another identification is running, or the session changed while waiting. */
    data object Superseded : IdentificationResult
}

/** Outcomes the UI must tell apart: credential problems are never reported as connectivity ones. */
sealed interface AuthResult {

    /** Tokens are stored. The account may still be unidentified if `auth/me` could not be reached. */
    data object Success : AuthResult

    /** Login: wrong email or password (HTTP 401). */
    data object InvalidCredentials : AuthResult

    /** Registration: the email already has an account (HTTP 409). */
    data object EmailAlreadyRegistered : AuthResult

    /** The backend rejected the submitted fields (HTTP 400 or 422). */
    data object RejectedInput : AuthResult

    /** No connection, a timeout or HTTP 5xx. Null [httpCode] means no HTTP response. */
    data class Unavailable(val httpCode: Int?) : AuthResult
}
