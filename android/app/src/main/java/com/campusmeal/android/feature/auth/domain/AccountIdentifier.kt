package com.campusmeal.android.feature.auth.domain

/**
 * Identifies the account of the session of [generation] with `GET auth/me`, stores its profile and
 * publishes it through the session coordinator. Implemented by the profile data layer.
 */
fun interface AccountIdentifier {
    suspend fun identify(generation: Long): IdentificationResult
}
