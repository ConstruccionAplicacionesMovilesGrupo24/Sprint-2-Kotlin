package com.campusmeal.android.core.network

import com.campusmeal.android.core.session.SessionRefreshUnavailableException
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response as OkHttpResponse
import retrofit2.Response

/**
 * Whether a write request reached a connection. Pass a new instance per attempt as a Retrofit `@Tag`;
 * [TransmissionTrackingInterceptor] sets [started] right before OkHttp writes the request.
 */
class TransmissionTracker {
    @Volatile
    var started: Boolean = false
        private set

    internal fun markStarted() {
        started = true
    }
}

/**
 * Network interceptor of the mutation client: it runs only after DNS, connect and TLS succeeded, just
 * before the request is written. A failure while [TransmissionTracker.started] is false is therefore
 * positive evidence that the server never received the request.
 */
object TransmissionTrackingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): OkHttpResponse {
        chain.request().tag(TransmissionTracker::class.java)?.markStarted()
        return chain.proceed(chain.request())
    }
}

/**
 * Outcome of a non-idempotent write. Unlike [ApiResult], it separates failures that prove the request
 * was not applied from failures after which the server may or may not have applied it.
 */
sealed interface MutationResponse<out T> {

    /** 2xx with a decodable body; [body] is null only for 204 No Content. */
    data class Applied<T>(val body: T?) : MutationResponse<T>

    /** 401 after the session interceptor's handling: the server did not apply the request. */
    data object Unauthorized : MutationResponse<Nothing>

    /**
     * A 4xx answer (except 401 and 408): the server refused the request. [errorCode] is the
     * CampusMeal `code` field when present; the rest of the error body is dropped.
     */
    data class Rejected(val httpCode: Int, val errorCode: String?) : MutationResponse<Nothing>

    /** The request never reached a connection, or the server answered 401 and refresh was unavailable. */
    data class NotSent(val cause: IOException) : MutationResponse<Nothing>

    /**
     * The server may have applied the request: a timeout or connection loss after sending, 408/5xx,
     * or a 2xx whose body could not be read. Never resend automatically.
     */
    data class OutcomeUnknown(val reason: String) : MutationResponse<Nothing>
}

/**
 * Runs one write attempt. [tracker] must be the instance passed to the call's `@Tag`. Cancellation
 * propagates: the caller decides from [TransmissionTracker.started] how to record an interrupted write.
 */
suspend fun <T> mutationCall(tracker: TransmissionTracker, call: suspend () -> Response<T>): MutationResponse<T> {
    val response = try {
        call()
    } catch (e: SessionRefreshUnavailableException) {
        // The server answered 401 (nothing applied) and the token refresh was temporarily unavailable.
        return MutationResponse.NotSent(e)
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        return if (tracker.started) MutationResponse.OutcomeUnknown(e.javaClass.simpleName) else MutationResponse.NotSent(e)
    } catch (_: SerializationException) {
        // Only 2xx bodies are decoded: the write was accepted but its answer is unreadable.
        return MutationResponse.OutcomeUnknown("UndecodableBody")
    }

    val code = response.code()
    return when {
        response.isSuccessful -> {
            val body = response.body()
            if (body == null && code != 204) MutationResponse.OutcomeUnknown("EmptyBody") else MutationResponse.Applied(body)
        }
        code == 401 -> MutationResponse.Unauthorized
        code == 408 || code >= 500 -> MutationResponse.OutcomeUnknown("HTTP $code")
        else -> MutationResponse.Rejected(code, response.errorCode())
    }
}

/** Reads only the `code` field of a CampusMeal error body; Retrofit has already buffered it. */
private fun Response<*>.errorCode(): String? =
    try {
        errorBody()?.string()
            ?.takeIf { it.length <= MAX_ERROR_BODY_CHARS }
            ?.let { ApiClientFactory.json.parseToJsonElement(it).jsonObject["code"]?.jsonPrimitive?.content }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        // Not JSON, or `code` is not a primitive (SerializationException is an IllegalArgumentException).
        null
    }

private const val MAX_ERROR_BODY_CHARS = 4_096
