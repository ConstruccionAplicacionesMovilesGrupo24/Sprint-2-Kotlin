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

    /**
     * v3 → v4 (S3-03): complete inventory and drafts. Purely additive: no existing row is changed or
     * deleted, so the S3-02 session, profile and BQ2 cache survive the upgrade.
     *
     * - `inventory_items`: each account's complete snapshot (`GET inventory`).
     * - `inventory_drafts`: add/edit drafts with raw input and their last submission outcome.
     * - `inventory_pending_consumes`: consumes whose outcome is not confirmed yet.
     * - `cache_metadata.invalidatedAtEpochMillis`: nullable; set after a confirmed write.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `inventory_items` (" +
                    "`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`quantity` REAL NOT NULL, `unit` TEXT NOT NULL, `expirationDate` TEXT NOT NULL, " +
                    "`remainingDays` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `itemId`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `inventory_drafts` (" +
                    "`accountId` TEXT NOT NULL, `draftId` TEXT NOT NULL, `targetItemId` TEXT, " +
                    "`name` TEXT NOT NULL, `quantityText` TEXT NOT NULL, `unit` TEXT NOT NULL, " +
                    "`expirationDateText` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, " +
                    "`lastSubmitOutcome` TEXT NOT NULL, `lastSubmitAtEpochMillis` INTEGER, " +
                    "`lastHttpCode` INTEGER, `lastErrorCode` TEXT, PRIMARY KEY(`accountId`, `draftId`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `inventory_pending_consumes` (" +
                    "`accountId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `itemName` TEXT NOT NULL, " +
                    "`outcome` TEXT NOT NULL, `startedAtEpochMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `itemId`))",
            )
            db.execSQL("ALTER TABLE `cache_metadata` ADD COLUMN `invalidatedAtEpochMillis` INTEGER")
        }
    }

    /**
     * v4 -> v5 (S3-05): the BQ3 recipe catalog. Purely additive: profile, inventory snapshots, drafts and
     * pending consumes are untouched.
     *
     * - `recipe_catalog_metadata`: the single stored version (contract, catalog version, generatedAt,
     *   content hash, local fetch time).
     * - `recipe_catalog_recipes`: one row per recipe of that version, as its validated contract JSON.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recipe_catalog_metadata` (" +
                    "`id` INTEGER NOT NULL, `contractVersion` INTEGER NOT NULL, `catalogVersion` TEXT NOT NULL, " +
                    "`generatedAt` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `fetchedAtEpochMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `recipe_catalog_recipes` (" +
                    "`recipeId` TEXT NOT NULL, `catalogVersion` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                    "`recipeJson` TEXT NOT NULL, PRIMARY KEY(`recipeId`))",
            )
        }
    }

    /**
     * v5 -> v6 (S3-09): the durable analytics outbox. Purely additive. Rows carry the `auth/me` account
     * id and are never deleted by account cleanup: pending events survive logout and are sent the next
     * time the same account is active.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `outbox_events` (" +
                    "`clientEventId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`recommendationId` TEXT, `payloadJson` TEXT NOT NULL, " +
                    "`occurredAtEpochMillis` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `lastError` TEXT, " +
                    "PRIMARY KEY(`clientEventId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_outbox_events_accountId_status_createdAtEpochMillis` " +
                    "ON `outbox_events` (`accountId`, `status`, `createdAtEpochMillis`)",
            )
        }
    }

    /**
     * v6 -> v7 (S3-11): the BQ5 comparison history (last 10 per account, raw backend response) and the
     * BQ6 report cache (per account and month). Purely additive; both are per-account read data.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `comparison_history` (" +
                    "`recommendationId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `comparedAtEpochMillis` INTEGER NOT NULL, " +
                    "`campusId` TEXT, `availableMinutes` INTEGER NOT NULL, `maximumBudget` INTEGER NOT NULL, " +
                    "`responseJson` TEXT NOT NULL, PRIMARY KEY(`recommendationId`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_comparison_history_accountId_comparedAtEpochMillis` " +
                    "ON `comparison_history` (`accountId`, `comparedAtEpochMillis`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `feature_adoption_reports` (" +
                    "`accountId` TEXT NOT NULL, `month` TEXT NOT NULL, `responseJson` TEXT NOT NULL, " +
                    "`fetchedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `month`))",
            )
        }
    }
    
    /**
     * v7 -> v8 (S3-13): the BQ7 restaurant search and detail caches. Purely additive; both are per-account
     * read data.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `restaurant_search_cache` (" +
                    "`accountId` TEXT NOT NULL, " +
                    "`contextKey` TEXT NOT NULL, " +
                    "`responseJson` TEXT NOT NULL, " +
                    "`fetchedAtEpochMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `contextKey`))",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `restaurant_detail_cache` (" +
                    "`accountId` TEXT NOT NULL, " +
                    "`restaurantId` TEXT NOT NULL, " +
                    "`responseJson` TEXT NOT NULL, " +
                    "`fetchedAtEpochMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`, `restaurantId`))",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
}
