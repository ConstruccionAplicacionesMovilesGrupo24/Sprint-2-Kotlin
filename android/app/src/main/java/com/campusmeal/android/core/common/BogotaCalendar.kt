package com.campusmeal.android.core.common

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar dates in the agreed `America/Bogota` zone, independent of the device's selected time zone.
 * Dates are ISO `YYYY-MM-DD` strings: `java.time` needs API 26 and minSdk is 24.
 *
 * Day differences compare calendar dates, never elapsed 24-hour blocks.
 */
object BogotaCalendar {

    val ZONE: TimeZone = TimeZone.getTimeZone("America/Bogota")

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private const val MILLIS_PER_DAY = 86_400_000L

    /** Bogotá calendar date of a UTC instant, e.g. "2026-10-27". */
    fun dateOf(epochMillis: Long): String = isoFormat(ZONE).format(Date(epochMillis))

    /** Today's Bogotá date for [nowEpochMillis]. */
    fun today(nowEpochMillis: Long = System.currentTimeMillis()): String = dateOf(nowEpochMillis)

    /**
     * Calendar days from [fromIsoDate] to [toIsoDate]: 0 for the same date, 1 for the next day, negative
     * when [toIsoDate] is earlier. Returns null when either value is not a real ISO date.
     */
    fun daysBetween(fromIsoDate: String, toIsoDate: String): Int? {
        val from = epochDay(fromIsoDate) ?: return null
        val to = epochDay(toIsoDate) ?: return null
        return (to - from).toInt()
    }

    /** True for a real calendar date written as `YYYY-MM-DD` (2026-02-30 is false). */
    fun isIsoDate(value: String): Boolean = parseUtc(value) != null

    /** Milliseconds from [nowEpochMillis] until the next Bogotá midnight (always > 0). */
    fun millisUntilNextMidnight(nowEpochMillis: Long): Long {
        val next = Calendar.getInstance(ZONE, Locale.US).apply {
            timeInMillis = nowEpochMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1)
        }
        return (next.timeInMillis - nowEpochMillis).coerceAtLeast(1L)
    }

    /** "Oct 27" for an ISO date, for "as of" labels. Returns the input when it is not a date. */
    fun formatShortDate(isoDate: String): String {
        val parsed = parseUtc(isoDate) ?: return isoDate
        return SimpleDateFormat("MMM d", Locale.US).apply { timeZone = UTC }.format(parsed)
    }

    private fun epochDay(isoDate: String): Long? = parseUtc(isoDate)?.time?.div(MILLIS_PER_DAY)

    /** Strict parse at UTC midnight, so the division by a day is exact (UTC has no daylight saving). */
    private fun parseUtc(isoDate: String): Date? {
        if (!ISO_DATE.matches(isoDate)) return null
        return try {
            isoFormat(UTC).apply { isLenient = false }.parse(isoDate)
        } catch (_: ParseException) {
            null
        }
    }

    private fun isoFormat(zone: TimeZone) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }

    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
}
