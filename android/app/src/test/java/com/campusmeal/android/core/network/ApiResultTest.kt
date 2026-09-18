package com.campusmeal.android.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.http.GET

class ApiResultTest {

    @Serializable
    internal data class ProbeResponse(val status: String)

    internal interface ProbeApi {
        @GET("probe")
        suspend fun probe(): ProbeResponse
    }

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
    fun successfulResponseIsDecodedIgnoringUnknownFields() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok","extra":1}"""))

        assertEquals(ApiResult.Success(ProbeResponse("ok")), apiCall { api(server.url("/api/v1/").toString()).probe() })
    }

    @Test
    fun unauthorizedStatusMapsToUnauthorized() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(ApiResult.Unauthorized, apiCall { api(server.url("/api/v1/").toString()).probe() })
    }

    @Test
    fun otherErrorStatusKeepsOnlyTheCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"echoed input"}"""))

        assertEquals(ApiResult.HttpError(503), apiCall { api(server.url("/api/v1/").toString()).probe() })
    }

    @Test
    fun connectionFailureMapsToNetworkUnavailable() = runTest {
        val unreachableUrl = MockWebServer().run {
            start()
            val url = url("/api/v1/").toString()
            shutdown()
            url
        }

        assertTrue(apiCall { api(unreachableUrl).probe() } is ApiResult.NetworkUnavailable)
    }

    @Test
    fun undecodableBodyMapsToInvalidResponse() = runTest {
        server.enqueue(MockResponse().setBody("""{"unexpected":true}"""))

        assertTrue(apiCall { api(server.url("/api/v1/").toString()).probe() } is ApiResult.InvalidResponse)
    }

    private fun api(baseUrl: String): ProbeApi =
        ApiClientFactory.createRetrofit(NetworkConfig(baseUrl, httpLoggingEnabled = false))
            .create(ProbeApi::class.java)
}
