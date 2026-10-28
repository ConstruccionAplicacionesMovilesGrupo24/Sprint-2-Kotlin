package com.campusmeal.android.feature.inventory.domain.repository

import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.inventory.domain.model.CompleteInventorySnapshot
import com.campusmeal.android.feature.inventory.domain.model.DraftFields
import com.campusmeal.android.feature.inventory.domain.model.EXPIRING_WITHIN_DAYS
import com.campusmeal.android.feature.inventory.domain.model.ExpiringInventorySnapshot
import com.campusmeal.android.feature.inventory.domain.model.InventoryDraft
import com.campusmeal.android.feature.inventory.domain.model.InventoryRefreshResult
import com.campusmeal.android.feature.inventory.domain.model.MutationOutcome
import com.campusmeal.android.feature.inventory.domain.model.PendingConsume
import kotlinx.coroutines.flow.Flow

/**
 * Every operation runs for the session [AccountSessionScope] the caller captured when it started:
 * requests use only that session's credentials and local results are applied only while it is active.
 * Cache cleanup is owned by the session lifecycle, not by this repository.
 *
 * Reads are local first: `observe…` streams emit the stored snapshot immediately (null when the account
 * never synchronized that data set) and again after every change. `refresh…` updates them from the
 * backend; a failed refresh keeps the previous snapshot. Refreshes and writes of one account are
 * serialized, so a refresh never overwrites a later write's result.
 */
interface InventoryRepository {

    /**
     * The complete active inventory, including expired items and zero quantities. Future BQ3 (S3-05)
     * consumes this stream; [CompleteInventorySnapshot.revision] changes after every sync or confirmed write.
     */
    fun observeCompleteInventory(scope: AccountSessionScope): Flow<CompleteInventorySnapshot?>

    /** Without [force], skips the request while the snapshot is fresh (TTL, same Bogotá date, not invalidated). */
    suspend fun refreshCompleteInventory(scope: AccountSessionScope, force: Boolean = false): InventoryRefreshResult

    /** BQ2: items expiring within [EXPIRING_WITHIN_DAYS] days, in the backend's priority order. */
    fun observeExpiringInventory(scope: AccountSessionScope): Flow<ExpiringInventorySnapshot?>

    suspend fun refreshExpiringInventory(scope: AccountSessionScope, force: Boolean = false): InventoryRefreshResult

    fun observeDrafts(scope: AccountSessionScope): Flow<List<InventoryDraft>>

    fun observePendingConsumes(scope: AccountSessionScope): Flow<List<PendingConsume>>

    /** Saves the form exactly as typed. [targetItemId] is null for the add draft. Nothing is sent. */
    suspend fun saveDraft(scope: AccountSessionScope, draftId: String, targetItemId: String?, fields: DraftFields)

    /** Explicit discard. A draft whose request is running cannot be discarded. */
    suspend fun discardDraft(scope: AccountSessionScope, draftId: String)

    /** Marks interrupted submissions as outcome-unknown and applies the seven-day draft retention. */
    suspend fun recoverSubmissions(scope: AccountSessionScope)

    /** Sends one draft once. Never retried automatically; [online] is the device's connectivity hint. */
    suspend fun submitDraft(scope: AccountSessionScope, draftId: String, online: Boolean): MutationOutcome

    /** Marks the item as consumed: `DELETE inventory/{id}`, a soft deactivation on the backend. */
    suspend fun consumeItem(scope: AccountSessionScope, itemId: String, itemName: String, online: Boolean): MutationOutcome

    /** Removes a consume marker once a complete sync after it has been shown to the user. */
    suspend fun dismissPendingConsume(scope: AccountSessionScope, itemId: String)
}
