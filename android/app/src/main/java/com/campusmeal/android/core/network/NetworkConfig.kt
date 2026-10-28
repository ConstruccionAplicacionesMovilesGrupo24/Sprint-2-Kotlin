package com.campusmeal.android.core.network

/**
 * Network settings supplied by the build (see android/README.md). Business logic must not
 * hardcode backend URLs. The app only talks to the CampusMeal API; external providers such as
 * route services are reached exclusively through that API.
 *
 * Timeouts apply to every client built by [ApiClientFactory] (auth, protected and mutation calls):
 * - [readTimeoutSeconds] allows for a Render Free instance waking up, which Render documents as
 *   taking about a minute. It is an allowance, not a promise that the service is available.
 * - [callTimeoutSeconds] bounds the whole call (connect, refresh retry, write, read), so a single
 *   request can never hang indefinitely.
 * - [writeTimeoutSeconds] is OkHttp's default, stated explicitly; request bodies are small.
 */
data class NetworkConfig(
    val baseUrl: String,
    val httpLoggingEnabled: Boolean,
    val connectTimeoutSeconds: Long = 15,
    val readTimeoutSeconds: Long = 90,
    val writeTimeoutSeconds: Long = 10,
    val callTimeoutSeconds: Long = 120,
) {
    init {
        require(baseUrl.isNotBlank()) { "CampusMeal API base URL is not configured. See android/README.md." }
        require(baseUrl.endsWith("/")) { "CampusMeal API base URL must end with '/'." }
        require(callTimeoutSeconds >= readTimeoutSeconds) { "The call timeout must cover the read timeout." }
    }
}
