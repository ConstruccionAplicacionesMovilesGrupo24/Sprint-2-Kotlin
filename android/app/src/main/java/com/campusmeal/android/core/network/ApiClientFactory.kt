package com.campusmeal.android.core.network

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object ApiClientFactory {

    /** Header values that must never reach logs, even in debug builds. */
    val SENSITIVE_HEADERS: Set<String> = setOf(
        "Authorization",
        "Proxy-Authorization",
        "Cookie",
        "Set-Cookie",
        "X-Refresh-Token",
    )

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Builds the shared OkHttp client. Logging is only installed when [NetworkConfig.httpLoggingEnabled]
     * is true, which the app sets from BuildConfig.DEBUG, so release builds carry no logging interceptor.
     * [interceptors] run before logging, so what they change is what gets logged.
     */
    fun createOkHttpClient(
        config: NetworkConfig,
        interceptors: List<Interceptor> = emptyList(),
        // Last, so callers can pass it as a trailing lambda.
        logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.callTimeoutSeconds, TimeUnit.SECONDS)
            .apply { interceptors.forEach(::addInterceptor) }
            .apply { if (config.httpLoggingEnabled) addInterceptor(createLoggingInterceptor(logger)) }
            .build()

    /**
     * Client for non-idempotent writes (POST/PATCH/DELETE). It shares [base]'s connection pool,
     * timeouts and interceptors (session refresh and redacted logging), with two differences:
     * - OkHttp never retries a connection failure transparently, so a write that may have reached the
     *   server is not resent behind the app's back. The session interceptor's single retry after an
     *   actual 401 is kept: a 401 means the server did not apply the request.
     * - [TransmissionTrackingInterceptor] records whether the request reached a connection, so a
     *   failure can be classified as "not sent" or "outcome unknown".
     */
    fun createMutationOkHttpClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor(TransmissionTrackingInterceptor)
            .build()

    fun createRetrofit(
        config: NetworkConfig,
        client: OkHttpClient = createOkHttpClient(config),
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    private fun createLoggingInterceptor(logger: HttpLoggingInterceptor.Logger): HttpLoggingInterceptor =
        HttpLoggingInterceptor(logger).apply {
            // Never BODY: request and response bodies can contain credentials, JWTs and refresh tokens.
            level = HttpLoggingInterceptor.Level.HEADERS
            SENSITIVE_HEADERS.forEach(::redactHeader)
        }
}
