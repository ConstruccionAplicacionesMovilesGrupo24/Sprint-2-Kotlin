package com.campusmeal.android.core.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration

/**
 * How current a locally stored data set is.
 *
 * [Missing] means no successful synchronization was ever stored: it is not the same as a synchronized
 * result that happens to be empty, which is [Fresh] or [Stale] with an empty payload.
 */
sealed interface Freshness {

    data object Missing : Freshness

    /** Synchronized within its time-to-live. */
    data class Fresh(override val syncedAtEpochMillis: Long) : Synced

    /** Older than its time-to-live: show it with its timestamp while (or instead of) refreshing. */
    data class Stale(override val syncedAtEpochMillis: Long) : Synced

    sealed interface Synced : Freshness {
        /** UTC instant of the last successful synchronization. */
        val syncedAtEpochMillis: Long
    }

    companion object {

        /** [syncedAtEpochMillis] null means never synchronized. */
        fun of(syncedAtEpochMillis: Long?, ttl: Duration, nowEpochMillis: Long = System.currentTimeMillis()): Freshness =
            if (syncedAtEpochMillis == null) Missing else synced(syncedAtEpochMillis, ttl, nowEpochMillis)

        /** For data known to be synchronized. A future timestamp (device clock change) counts as fresh. */
        fun synced(syncedAtEpochMillis: Long, ttl: Duration, nowEpochMillis: Long = System.currentTimeMillis()): Synced =
            if (nowEpochMillis - syncedAtEpochMillis <= ttl.inWholeMilliseconds) Fresh(syncedAtEpochMillis) else Stale(syncedAtEpochMillis)
    }
}

private val BOGOTA: TimeZone = TimeZone.getTimeZone("America/Bogota")

/**
 * Formats a stored UTC instant for "Updated …" labels, in the agreed America/Bogota calendar.
 * `java.time` needs API 26 and minSdk is 24, hence `SimpleDateFormat`.
 */
fun formatSyncedAt(epochMillis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US)
        .apply { timeZone = BOGOTA }
        .format(Date(epochMillis))
