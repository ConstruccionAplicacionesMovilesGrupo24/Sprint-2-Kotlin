package com.campusmeal.android.core.network

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Outcome of a call to the CampusMeal API. Remote data sources return it so repositories and
 * ViewModels never handle Retrofit or OkHttp exceptions directly.
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>

    /** HTTP 401: the session is missing or expired. */
    data object Unauthorized : ApiResult<Nothing>

    /** Any other non-2xx status. The error body is dropped because it may echo sensitive input. */
    data class HttpError(val code: Int) : ApiResult<Nothing>

    /** Connectivity failure or timeout. Repositories may fall back to cached data. */
    data class NetworkUnavailable(val cause: IOException) : ApiResult<Nothing>

    /** The response body could not be decoded into the expected model. */
    data class InvalidResponse(val cause: SerializationException) : ApiResult<Nothing>
}

/**
 * Runs a Retrofit suspend call and maps its expected failures to [ApiResult].
 * Unexpected exceptions, including coroutine cancellation, still propagate.
 *
 * A temporary token-refresh failure arrives as `SessionRefreshUnavailableException`, an
 * [IOException], so it maps to [ApiResult.NetworkUnavailable] like any connectivity failure.
 */
suspend fun <T> apiCall(call: suspend () -> T): ApiResult<T> =
    try {
        ApiResult.Success(call())
    } catch (e: HttpException) {
        if (e.code() == 401) ApiResult.Unauthorized else ApiResult.HttpError(e.code())
    } catch (e: IOException) {
        // A call canceled with its coroutine can surface as IOException("Canceled"): keep it a cancellation.
        currentCoroutineContext().ensureActive()
        ApiResult.NetworkUnavailable(e)
    } catch (e: SerializationException) {
        ApiResult.InvalidResponse(e)
    }
