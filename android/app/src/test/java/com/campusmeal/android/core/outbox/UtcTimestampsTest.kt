package com.campusmeal.android.core.outbox

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S4-11: the reused formatter must produce byte-identical `occurredAt` strings to the per-call formatter it
 * replaced. JVM only (`./gradlew :app:testDebugUnitTest --tests '*UtcTimestampsTest*'`); on the device the same
 * property is checked by the payload SHA-256 of the outbox burst (`docs/SPRINT4_MICROOPT_DANIEL.md`).
 */
class UtcTimestampsTest {

    /** Verbatim body of `OutboxRepository.isoUtc` before the optimization (dad0a47). */
    private fun reference(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    /** Expected strings computed independently (Python `datetime`, UTC). */
    private val boundaries = listOf(
        0L to "1970-01-01T00:00:00Z",
        999L to "1970-01-01T00:00:00Z", // milliseconds are truncated, never rounded
        -1L to "1969-12-31T23:59:59Z",
        1_798_761_599_999L to "2026-12-31T23:59:59Z",
        1_798_761_600_000L to "2027-01-01T00:00:00Z",
        1_835_481_599_999L to "2028-02-29T23:59:59Z",
        1_835_481_600_000L to "2028-03-01T00:00:00Z",
        1_796_083_199_999L to "2026-11-30T23:59:59Z",
        1_798_761_590_123L to "2026-12-31T23:59:50Z",
        2_147_483_648_000L to "2038-01-19T03:14:08Z",
        253_402_300_799_999L to "9999-12-31T23:59:59Z",
    )

    @Test
    fun boundariesMatchExpectedAndReference() {
        for ((millis, expected) in boundaries) {
            assertEquals("reference at $millis", expected, reference(millis))
            assertEquals("formatter at $millis", expected, UtcTimestamps.format(millis))
        }
    }

    @Test
    fun deviceZoneAndLocaleDoNotChangeOutput() {
        val zone = TimeZone.getDefault()
        val locale = Locale.getDefault()
        try {
            for (tz in listOf("America/Bogota", "Pacific/Kiritimati", "Asia/Kathmandu")) {
                for (loc in listOf(Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("th-TH-u-ca-buddhist"), Locale.forLanguageTag("fa-IR"))) {
                    TimeZone.setDefault(TimeZone.getTimeZone(tz))
                    Locale.setDefault(loc)
                    for ((millis, expected) in boundaries) assertEquals("$tz $loc $millis", expected, UtcTimestamps.format(millis))
                }
            }
        } finally {
            TimeZone.setDefault(zone)
            Locale.setDefault(locale)
        }
    }

    @Test
    fun randomInstantsMatchReference() {
        val random = Random(4_11L)
        val from = -2_208_988_800_000L // 1900-01-01
        val to = 4_102_444_800_000L // 2100-01-01
        repeat(100_000) {
            val millis = from + (random.nextDouble() * (to - from)).toLong()
            assertEquals("at $millis", reference(millis), UtcTimestamps.format(millis))
        }
    }

    @Test
    fun concurrentCallersMatchReference() {
        val threads = 8
        val perThread = 20_000
        val inputs = LongArray(threads * perThread) { 1_798_761_590_123L + it * 1_037L }
        val expected = inputs.map(::reference)
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val mismatches = (0 until threads).map { t ->
                pool.submit(
                    Callable {
                        barrier.await()
                        (t until inputs.size step threads).count { UtcTimestamps.format(inputs[it]) != expected[it] }
                    },
                )
            }.sumOf { it.get(60, TimeUnit.SECONDS) }
            assertEquals("mismatched timestamps under $threads concurrent callers", 0, mismatches)
        } finally {
            pool.shutdownNow()
        }
    }
}
