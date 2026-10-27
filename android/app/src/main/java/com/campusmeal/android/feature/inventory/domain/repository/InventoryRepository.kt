package com.campusmeal.android.feature.inventory.domain.repository

import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.inventory.domain.model.EXPIRING_WITHIN_DAYS
import com.campusmeal.android.feature.inventory.domain.model.ExpiringInventoryResult
import com.campusmeal.android.feature.inventory.domain.model.InventoryItemDraft
import com.campusmeal.android.feature.inventory.domain.model.InventoryWriteResult

/**
 * Every operation runs for the session [AccountSessionScope] the caller captured when it started:
 * requests use only that session's credentials and local results are applied only while it is active.
 * Cache cleanup is owned by the session lifecycle, not by this repository.
 */
interface InventoryRepository {

    /**
     * Answers BQ2 for the account of [scope]: items expiring within [EXPIRING_WITHIN_DAYS] days, in the
     * backend's priority order.
     */
    suspend fun getExpiringInventory(scope: AccountSessionScope): ExpiringInventoryResult

    suspend fun addItem(scope: AccountSessionScope, draft: InventoryItemDraft): InventoryWriteResult

    suspend fun updateItem(scope: AccountSessionScope, itemId: String, draft: InventoryItemDraft): InventoryWriteResult

    /** Marks the item as consumed: `DELETE inventory/{id}`, a soft deactivation on the backend. */
    suspend fun consumeItem(scope: AccountSessionScope, itemId: String): InventoryWriteResult
}
