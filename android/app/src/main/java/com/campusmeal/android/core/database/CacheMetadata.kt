package com.campusmeal.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Records when a cached data set was last synchronized, to support offline-with-cache states.
 * Account-owned data sets put the account in the key, for example `inventory.expiring:{accountId}`.
 */
@Entity(tableName = "cache_metadata")
data class CacheMetadataEntity(
    @PrimaryKey val cacheKey: String,
    val lastSyncedAtEpochMillis: Long,
)

@Dao
interface CacheMetadataDao {

    @Upsert
    suspend fun upsert(metadata: CacheMetadataEntity)

    @Query("SELECT * FROM cache_metadata WHERE cacheKey = :cacheKey")
    fun observe(cacheKey: String): Flow<CacheMetadataEntity?>

    @Query("SELECT * FROM cache_metadata WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): CacheMetadataEntity?

    @Query("DELETE FROM cache_metadata WHERE cacheKey = :cacheKey")
    suspend fun delete(cacheKey: String)

    /**
     * Deletes the rows of one data set ([prefix] is a SQL `LIKE` pattern such as `inventory.expiring%`)
     * except [keep]. Other data sets are untouched.
     */
    @Query("DELETE FROM cache_metadata WHERE cacheKey LIKE :prefix AND cacheKey != :keep")
    suspend fun deleteByPrefixExcept(prefix: String, keep: String)

    @Query("DELETE FROM cache_metadata")
    suspend fun clear()
}
