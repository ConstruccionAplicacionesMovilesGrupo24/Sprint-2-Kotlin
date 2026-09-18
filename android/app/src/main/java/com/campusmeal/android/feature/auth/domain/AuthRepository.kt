package com.campusmeal.android.feature.auth.domain

import kotlinx.coroutines.flow.Flow

/**
 * Contract between the authentication experience (screens, `AuthViewModel`, protected navigation)
 * and the authentication data layer. The UI depends only on this interface, so the interim
 * `NetworkAuthRepository` can be replaced by the full implementation — token refresh, Keystore
 * storage, session restoration — without touching the screens.
 */
interface AuthRepository {

    /** Drives protected navigation: only [SessionStatus.AUTHENTICATED] opens the main graph. */
    val sessionStatus: Flow<SessionStatus>

    suspend fun login(email: String, password: String): AuthResult

    suspend fun register(fullName: String, email: String, password: String): AuthResult

    /** Invalidates the session on the backend when possible and always removes it locally. */
    suspend fun logout()

    /**
     * Ends a session the backend rejected and could not be recovered — after the single refresh
     * attempt fails. Moves [sessionStatus] to [SessionStatus.EXPIRED].
     */
    suspend fun expireSession()

    /** The user saw the Session Expired screen and chose to log in again. */
    fun acknowledgeExpiredSession()
}

enum class SessionStatus {
    SIGNED_OUT,
    AUTHENTICATED,

    /** The session was ended by the backend, not by the user. Shows the Session Expired screen. */
    EXPIRED,
}

/** Outcomes the UI must tell apart: credential problems are never reported as connectivity ones. */
sealed interface AuthResult {

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
