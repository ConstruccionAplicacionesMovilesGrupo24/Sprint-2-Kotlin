package com.campusmeal.android.core.telemetry

import com.campusmeal.android.core.outbox.DeviceInfo
import java.io.File
import java.io.FileOutputStream

/**
 * What a fatal crash leaves behind (S4-10): the session it ended and the class of the exception. No
 * message, stack trace, token or location. [accountId] is the account that was active, and the marker is
 * only ever imported for it.
 */
data class CrashMarker(
    val accountId: String,
    val sessionId: String,
    val sessionStartEventId: String,
    val sessionStartedAtEpochMillis: Long,
    val crashEventId: String,
    val crashedAtEpochMillis: Long,
    val device: DeviceInfo,
    val screen: String?,
    val exceptionClass: String,
    val injected: Boolean,
)

/**
 * Small text files in `noBackupFilesDir`, one per crashed session. Writing one runs inside the crashing
 * thread, so it uses plain `java.io` only: no Room, DataStore, coroutines, JSON or network. The content
 * is forced to disk and renamed at the end, so a half-written file is never read as a marker.
 */
class CrashMarkerStore(private val directory: File) {

    /** Called at startup, so the crashing handler never has to create it. */
    fun prepare() {
        directory.mkdirs()
        directory.listFiles { file -> file.name.endsWith(TEMPORARY) }?.forEach { it.delete() }
    }

    /** True when the marker is on disk. Keeps the oldest [MAX_MARKERS]: a crash loop cannot fill the storage. */
    fun write(marker: CrashMarker): Boolean {
        if ((directory.list()?.size ?: 0) >= MAX_MARKERS) return false
        val temporary = File(directory, marker.sessionId + TEMPORARY)
        FileOutputStream(temporary).use { output ->
            output.write(encode(marker).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        return temporary.renameTo(File(directory, marker.sessionId + EXTENSION))
    }

    /** The readable markers of [accountId]. An unreadable file can never be attributed: it is deleted. */
    fun read(accountId: String): List<CrashMarker> =
        directory.listFiles { file -> file.name.endsWith(EXTENSION) }.orEmpty().mapNotNull { file ->
            val marker = runCatching { if (file.length() <= MAX_BYTES) decode(file.readText()) else null }.getOrNull()
            if (marker == null) file.delete()
            marker?.takeIf { it.accountId == accountId }
        }.sortedBy { it.crashedAtEpochMillis }

    fun delete(marker: CrashMarker) {
        File(directory, marker.sessionId + EXTENSION).delete()
    }

    private fun encode(marker: CrashMarker): String =
        listOf(
            VERSION,
            marker.accountId,
            marker.sessionId,
            marker.sessionStartEventId,
            marker.sessionStartedAtEpochMillis.toString(),
            marker.crashEventId,
            marker.crashedAtEpochMillis.toString(),
            marker.device.deviceModel,
            marker.device.osVersion,
            marker.device.appVersion,
            marker.screen.orEmpty(),
            marker.exceptionClass,
            marker.injected.toString(),
        ).joinToString("\n") { it.replace('\n', ' ') }

    private fun decode(text: String): CrashMarker? {
        val fields = text.split('\n')
        if (fields.size != FIELDS || fields[0] != VERSION) return null
        return CrashMarker(
            accountId = fields[1],
            sessionId = fields[2],
            sessionStartEventId = fields[3],
            sessionStartedAtEpochMillis = fields[4].toLongOrNull() ?: return null,
            crashEventId = fields[5],
            crashedAtEpochMillis = fields[6].toLongOrNull() ?: return null,
            device = DeviceInfo(fields[7], fields[8], fields[9]),
            screen = fields[10].ifEmpty { null },
            exceptionClass = fields[11],
            injected = fields[12] == "true",
        )
    }

    private companion object {
        const val VERSION = "1"
        const val FIELDS = 13
        const val MAX_MARKERS = 5
        const val MAX_BYTES = 4_096L
        const val EXTENSION = ".crash"
        const val TEMPORARY = ".tmp"
    }
}
