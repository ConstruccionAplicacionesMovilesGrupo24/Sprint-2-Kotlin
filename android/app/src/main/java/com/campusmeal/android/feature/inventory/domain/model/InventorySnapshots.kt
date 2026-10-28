package com.campusmeal.android.feature.inventory.domain.model

import com.campusmeal.android.core.common.BogotaCalendar

/** BQ2 window: items expiring within the next three days (0 = today). */
const val EXPIRING_WITHIN_DAYS = 3

/**
 * The account's last successful BQ2 answer (`GET inventory/expiring`), in the backend's priority
 * order. Each item's `remainingDays` was computed by the server on [syncedBogotaDate]; on a later day
 * this snapshot is historical and must be labelled so, never re-scored on the device.
 */
data class ExpiringInventorySnapshot(
    val items: List<InventoryItem>,
    val syncedAtEpochMillis: Long,
    /** Set after a confirmed write until the next successful sync. */
    val invalidatedAtEpochMillis: Long?,
) {
    val syncedBogotaDate: String get() = BogotaCalendar.dateOf(syncedAtEpochMillis)
}

/**
 * The account's last successful complete inventory (`GET inventory`): every active item, including
 * expired items and zero quantities, in the backend's order. An empty [items] list is a valid answer.
 *
 * This is the input future BQ3 (S3-05) evaluates; it needs no other inventory storage.
 */
data class CompleteInventorySnapshot(
    val items: List<InventoryItem>,
    val syncedAtEpochMillis: Long,
    /** Set after a confirmed write until the next successful sync: the content is behind the server. */
    val invalidatedAtEpochMillis: Long?,
) {
    /** Changes whenever the snapshot is replaced or invalidated; part of the BQ3 result key (plan §5.4). */
    val revision: String get() = "$syncedAtEpochMillis:${invalidatedAtEpochMillis ?: 0}"

    /**
     * Items a recipe may use on [todayIsoDate] (Bogotá): positive quantity and not expired, judged by
     * calendar date from `expirationDate`, not by the stored server `remainingDays`.
     */
    fun usableIngredients(todayIsoDate: String): List<InventoryItem> =
        items.filter { item ->
            val days = BogotaCalendar.daysBetween(todayIsoDate, item.expirationDate)
            item.quantity > 0.0 && days != null && days >= 0
        }
}

/** Result of one refresh attempt. The stored snapshot is only replaced by [Refreshed]. */
sealed interface InventoryRefreshResult {

    data object Refreshed : InventoryRefreshResult

    /** Skipped: the snapshot is fresh, or a refresh that started after this request already succeeded. */
    data object AlreadyCurrent : InventoryRefreshResult

    /** No session, or the backend answered 401. */
    data object Unauthorized : InventoryRefreshResult

    /** The caller's session ended meanwhile; nothing was stored. */
    data object SessionEnded : InventoryRefreshResult

    /** Nothing was stored; the previous snapshot, if any, is kept with its age. */
    data class Failed(val error: InventoryError) : InventoryRefreshResult
}

sealed interface InventoryError {

    /** Connectivity failure or HTTP 5xx. Null [httpCode] means no HTTP response. */
    data class BackendUnavailable(val httpCode: Int?) : InventoryError

    /** Any other non-2xx status except 401. The response body is not kept. */
    data class Http(val code: Int) : InventoryError

    /** The response broke the contract: a required field was missing or invalid. */
    data object InvalidResponse : InventoryError
}
