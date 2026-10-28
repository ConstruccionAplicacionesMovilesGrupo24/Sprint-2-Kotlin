package com.campusmeal.android.feature.inventory.data.local

import androidx.room.withTransaction
import com.campusmeal.android.core.database.CacheMetadataEntity
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.feature.inventory.data.mapper.toCompleteEntities
import com.campusmeal.android.feature.inventory.data.mapper.toDomain
import com.campusmeal.android.feature.inventory.data.mapper.toEntities
import com.campusmeal.android.feature.inventory.domain.model.CompleteInventorySnapshot
import com.campusmeal.android.feature.inventory.domain.model.ExpiringInventorySnapshot
import com.campusmeal.android.feature.inventory.domain.model.InventoryDraft
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem
import com.campusmeal.android.feature.inventory.domain.model.PendingConsume
import com.campusmeal.android.feature.inventory.domain.model.SubmitOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Room storage for every account-owned inventory data set:
 * - the complete snapshot (`inventory_items`, metadata `inventory.items:{accountId}`);
 * - the BQ2 snapshot (`expiring_inventory_items`, metadata `inventory.expiring:{accountId}`);
 * - add/edit drafts (`inventory_drafts`) and unconfirmed consumes (`inventory_pending_consumes`).
 *
 * The metadata row, not the item count, tells whether a sync ever happened, so an empty successful
 * result is still a stored answer. Each snapshot has its own row: one endpoint's success never
 * certifies the other. Every read and write names the owning account.
 */
class RoomInventoryStore(private val database: CampusMealDatabase) : AccountScopedStore {

    private val metadataDao = database.cacheMetadataDao()
    private val completeDao = database.inventoryItemDao()
    private val expiringDao = database.expiringInventoryDao()
    private val draftDao = database.inventoryDraftDao()
    private val consumeDao = database.pendingConsumeDao()

    // Complete inventory

    /** Emits the stored snapshot now and after every change; null while none was ever synchronized. */
    fun observeComplete(accountId: String): Flow<CompleteInventorySnapshot?> =
        database.invalidationTracker.createFlow(COMPLETE_TABLE, METADATA_TABLE)
            .map { readComplete(accountId) }
            .distinctUntilChanged()

    suspend fun readComplete(accountId: String): CompleteInventorySnapshot? =
        database.withTransaction {
            val metadata = metadataDao.get(completeKey(accountId)) ?: return@withTransaction null
            CompleteInventorySnapshot(
                items = completeDao.getItems(accountId).map { it.toDomain() },
                syncedAtEpochMillis = metadata.lastSyncedAtEpochMillis,
                invalidatedAtEpochMillis = metadata.invalidatedAtEpochMillis,
            )
        }

    suspend fun completeMetadata(accountId: String): CacheMetadataEntity? = metadataDao.get(completeKey(accountId))

    /** Replaces the rows and the sync timestamp in one transaction; also clears any invalidation. */
    suspend fun replaceComplete(accountId: String, items: List<InventoryItem>, syncedAtEpochMillis: Long) {
        database.withTransaction {
            completeDao.replaceItems(accountId, items.toCompleteEntities(accountId))
            metadataDao.upsert(CacheMetadataEntity(completeKey(accountId), syncedAtEpochMillis))
        }
    }

    // BQ2 expiring list

    fun observeExpiring(accountId: String): Flow<ExpiringInventorySnapshot?> =
        database.invalidationTracker.createFlow(EXPIRING_TABLE, METADATA_TABLE)
            .map { readExpiring(accountId) }
            .distinctUntilChanged()

    suspend fun readExpiring(accountId: String): ExpiringInventorySnapshot? =
        database.withTransaction {
            val metadata = metadataDao.get(expiringKey(accountId)) ?: return@withTransaction null
            ExpiringInventorySnapshot(
                items = expiringDao.getItems(accountId).map { it.toDomain() },
                syncedAtEpochMillis = metadata.lastSyncedAtEpochMillis,
                invalidatedAtEpochMillis = metadata.invalidatedAtEpochMillis,
            )
        }

    suspend fun expiringMetadata(accountId: String): CacheMetadataEntity? = metadataDao.get(expiringKey(accountId))

    suspend fun replaceExpiring(accountId: String, items: List<InventoryItem>, syncedAtEpochMillis: Long) {
        database.withTransaction {
            expiringDao.replaceItems(accountId, items.toEntities(accountId))
            metadataDao.upsert(CacheMetadataEntity(expiringKey(accountId), syncedAtEpochMillis))
        }
    }

    // Drafts

    fun observeDrafts(accountId: String): Flow<List<InventoryDraft>> =
        draftDao.observe(accountId).map { rows -> rows.map { it.toDomain() } }

    suspend fun getDraft(accountId: String, draftId: String): InventoryDraftEntity? = draftDao.get(accountId, draftId)

    suspend fun upsertDraft(draft: InventoryDraftEntity) = draftDao.upsert(draft)

    suspend fun updateDraftOutcome(
        accountId: String,
        draftId: String,
        outcome: SubmitOutcome,
        atEpochMillis: Long?,
        httpCode: Int? = null,
        errorCode: String? = null,
    ) = draftDao.updateOutcome(accountId, draftId, outcome, atEpochMillis, httpCode, errorCode)

    suspend fun deleteDraft(accountId: String, draftId: String) = draftDao.delete(accountId, draftId)

    // Consume markers

    fun observePendingConsumes(accountId: String): Flow<List<PendingConsume>> =
        consumeDao.observe(accountId).map { rows -> rows.map { it.toDomain() } }

    suspend fun getPendingConsume(accountId: String, itemId: String): PendingConsumeEntity? = consumeDao.get(accountId, itemId)

    suspend fun upsertPendingConsume(marker: PendingConsumeEntity) = consumeDao.upsert(marker)

    suspend fun deletePendingConsume(accountId: String, itemId: String) = consumeDao.delete(accountId, itemId)

    // Lifecycle of submissions

    /**
     * A confirmed write: removes its draft or marker and marks both snapshots stale, atomically. No
     * snapshot timestamp moves: a single write never certifies a complete sync.
     */
    suspend fun confirmApplied(accountId: String, draftId: String?, consumedItemId: String?, atEpochMillis: Long) {
        database.withTransaction {
            draftId?.let { draftDao.delete(accountId, it) }
            consumedItemId?.let { consumeDao.delete(accountId, it) }
            metadataDao.invalidate(listOf(completeKey(accountId), expiringKey(accountId)), atEpochMillis)
        }
    }

    /**
     * Restores honest states after a restart: SUBMITTING rows nobody is sending become OUTCOME_UNKNOWN,
     * and drafts past the seven-day retention without an unresolved submission are deleted.
     */
    suspend fun recoverSubmissions(
        accountId: String,
        inFlightDraftIds: List<String>,
        inFlightConsumeIds: List<String>,
        draftCutoffEpochMillis: Long,
    ) {
        database.withTransaction {
            draftDao.markInterrupted(accountId, inFlightDraftIds)
            consumeDao.markInterrupted(accountId, inFlightConsumeIds)
            draftDao.deleteOlderThan(accountId, draftCutoffEpochMillis)
        }
    }

    // AccountScopedStore

    /** Logout: the account's snapshots, drafts and markers are personal data and are all deleted. */
    override suspend fun deleteAccount(accountId: String) {
        database.withTransaction {
            completeDao.deleteItems(accountId)
            expiringDao.deleteItems(accountId)
            draftDao.deleteAccount(accountId)
            consumeDao.deleteAccount(accountId)
            metadataDao.delete(completeKey(accountId))
            metadataDao.delete(expiringKey(accountId))
        }
    }

    /**
     * Another account is about to become active. Drafts and markers of other accounts are deleted too:
     * they hold personal input, and the server stays authoritative for anything they may have sent.
     */
    override suspend fun deleteAllExcept(accountId: String) {
        database.withTransaction {
            completeDao.deleteItemsExcept(accountId)
            expiringDao.deleteItemsExcept(accountId)
            draftDao.deleteAllExcept(accountId)
            consumeDao.deleteAllExcept(accountId)
            metadataDao.deleteByPrefixExcept(prefix = "$COMPLETE_KEY_PREFIX%", keep = completeKey(accountId))
            // Also matches the ownerless Sprint 2 key `inventory.expiring`, which migration 2 → 3 deletes.
            metadataDao.deleteByPrefixExcept(prefix = "$EXPIRING_KEY_PREFIX%", keep = expiringKey(accountId))
        }
    }

    companion object {
        private const val COMPLETE_KEY_PREFIX = "inventory.items"
        private const val EXPIRING_KEY_PREFIX = "inventory.expiring"

        private const val COMPLETE_TABLE = "inventory_items"
        private const val EXPIRING_TABLE = "expiring_inventory_items"
        private const val METADATA_TABLE = "cache_metadata"

        fun completeKey(accountId: String) = "$COMPLETE_KEY_PREFIX:$accountId"

        fun expiringKey(accountId: String) = "$EXPIRING_KEY_PREFIX:$accountId"
    }
}
