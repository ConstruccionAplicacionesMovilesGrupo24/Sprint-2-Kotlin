package com.campusmeal.android.feature.profile.data

import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header

interface CurrentUserApi {

    /** `GET auth/me`: the backend never returns hashes or session data here. */
    @GET("auth/me")
    suspend fun me(@Header("Authorization") authorization: String): CurrentUserDto
}

@Serializable
data class CurrentUserDto(
    val id: String,
    val name: String,
    val email: String,
    /** `USER` or `ANALYST`; unknown values are shown as received. */
    val role: String,
)

data class CurrentUser(val name: String, val email: String, val role: String)

sealed interface CurrentUserResult {
    data class Success(val user: CurrentUser) : CurrentUserResult
    data object Unauthorized : CurrentUserResult
    data object Unavailable : CurrentUserResult
}

/** Reads the signed-in user for Home and Profile. Nothing is cached on disk. */
class ProfileRepository(
    private val api: CurrentUserApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
) {
    suspend fun currentUser(): CurrentUserResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader()
            ?: return CurrentUserResult.Unauthorized
        return when (val result = apiCall { api.me(authorization) }) {
            is ApiResult.Success -> CurrentUserResult.Success(CurrentUser(result.data.name, result.data.email, result.data.role))
            ApiResult.Unauthorized -> CurrentUserResult.Unauthorized
            else -> CurrentUserResult.Unavailable
        }
    }
}
