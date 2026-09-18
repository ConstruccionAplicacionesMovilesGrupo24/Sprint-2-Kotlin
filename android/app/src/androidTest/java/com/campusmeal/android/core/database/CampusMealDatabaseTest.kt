package com.campusmeal.android.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampusMealDatabaseTest {

    private lateinit var database: CampusMealDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            CampusMealDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertReplacesCacheMetadataAndClearRemovesIt() = runTest {
        val dao = database.cacheMetadataDao()

        dao.upsert(CacheMetadataEntity(cacheKey = "sample-cache", lastSyncedAtEpochMillis = 1_000))
        dao.upsert(CacheMetadataEntity(cacheKey = "sample-cache", lastSyncedAtEpochMillis = 2_000))
        assertEquals(2_000L, dao.observe("sample-cache").first()?.lastSyncedAtEpochMillis)

        dao.clear()
        assertNull(dao.observe("sample-cache").first())
    }
}
