package com.campusmeal.android.feature.restaurants.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert


@Entity(
    tableName = "restaurant_search_cache",
    primaryKeys = ["accountId", "contextKey"],
)
data class RestaurantSearchCacheEntity(
    val accountId: String,
    val contextKey: String,
    val responseJson: String,
    val fetchedAtEpochMillis: Long,
)

@Entity(
    tableName = "restaurant_detail_cache",
    primaryKeys = ["accountId", "restaurantId"],
)
data class RestaurantDetailCacheEntity(
    val accountId: String,
    val restaurantId: String,
    val responseJson: String,
    val fetchedAtEpochMillis: Long,
)

@Dao
interface RestaurantCacheDao {

    @Upsert
    suspend fun upsertSearch(entry: RestaurantSearchCacheEntity)

    @Query(
        "SELECT * FROM restaurant_search_cache " +
            "WHERE accountId = :accountId AND contextKey = :contextKey"
    )
    suspend fun getSearch(
        accountId: String,
        contextKey: String,
    ): RestaurantSearchCacheEntity?

    @Upsert
    suspend fun upsertDetail(entry: RestaurantDetailCacheEntity)

    @Query(
        "SELECT * FROM restaurant_detail_cache " +
            "WHERE accountId = :accountId AND restaurantId = :restaurantId"
    )
    suspend fun getDetail(
        accountId: String,
        restaurantId: String,
    ): RestaurantDetailCacheEntity?

    @Query(
        "DELETE FROM restaurant_search_cache WHERE accountId = :accountId"
    )
    suspend fun deleteSearches(accountId: String)

    @Query(
        "DELETE FROM restaurant_detail_cache WHERE accountId = :accountId"
    )
    suspend fun deleteDetails(accountId: String)

    @Query(
        "DELETE FROM restaurant_search_cache WHERE accountId != :accountId"
    )
    suspend fun deleteSearchesExcept(accountId: String)

    @Query(
        "DELETE FROM restaurant_detail_cache WHERE accountId != :accountId"
    )
    suspend fun deleteDetailsExcept(accountId: String)
}