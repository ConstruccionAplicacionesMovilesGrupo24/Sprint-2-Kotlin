package com.campusmeal.android.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CampusMealDatabase::class.java,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun deleteDatabases() {
        context.deleteDatabase(TEST_DATABASE)
        context.deleteDatabase(CampusMealDatabase.NAME)
    }

    @Test
    fun migration1To2KeepsCacheMetadataAndMatchesExportedSchema() {
        helper.createDatabase(TEST_DATABASE, 1).use { db ->
            db.execSQL("INSERT INTO cache_metadata (cacheKey, lastSyncedAtEpochMillis) VALUES ('existing', 1234)")
        }

        helper.runMigrationsAndValidate(TEST_DATABASE, 2, true, DatabaseMigrations.MIGRATION_1_2).use { db ->
            db.query("SELECT lastSyncedAtEpochMillis FROM cache_metadata WHERE cacheKey = 'existing'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1234L, cursor.getLong(0))
            }
            db.query("SELECT COUNT(*) FROM expiring_inventory_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun productionBuilderRegistersMigrationFromVersion1() {
        helper.createDatabase(CampusMealDatabase.NAME, 1).close()

        val database = CampusMealDatabase.create(context)
        try {
            // Opening fails with IllegalStateException if the builder lacks the 1 -> 2 migration.
            assertEquals(2, database.openHelper.writableDatabase.version)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-test.db"
    }
}
