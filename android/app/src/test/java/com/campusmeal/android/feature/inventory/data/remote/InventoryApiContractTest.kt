package com.campusmeal.android.feature.inventory.data.remote

import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.network.ApiResult
import com.campusmeal.android.core.network.NetworkConfig
import com.campusmeal.android.core.network.apiCall
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InventoryApiContractTest {

    private val server = MockWebServer()
    private lateinit var api: InventoryApi

    @Before
    fun setUp() {
        server.start()
        api = ApiClientFactory
            .createRetrofit(NetworkConfig(baseUrl = server.url("/api/v1/").toString(), httpLoggingEnabled = false))
            .create(InventoryApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun requestsExpiringInventoryWithBearerTokenAndDecodesResponse() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "items": [
                        {"id":"item-001","name":"Milk","quantity":1.0,"unit":"L","expirationDate":"2026-09-16",
                         "remainingDays":1,"active":true,"category":"dairy"}
                      ],
                      "generatedAt": "2026-09-15T10:00:00Z"
                    }
                    """.trimIndent(),
                ),
        )

        val response = api.getExpiringInventory(authorization = TEST_AUTHORIZATION)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/inventory/expiring", request.requestUrl?.encodedPath)
        assertEquals("3", request.requestUrl?.queryParameter("withinDays"))
        // Compared without assertEquals so a failure never prints the header value.
        assertTrue("Authorization header mismatch", request.getHeader("Authorization") == TEST_AUTHORIZATION)
        assertEquals(
            ExpiringInventoryResponseDto(
                listOf(
                    InventoryItemDto(
                        id = "item-001",
                        name = "Milk",
                        quantity = 1.0,
                        unit = "L",
                        expirationDate = "2026-09-16",
                        remainingDays = 1,
                        active = true,
                    ),
                ),
            ),
            response,
        )
    }

    @Test
    fun missingRequiredFieldIsAnInvalidResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[{"id":"item-001","name":"Milk","quantity":1.0,"unit":"L","expirationDate":"2026-09-16","active":true}]}""",
            ),
        )

        val result = apiCall { api.getExpiringInventory(authorization = TEST_AUTHORIZATION) }

        assertTrue(result is ApiResult.InvalidResponse)
    }

    private companion object {
        const val TEST_AUTHORIZATION = "Bearer test-access-token"
    }
}
