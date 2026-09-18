package com.campusmeal.android.feature.inventory.data.repository

import com.campusmeal.android.core.session.AuthorizationHeaderProvider
import com.campusmeal.android.feature.inventory.data.local.CachedExpiringInventory
import com.campusmeal.android.feature.inventory.data.local.ExpiringInventoryCache
import com.campusmeal.android.feature.inventory.data.remote.ExpiringInventoryResponseDto
import com.campusmeal.android.feature.inventory.data.remote.InventoryApi
import com.campusmeal.android.feature.inventory.domain.model.ExpiringInventoryResult
import com.campusmeal.android.feature.inventory.domain.model.InventoryDataSource
import com.campusmeal.android.feature.inventory.domain.model.InventoryError
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem
import com.campusmeal.android.feature.inventory.inventoryItem
import com.campusmeal.android.feature.inventory.inventoryItemDto
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class OfflineFirstInventoryRepositoryTest {

    private val api = FakeInventoryApi()
    private val cache = FakeExpiringInventoryCache()
    private var authorizationHeader: String? = TEST_AUTHORIZATION

    private val repository = OfflineFirstInventoryRepository(
        api = api,
        authorizationHeaderProvider = object : AuthorizationHeaderProvider {
            override suspend fun getAuthorizationHeader(): String? = authorizationHeader
        },
        cache = cache,
        currentTimeMillis = { NOW },
    )

    @Test
    fun successfulResponseIsCachedAndReturnedAsFresh() = runTest {
        api.respondWith(inventoryItemDto(id = "milk", remainingDays = 0), inventoryItemDto(id = "bread", remainingDays = 2))

        val result = repository.getExpiringInventory()

        val expectedItems = listOf(inventoryItem(id = "milk", remainingDays = 0), inventoryItem(id = "bread", remainingDays = 2))
        assertEquals(ExpiringInventoryResult.Success(expectedItems, InventoryDataSource.NETWORK, NOW), result)
        assertEquals(CachedExpiringInventory(expectedItems, NOW), cache.stored)
        assertEquals(3, api.lastWithinDays)
        assertTrue("Unexpected Authorization value", api.lastAuthorization == TEST_AUTHORIZATION)
    }

    @Test
    fun emptySuccessfulResponseReplacesPreviousCache() = runTest {
        cache.stored = CachedExpiringInventory(listOf(inventoryItem(id = "old")), PREVIOUS_SYNC)
        api.respondWith()

        val result = repository.getExpiringInventory()

        assertEquals(ExpiringInventoryResult.Success(emptyList(), InventoryDataSource.NETWORK, NOW), result)
        assertEquals(CachedExpiringInventory(emptyList(), NOW), cache.stored)
    }

    @Test
    fun networkErrorFallsBackToCachedResult() = runTest {
        val cachedItems = listOf(inventoryItem(id = "milk"))
        cache.stored = CachedExpiringInventory(cachedItems, PREVIOUS_SYNC)
        api.failWith(IOException("offline"))

        val result = repository.getExpiringInventory()

        assertEquals(ExpiringInventoryResult.Success(cachedItems, InventoryDataSource.CACHE, PREVIOUS_SYNC), result)
    }

    @Test
    fun networkErrorReturnsCachedEmptyResult() = runTest {
        cache.stored = CachedExpiringInventory(emptyList(), PREVIOUS_SYNC)
        api.failWith(IOException("offline"))

        assertEquals(
            ExpiringInventoryResult.Success(emptyList<InventoryItem>(), InventoryDataSource.CACHE, PREVIOUS_SYNC),
            repository.getExpiringInventory(),
        )
    }

    @Test
    fun serverErrorFallsBackToCachedResult() = runTest {
        val cachedItems = listOf(inventoryItem(id = "milk"))
        cache.stored = CachedExpiringInventory(cachedItems, PREVIOUS_SYNC)
        api.failWith(httpException(503))

        assertEquals(
            ExpiringInventoryResult.Success(cachedItems, InventoryDataSource.CACHE, PREVIOUS_SYNC),
            repository.getExpiringInventory(),
        )
    }

    @Test
    fun networkErrorWithoutCacheReturnsBackendUnavailable() = runTest {
        api.failWith(IOException("offline"))

        assertEquals(
            ExpiringInventoryResult.Failure(InventoryError.BackendUnavailable(httpCode = null)),
            repository.getExpiringInventory(),
        )
    }

    @Test
    fun missingSessionReturnsUnauthorizedWithoutCallingBackend() = runTest {
        authorizationHeader = null

        assertEquals(ExpiringInventoryResult.Unauthorized, repository.getExpiringInventory())
        assertEquals(0, api.calls)
    }

    @Test
    fun http401ReturnsUnauthorizedAndDropsCache() = runTest {
        cache.stored = CachedExpiringInventory(listOf(inventoryItem(id = "milk")), PREVIOUS_SYNC)
        api.failWith(httpException(401))

        assertEquals(ExpiringInventoryResult.Unauthorized, repository.getExpiringInventory())
        assertNull(cache.stored)
    }

    @Test
    fun otherHttpErrorReturnsTypedErrorWithoutUsingCache() = runTest {
        val cached = CachedExpiringInventory(listOf(inventoryItem(id = "milk")), PREVIOUS_SYNC)
        cache.stored = cached
        api.failWith(httpException(404))

        assertEquals(ExpiringInventoryResult.Failure(InventoryError.Http(404)), repository.getExpiringInventory())
        assertEquals(cached, cache.stored)
    }

    @Test
    fun invalidResponseKeepsPreviousCache() = runTest {
        val cached = CachedExpiringInventory(listOf(inventoryItem(id = "milk")), PREVIOUS_SYNC)
        cache.stored = cached
        api.respondWith(inventoryItemDto(name = ""))

        assertEquals(ExpiringInventoryResult.Failure(InventoryError.InvalidResponse), repository.getExpiringInventory())
        assertEquals(cached, cache.stored)
    }

    @Test
    fun clearCacheDeletesStoredResult() = runTest {
        cache.stored = CachedExpiringInventory(emptyList(), PREVIOUS_SYNC)

        repository.clearCache()

        assertNull(cache.stored)
    }

    private class FakeInventoryApi : InventoryApi {
        private var next: () -> ExpiringInventoryResponseDto = { ExpiringInventoryResponseDto(emptyList()) }
        var calls = 0
        var lastAuthorization: String? = null
        var lastWithinDays: Int? = null

        fun respondWith(vararg items: com.campusmeal.android.feature.inventory.data.remote.InventoryItemDto) {
            next = { ExpiringInventoryResponseDto(items.toList()) }
        }

        fun failWith(exception: Exception) {
            next = { throw exception }
        }

        override suspend fun getExpiringInventory(authorization: String, withinDays: Int): ExpiringInventoryResponseDto {
            calls++
            lastAuthorization = authorization
            lastWithinDays = withinDays
            return next()
        }
    }

    private class FakeExpiringInventoryCache : ExpiringInventoryCache {
        var stored: CachedExpiringInventory? = null

        override suspend fun read(): CachedExpiringInventory? = stored

        override suspend fun replace(items: List<InventoryItem>, syncedAtEpochMillis: Long) {
            stored = CachedExpiringInventory(items, syncedAtEpochMillis)
        }

        override suspend fun clear() {
            stored = null
        }
    }

    private companion object {
        const val NOW = 1_758_000_000_000L
        const val PREVIOUS_SYNC = 1_757_900_000_000L
        const val TEST_AUTHORIZATION = "Bearer test-access-token"

        fun httpException(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))
    }
}
