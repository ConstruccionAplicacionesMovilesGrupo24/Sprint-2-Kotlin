package com.campusmeal.android.core.outbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The synchronization center keys its rows by `clientEventId`: no id may appear twice in one state. */
class OutboxActivityTest {

    @Test
    fun splitsByStatusAndRestoresEachOrder() {
        val rows = listOf(
            item("s-old", OutboxStatus.SENT, created = 1, closed = 10),
            item("p-old", OutboxStatus.PENDING, created = 2),
            item("s-new", OutboxStatus.SENT, created = 3, closed = 30),
            item("f", OutboxStatus.FAILED, created = 5),
            item("p-new", OutboxStatus.SENDING, created = 4),
            item("s-noclose", OutboxStatus.SENT, created = 20),
        )
        val activity = OutboxActivity.of(rows)
        assertEquals(listOf("f", "p-new", "p-old"), activity.open.map { it.clientEventId })
        assertEquals(listOf("s-new", "s-noclose", "s-old"), activity.sent.map { it.clientEventId })
    }

    @Test
    fun anEventIsNeverInBothGroups() {
        // A repeated id (it cannot come from one query, since each row has one status) still yields unique keys.
        val activity = OutboxActivity.of(
            listOf(item("e", OutboxStatus.PENDING, created = 1), item("e", OutboxStatus.SENT, created = 1, closed = 2)),
        )
        val ids = (activity.open + activity.sent).map { it.clientEventId }
        assertEquals(ids.distinct(), ids)
        assertTrue(ids.size == 1)
    }

    private fun item(id: String, status: String, created: Long, closed: Long? = null) =
        OutboxItem(id, OutboxKind.USAGE, status, 0, null, created, created, null, closed)
}
