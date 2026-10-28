package com.campusmeal.android.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Explicit Room migrations. Destructive migration is never used, so cached data survives upgrades. */
object DatabaseMigrations {

    /** v1 → v2: adds the BQ2 expiring-inventory cache. Existing `cache_metadata` rows are kept. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `expiring_inventory_items` (" +
                    "`itemId` TEXT NOT NULL, `name` TEXT NOT NULL, `quantity` REAL NOT NULL, " +
                    "`unit` TEXT NOT NULL, `expirationDate` TEXT NOT NULL, `remainingDays` INTEGER NOT NULL, " +
                    "`priorityIndex` INTEGER NOT NULL, PRIMARY KEY(`itemId`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_expiring_inventory_items_priorityIndex` " +
                    "ON `expiring_inventory_items` (`priorityIndex`)",
            )
        }
    }

    /**
     * v2 → v3 (S3-02): account ownership.
     *
     * - Adds `account_profile` for the identified account's offline profile.
     * - Rebuilds `expiring_inventory_items` with an `accountId` owner. Its v2 rows have no owner and
     *   cannot be attributed safely, so they are discarded (cache only: the next online BQ2 load
     *   refills it), together with their ownerless `inventory.expiring` metadata row.
     *
     * Other `cache_metadata` rows and every other table are untouched.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `account_profile` (" +
                    "`accountId` TEXT NOT NULL, `name` TEXT NOT NULL, `email` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`))",
            )
            db.execSQL("DROP TABLE IF EXISTS `expiring_inventory_items`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `expiring_inventory_items` (" +
                    "`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`quantity` REAL NOT NULL, `unit` TEXT NOT NULL, `expirationDate` TEXT NOT NULL, " +
                    "`remainingDays` INTEGER NOT NULL, `priorityIndex` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `itemId`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_expiring_inventory_items_accountId_priorityIndex` " +
                    "ON `expiring_inventory_items` (`accountId`, `priorityIndex`)",
            )
            db.execSQL("DELETE FROM `cache_metadata` WHERE `cacheKey` = 'inventory.expiring'")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
