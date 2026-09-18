package com.campusmeal.android.core.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiClientFactoryTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun releaseConfigurationInstallsNoLoggingInterceptor() {
        val client = ApiClientFactory.createOkHttpClient(config(httpLoggingEnabled = false))

        assertFalse(client.interceptors.any { it is HttpLoggingInterceptor })
    }

    @Test
    fun debugLoggingRedactsCredentialsAndOmitsBodies() {
        val logLines = mutableListOf<String>()
        val client = ApiClientFactory.createOkHttpClient(config(httpLoggingEnabled = true)) { logLines += it }
        server.enqueue(
            MockResponse()
                .setHeader("Set-Cookie", "session=cookie-secret")
                .setBody("""{"refreshToken":"response-secret"}"""),
        )

        client.execute(
            Request.Builder()
                .url(server.url("/api/v1/auth/refresh"))
                .header("Authorization", "Bearer jwt-secret")
                .post("""{"refreshToken":"request-secret"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        )

        val log = logLines.joinToString("\n")
        assertTrue(log.contains("Authorization"))
        assertFalse(log.contains("secret"))
    }

    @Test
    fun retrofitUsesConfiguredBaseUrl() {
        val baseUrl = server.url("/api/v1/").toString()

        val retrofit = ApiClientFactory.createRetrofit(NetworkConfig(baseUrl, httpLoggingEnabled = false))

        assertEquals(baseUrl, retrofit.baseUrl().toString())
    }

    private fun config(httpLoggingEnabled: Boolean) =
        NetworkConfig(baseUrl = server.url("/api/v1/").toString(), httpLoggingEnabled = httpLoggingEnabled)

    private fun OkHttpClient.execute(request: Request) {
        newCall(request).execute().use { it.body?.string() }
    }
}
