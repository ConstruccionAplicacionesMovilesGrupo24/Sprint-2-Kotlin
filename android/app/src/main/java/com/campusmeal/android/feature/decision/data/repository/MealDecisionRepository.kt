package com.campusmeal.android.feature.decision.data.repository

import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.decision.data.local.ComparisonHistoryEntity
import com.campusmeal.android.feature.decision.data.local.ComparisonHistoryStore
import com.campusmeal.android.feature.decision.data.mapper.toDomainOrNull
import com.campusmeal.android.feature.decision.data.mapper.toRequestDto
import com.campusmeal.android.feature.decision.data.remote.CompareMealOptionsResponseDto
import com.campusmeal.android.feature.decision.data.remote.MealDecisionApi
import com.campusmeal.android.feature.decision.domain.model.DecisionContext
import com.campusmeal.android.feature.decision.domain.model.MealDecisionError
import com.campusmeal.android.feature.decision.domain.model.MealDecisionResult

/** The BQ5 boundary the ViewModel depends on. */
interface MealDecisionRepository {
    /** Sent with the credentials of [scope] only; null credentials (scope ended) answer Unauthorized. */
    suspend fun compare(scope: AccountSessionScope, context: DecisionContext, requestedAtEpochMillis: Long): MealDecisionResult

    /**
     * The stored comparison [recommendationId] as a current result, or null. Only for a
     * recommendation the caller knows is still recent; it never calls the backend.
     */
    suspend fun restore(scope: AccountSessionScope, recommendationId: String): MealDecisionResult?
}

/**
 * Asks the backend to compare Cook, Walk and Order. The backend owns scoring and ranking; this class
 * only validates the response and passes it through.
 *
 * S3-11: every successful comparison is kept in [history] exactly as received. A recommendation
 * depends on the time of day and live restaurant data, so the stored one is never presented as
 * current: when the backend cannot be reached it comes back as [MealDecisionResult.Previous], dated
 * and not selectable. A new comparison always needs the backend; it is never queued and replayed,
 * because each POST creates a new recommendation on the server.
 */
class NetworkMealDecisionRepository(
    private val api: MealDecisionApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
    private val history: ComparisonHistoryStore,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : MealDecisionRepository {

    override suspend fun compare(scope: AccountSessionScope, context: DecisionContext, requestedAtEpochMillis: Long): MealDecisionResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader(scope)
            ?: return MealDecisionResult.Unauthorized

        val request = context.toRequestDto(requestedAtEpochMillis)
        return when (val response = apiCall { api.compare(authorization, request) }) {
            is ApiResult.Success -> response.data.toResult().also { result ->
                if (result is MealDecisionResult.Content || result is MealDecisionResult.PartialContent) {
                    history.save(
                        scope,
                        ComparisonHistoryEntity(
                            recommendationId = response.data.recommendationId,
                            accountId = scope.accountId,
                            comparedAtEpochMillis = currentTimeMillis(),
                            campusId = request.campusId,
                            availableMinutes = request.availableMinutes,
                            maximumBudget = request.maximumBudget,
                            responseJson = ApiClientFactory.json.encodeToString(CompareMealOptionsResponseDto.serializer(), response.data),
                        ),
                    )
                }
            }
            ApiResult.Unauthorized -> MealDecisionResult.Unauthorized
            is ApiResult.NetworkUnavailable -> previousOr(scope, MealDecisionError.BackendUnavailable(null))
            is ApiResult.HttpError ->
                if (response.code in 500..599) {
                    previousOr(scope, MealDecisionError.BackendUnavailable(response.code))
                } else {
                    failure(MealDecisionError.Http(response.code))
                }
            is ApiResult.InvalidResponse -> failure(MealDecisionError.InvalidResponse)
        }
    }

    override suspend fun restore(scope: AccountSessionScope, recommendationId: String): MealDecisionResult? {
        val entry = history.get(scope, recommendationId) ?: return null
        val response = runCatching {
            ApiClientFactory.json.decodeFromString(CompareMealOptionsResponseDto.serializer(), entry.responseJson)
        }.getOrNull() ?: return null
        return response.toResult().takeIf { it is MealDecisionResult.Content || it is MealDecisionResult.PartialContent }
    }

    private fun CompareMealOptionsResponseDto.toResult(): MealDecisionResult {
        val mapped = toDomainOrNull() ?: return failure(MealDecisionError.InvalidResponse)
        return when {
            mapped.decision.alternatives.isNotEmpty() && mapped.skippedAlternatives == 0 ->
                MealDecisionResult.Content(mapped.decision)
            mapped.decision.alternatives.isNotEmpty() ->
                MealDecisionResult.PartialContent(mapped.decision, mapped.skippedAlternatives)
            // The backend offered alternatives but none could be shown: that is a broken contract,
            // not an honest "nothing is available".
            mapped.skippedAlternatives > 0 -> failure(MealDecisionError.InvalidResponse)
            else -> MealDecisionResult.NoAvailableAlternatives(recommendationId, mainExplanation)
        }
    }

    /** The latest stored comparison of the account, labeled as previous; otherwise the failure. */
    private suspend fun previousOr(scope: AccountSessionScope, error: MealDecisionError): MealDecisionResult {
        val entry = history.latest(scope) ?: return failure(error)
        val mapped = runCatching {
            ApiClientFactory.json.decodeFromString(CompareMealOptionsResponseDto.serializer(), entry.responseJson).toDomainOrNull()
        }.getOrNull() ?: return failure(error)
        return MealDecisionResult.Previous(
            decision = mapped.decision,
            skippedAlternatives = mapped.skippedAlternatives,
            comparedAtEpochMillis = entry.comparedAtEpochMillis,
            availableMinutes = entry.availableMinutes,
            maximumBudget = entry.maximumBudget,
            cause = error,
        )
    }

    private fun failure(error: MealDecisionError) = MealDecisionResult.Failure(error)
}
