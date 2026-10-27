package com.campusmeal.android.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.campusmeal.android.feature.inventory.data.local.ExpiringInventoryDao
import com.campusmeal.android.feature.inventory.data.local.ExpiringInventoryEntity
import com.campusmeal.android.feature.profile.data.local.AccountProfileDao
import com.campusmeal.android.feature.profile.data.local.AccountProfileEntity

/**
 * Local cache database. It holds no seed data; feature entities are added with their features.
 * Tokens and other secrets must never be stored here. Account-owned rows carry the `auth/me` id.
 *
 * Every version bump needs an explicit migration in [DatabaseMigrations] and a committed schema file.
 */
@Database(
    entities = [CacheMetadataEntity::class, ExpiringInventoryEntity::class, AccountProfileEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class CampusMealDatabase : RoomDatabase() {

    abstract fun cacheMetadataDao(): CacheMetadataDao

    abstract fun expiringInventoryDao(): ExpiringInventoryDao

    abstract fun accountProfileDao(): AccountProfileDao

    companion object {
        const val NAME = "campusmeal.db"

        fun create(context: Context): CampusMealDatabase =
            Room.databaseBuilder(context.applicationContext, CampusMealDatabase::class.java, NAME)
                .addMigrations(*DatabaseMigrations.ALL)
                .build()
    }
}
