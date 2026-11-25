package com.campusmeal.android.core.common

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Custom trace sections for the Sprint 4 micro-optimizations (S4-02, `android/docs/SPRINT4_PROFILING_PROTOCOL.md`).
 * They only mark time in a system trace: no data, ids or text of the user is written. Names must not
 * change between the "before" and the "after" capture of a pair.
 */
internal object TraceSections {
    /** BQ3 evaluation of the whole catalog, on `Dispatchers.Default` (S4-05). */
    const val BQ3_EVALUATE = "cm.bq3.evaluate"

    /** One Room read of a cached restaurant search or detail (S4-08). */
    const val RESTAURANT_CACHE_READ = "cm.restaurant.cache.read"

    /** JSON decoding of that cached response (S4-08). */
    const val RESTAURANT_CACHE_DECODE = "cm.restaurant.cache.decode"

    /** Formatting of one UTC timestamp (S4-11). */
    const val OUTBOX_ISO_UTC = "cm.outbox.isoUtc"

    /** Whole admission of one event: payload, timestamp and Room transaction, without network (S4-11). */
    const val OUTBOX_ENQUEUE = "cm.outbox.enqueue"
}

private val nextCookie = AtomicInteger()

@PublishedApi
internal fun nextTraceCookie(): Int = nextCookie.incrementAndGet()

/**
 * Section of one thread. [block] is `crossinline`, so it cannot suspend: a synchronous section that
 * began on one thread and ended on another would corrupt the trace.
 */
internal inline fun <T> traceSection(name: String, crossinline block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

/**
 * Section that may suspend and resume on another thread: begin and end are matched by a cookie. Needs
 * API 29; on older devices [block] just runs.
 */
internal suspend inline fun <T> traceAsyncSection(name: String, block: () -> T): T {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return block()
    val cookie = nextTraceCookie()
    Trace.beginAsyncSection(name, cookie)
    try {
        return block()
    } finally {
        Trace.endAsyncSection(name, cookie)
    }
}
