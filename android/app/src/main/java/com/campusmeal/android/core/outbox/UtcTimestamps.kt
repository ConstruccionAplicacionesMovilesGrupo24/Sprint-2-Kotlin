package com.campusmeal.android.core.outbox

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wire format of every outbox `occurredAt`: UTC, whole seconds (milliseconds truncated), Latin digits and the
 * Gregorian calendar whatever the device locale, e.g. `2026-12-31T23:59:50Z`.
 *
 * S4-11 (`docs/SPRINT4_MICROOPT_DANIEL.md`): building and configuring a [SimpleDateFormat] for every event
 * was measurable in event admission. [SimpleDateFormat] is mutable and not thread-safe, so a single shared
 * instance would be invalid; each thread keeps its own, configured once. Admissions run on the bounded
 * coroutine dispatchers (Default, IO) and the main thread, so the number of formatters is bounded too. A
 * formatter holds the pattern, the UTC zone, US symbols and the last instant it formatted: no account id,
 * event id or payload. java.time is not used because it needs API 26 (minSdk is 24) or desugaring.
 */
internal object UtcTimestamps {

    private const val PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    private val formatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() =
            SimpleDateFormat(PATTERN, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun format(epochMillis: Long): String = formatter.get()!!.format(Date(epochMillis))
}
