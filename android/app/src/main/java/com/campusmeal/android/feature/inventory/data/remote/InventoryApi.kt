package com.campusmeal.android.feature.inventory.data.remote

import com.campusmeal.android.core.network.TransmissionTracker
import com.campusmeal.android.feature.inventory.domain.model.EXPIRING_WITHIN_DAYS
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * Inventory reads. [authorization] is the full `Bearer` header value. Paths are relative because the
 * base URL already ends in `/api/v1/`.
 */
interface InventoryApi {

    /** Every active item of the current user, expired items included, in the backend's order. */
    @GET("inventory")
    suspend fun getInventory(
        @Header("Authorization") authorization: String,
    ): InventoryItemsResponseDto

    /**
     * BQ2: the current user's items expiring within [withinDays] days, already filtered and ordered by
     * priority by the backend.
     */
    @GET("inventory/expiring")
    suspend fun getExpiringInventory(
        @Header("Authorization") authorization: String,
        @Query("withinDays") withinDays: Int = EXPIRING_WITHIN_DAYS,
    ): InventoryItemsResponseDto
}

/**
 * Inventory writes. Created from the mutation client (`ApiClientFactory.createMutationOkHttpClient`),
 * which never retries a connection failure transparently. Each call takes a new [TransmissionTracker]
 * as its `@Tag`, so a failure can be classified as "not sent" or "outcome unknown". The API has no
 * idempotency key: a write is never resent automatically.
 */
interface InventoryMutationApi {

    /** Returns 201 with the created item. */
    @POST("inventory")
    suspend fun createItem(
        @Header("Authorization") authorization: String,
        @Body request: CreateInventoryItemRequestDto,
        @Tag transmission: TransmissionTracker,
    ): Response<InventoryItemDto>

    /** Returns 200 with the updated item; another user's or an inactive item answers 404. */
    @PATCH("inventory/{id}")
    suspend fun updateItem(
        @Header("Authorization") authorization: String,
        @Path("id") itemId: String,
        @Body request: UpdateInventoryItemRequestDto,
        @Tag transmission: TransmissionTracker,
    ): Response<InventoryItemDto>

    /** Soft deactivation ("consumed"). Returns 204 with no body; a second call answers 404. */
    @DELETE("inventory/{id}")
    suspend fun deactivateItem(
        @Header("Authorization") authorization: String,
        @Path("id") itemId: String,
        @Tag transmission: TransmissionTracker,
    ): Response<Unit>
}
