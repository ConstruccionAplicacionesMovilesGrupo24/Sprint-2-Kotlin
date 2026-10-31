package com.campusmeal.android.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.campusmeal.android.core.outbox.OutboxDao
import com.campusmeal.android.core.outbox.OutboxEventEntity
import com.campusmeal.android.feature.analytics.data.FeatureAdoptionReportDao
import com.campusmeal.android.feature.analytics.data.FeatureAdoptionReportEntity
import com.campusmeal.android.feature.decision.data.local.ComparisonHistoryDao
import com.campusmeal.android.feature.decision.data.local.ComparisonHistoryEntity
import com.campusmeal.android.feature.inventory.data.local.ExpiringInventoryDao
import com.campusmeal.android.feature.inventory.data.local.ExpiringInventoryEntity
import com.campusmeal.android.feature.inventory.data.local.InventoryDraftDao
import com.campusmeal.android.feature.inventory.data.local.InventoryDraftEntity
import com.campusmeal.android.feature.inventory.data.local.InventoryItemDao
import com.campusmeal.android.feature.inventory.data.local.InventoryItemEntity
import com.campusmeal.android.feature.inventory.data.local.PendingConsumeDao
import com.campusmeal.android.feature.inventory.data.local.PendingConsumeEntity
import com.campusmeal.android.feature.profile.data.local.AccountProfileDao
import com.campusmeal.android.feature.profile.data.local.AccountProfileEntity
import com.campusmeal.android.feature.recipes.data.local.RecipeCatalogDao
import com.campusmeal.android.feature.recipes.data.local.RecipeCatalogMetadataEntity
import com.campusmeal.android.feature.recipes.data.local.RecipeCatalogRecipeEntity
import com.campusmeal.android.feature.restaurants.data.RestaurantCacheDao
import com.campusmeal.android.feature.restaurants.data.RestaurantDetailCacheEntity
import com.campusmeal.android.feature.restaurants.data.RestaurantSearchCacheEntity
import com.campusmeal.android.feature.analytics.data.ZoneDemandReportDao
import com.campusmeal.android.feature.analytics.data.ZoneDemandReportEntity

/**
 * Local cache database. It holds no seed data; feature entities are added with their features.
 * Tokens and other secrets must never be stored here. Account-owned rows carry the `auth/me` id.
 *
 * Every version bump needs an explicit migration in [DatabaseMigrations] and a committed schema file.
 */
@Database(
    entities = [
        CacheMetadataEntity::class,
        ExpiringInventoryEntity::class,
        AccountProfileEntity::class,
        InventoryItemEntity::class,
        InventoryDraftEntity::class,
        PendingConsumeEntity::class,
        RecipeCatalogMetadataEntity::class,
        RecipeCatalogRecipeEntity::class,
        OutboxEventEntity::class,
        ComparisonHistoryEntity::class,
        FeatureAdoptionReportEntity::class,
        RestaurantSearchCacheEntity::class,
        RestaurantDetailCacheEntity::class,
        ZoneDemandReportEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class CampusMealDatabase : RoomDatabase() {

    abstract fun cacheMetadataDao(): CacheMetadataDao

    abstract fun expiringInventoryDao(): ExpiringInventoryDao

    abstract fun accountProfileDao(): AccountProfileDao

    abstract fun inventoryItemDao(): InventoryItemDao

    abstract fun inventoryDraftDao(): InventoryDraftDao

    abstract fun pendingConsumeDao(): PendingConsumeDao

    abstract fun recipeCatalogDao(): RecipeCatalogDao

    abstract fun outboxDao(): OutboxDao

    abstract fun comparisonHistoryDao(): ComparisonHistoryDao

    abstract fun featureAdoptionReportDao(): FeatureAdoptionReportDao

    abstract fun restaurantCacheDao(): RestaurantCacheDao
    
    abstract fun zoneDemandReportDao(): ZoneDemandReportDao

    companion object {
        const val NAME = "campusmeal.db"

        fun create(context: Context): CampusMealDatabase =
            Room.databaseBuilder(context.applicationContext, CampusMealDatabase::class.java, NAME)
                .addMigrations(*DatabaseMigrations.ALL)
                .build()
    }
}
