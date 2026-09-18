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
            .apply { interceptors.forEach(::addInterceptor) }
            .apply { if (config.httpLoggingEnabled) addInterceptor(createLoggingInterceptor(logger)) }
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
