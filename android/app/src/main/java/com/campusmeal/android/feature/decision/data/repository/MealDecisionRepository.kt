package com.campusmeal.android.feature.decision.data.repository

import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.apiCall
import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.decision.data.mapper.toDomainOrNull
import com.campusmeal.android.feature.decision.data.mapper.toRequestDto
import com.campusmeal.android.feature.decision.data.remote.CompareMealOptionsResponseDto
import com.campusmeal.android.feature.decision.data.remote.MealDecisionApi
import com.campusmeal.android.feature.decision.domain.model.DecisionContext
import com.campusmeal.android.feature.decision.domain.model.MealDecisionError
import com.campusmeal.android.feature.decision.domain.model.MealDecisionResult

/** The BQ5 boundary the ViewModel depends on. */
interface MealDecisionRepository {
    suspend fun compare(context: DecisionContext, requestedAtEpochMillis: Long): MealDecisionResult
}

/**
 * Asks the backend to compare Cook, Walk and Order. There is no cache: a recommendation depends on
 * the time of day and on live restaurant data, so a stale one would be misleading. The backend owns
 * scoring and ranking; this class only validates the response and passes it through.
 */
class NetworkMealDecisionRepository(
    private val api: MealDecisionApi,
    private val authorizationHeaderProvider: AuthorizationHeaderProvider,
) : MealDecisionRepository {

    override suspend fun compare(context: DecisionContext, requestedAtEpochMillis: Long): MealDecisionResult {
        val authorization = authorizationHeaderProvider.getAuthorizationHeader()
            ?: return MealDecisionResult.Unauthorized

        val request = context.toRequestDto(requestedAtEpochMillis)
        return when (val response = apiCall { api.compare(authorization, request) }) {
            is ApiResult.Success -> response.data.toResult()
            ApiResult.Unauthorized -> MealDecisionResult.Unauthorized
            is ApiResult.NetworkUnavailable -> failure(MealDecisionError.BackendUnavailable(null))
            is ApiResult.HttpError ->
                if (response.code in 500..599) {
                    failure(MealDecisionError.BackendUnavailable(response.code))
                } else {
                    failure(MealDecisionError.Http(response.code))
                }
            is ApiResult.InvalidResponse -> failure(MealDecisionError.InvalidResponse)
        }
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
            else -> MealDecisionResult.NoAvailableAlternatives
        }
    }

    private fun failure(error: MealDecisionError) = MealDecisionResult.Failure(error)
}
