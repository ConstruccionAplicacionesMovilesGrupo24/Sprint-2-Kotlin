package com.campusmeal.android.feature.inventory.data.remote

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

interface InventoryApi {

    /**
     * Returns the current user's items expiring within [withinDays] days, already filtered and
     * ordered by priority by the backend. [authorization] is the full `Bearer` header value.
     * The path is relative because the base URL already ends in `/api/v1/`.
     */
    @GET("inventory/expiring")
    suspend fun getExpiringInventory(
        @Header("Authorization") authorization: String,
        @Query("withinDays") withinDays: Int = EXPIRING_WITHIN_DAYS,
    ): ExpiringInventoryResponseDto

    /** Returns 201 with the created item. */
    @POST("inventory")
    suspend fun createItem(
        @Header("Authorization") authorization: String,
        @Body request: CreateInventoryItemRequestDto,
    ): InventoryItemDto

    /** Returns 200 with the updated item; another user's item answers 404. */
    @PATCH("inventory/{id}")
    suspend fun updateItem(
        @Header("Authorization") authorization: String,
        @Path("id") itemId: String,
        @Body request: UpdateInventoryItemRequestDto,
    ): InventoryItemDto

    /** Soft deactivation ("consumed"). Returns 204 with no body, so the response is not decoded. */
    @DELETE("inventory/{id}")
    suspend fun deactivateItem(
        @Header("Authorization") authorization: String,
        @Path("id") itemId: String,
    ): Response<Unit>
}
