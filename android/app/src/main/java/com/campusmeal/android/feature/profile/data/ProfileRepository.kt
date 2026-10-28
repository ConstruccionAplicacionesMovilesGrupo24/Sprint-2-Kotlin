package com.campusmeal.android.feature.profile.data

import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.auth.domain.AccountIdentifier
import com.campusmeal.android.feature.auth.domain.IdentificationResult
import com.campusmeal.android.feature.profile.data.local.AccountProfileDao
import com.campusmeal.android.feature.profile.data.local.AccountProfileEntity
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
    /** Stable user id: the account identifier for everything stored on the device. */
    val id: String,
    val name: String,
    val email: String,
    /** `USER` or `ANALYST`; unknown values are shown as received. */
    val role: String,
)

data class CurrentUser(val accountId: String, val name: String, val email: String, val role: String)

enum class ProfileSource { NETWORK, CACHE }

sealed interface CurrentUserResult {

    /** [source] CACHE means `auth/me` was unreachable and this is the stored copy from [fetchedAtEpochMillis]. */
    data class Success(
        val user: CurrentUser,
        val source: ProfileSource,
        val fetchedAtEpochMillis: Long,
    ) : CurrentUserResult

    data object Unauthorized : CurrentUserResult

    /** `auth/me` was unreachable and no profile of this account is stored. */
    data object Unavailable : CurrentUserResult
}

/**
 * Owns `GET auth/me` and the `account_profile` table: it identifies new sessions and keeps the
 * signed-in account's profile for offline Home and Profile.
 */
class ProfileRepository(
    private val api: CurrentUserApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val coordinator: AccountSessionCoordinator,
    private val dao: AccountProfileDao,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : AccountIdentifier, AccountScopedStore {

    /** Identification: publishes the account returned by `auth/me` for the session of [generation]. */
    override suspend fun identify(generation: Long): IdentificationResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(generation)
            ?: return IdentificationResult.Superseded
        return when (val result = apiCall { api.me(authorization) }) {
            is ApiResult.Success -> {
                // A blank id would not identify anyone: never publish it as an account.
                val entity = result.data.toEntityOrNull() ?: return IdentificationResult.Unavailable
                coordinator.publishIdentity(generation, entity.accountId) { dao.upsert(entity) }
                    ?.let(IdentificationResult::Identified)
                    ?: IdentificationResult.Superseded
            }
            ApiResult.Unauthorized -> IdentificationResult.NotAuthenticated
            else -> IdentificationResult.Unavailable
        }
    }

    /**
     * Reads the account of [scope] for Home and Profile. Online it refreshes the stored profile; when
     * `auth/me` is unreachable it returns that account's stored copy, labeled with its fetch time.
     */
    suspend fun currentUser(scope: AccountSessionScope): CurrentUserResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return CurrentUserResult.Unauthorized
        return when (val result = apiCall { api.me(authorization) }) {
            is ApiResult.Success -> {
                val entity = result.data.toEntityOrNull()
                    ?: return dao.get(scope.accountId)?.toResult(ProfileSource.CACHE) ?: CurrentUserResult.Unavailable
                if (entity.accountId != scope.accountId) {
                    // The tokens now belong to another account: switch scopes; this result is stale.
                    coordinator.publishIdentity(scope.generation, entity.accountId) { dao.upsert(entity) }
                    CurrentUserResult.Unauthorized
                } else {
                    coordinator.runIfCurrent(scope) { dao.upsert(entity) }
                    entity.toResult(ProfileSource.NETWORK)
                }
            }
            ApiResult.Unauthorized -> CurrentUserResult.Unauthorized
            else -> dao.get(scope.accountId)?.toResult(ProfileSource.CACHE) ?: CurrentUserResult.Unavailable
        }
    }

    override suspend fun deleteAccount(accountId: String) {
        dao.delete(accountId)
    }

    override suspend fun deleteAllExcept(accountId: String) {
        dao.deleteAllExcept(accountId)
    }

    private fun CurrentUserDto.toEntityOrNull(): AccountProfileEntity? =
        if (id.isBlank()) null
        else AccountProfileEntity(accountId = id, name = name, email = email, role = role, fetchedAtEpochMillis = currentTimeMillis())

    private fun AccountProfileEntity.toResult(source: ProfileSource) =
        CurrentUserResult.Success(CurrentUser(accountId, name, email, role), source, fetchedAtEpochMillis)
}
