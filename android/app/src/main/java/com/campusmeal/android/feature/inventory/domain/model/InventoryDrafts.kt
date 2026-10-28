package com.campusmeal.android.feature.inventory.domain.model

import com.campusmeal.android.core.common.BogotaCalendar

/** Fixed draft ids, so an account has at most one add draft and one draft per edited item. */
object InventoryDraftIds {
    const val CREATE = "create"

    fun edit(itemId: String) = "edit:$itemId"
}

/**
 * The last submission of a draft or consume. Local persistence and server confirmation are separate:
 * none of these states means "saved on the server"; a confirmed success deletes the draft instead.
 */
enum class SubmitOutcome {
    /** Edited locally, never submitted. */
    NONE,

    /** Refused offline, or failed before the request reached a connection. Safe to submit again. */
    NOT_SENT,

    /** Persisted before the request starts. If nobody is sending it any more, it was interrupted. */
    SUBMITTING,

    /** The server refused it (validation, not found, session). Nothing was applied. */
    REJECTED,

    /**
     * The server may have applied it. Another attempt requires a complete inventory sync that started
     * after this submission, and then an explicit user decision.
     */
    OUTCOME_UNKNOWN,
}

/** Raw text from the add/edit form, kept exactly as typed so incomplete input survives. */
data class DraftFields(
    val name: String,
    val quantityText: String,
    val unit: String,
    val expirationDateText: String,
) {
    /** Values the API accepts, or null while the form is incomplete or invalid. */
    fun toWriteOrNull(): InventoryItemWrite? {
        val name = name.trim()
        val unit = unit.trim()
        val date = expirationDateText.trim()
        val quantity = quantityText.trim().replace(',', '.').toDoubleOrNull()
        val valid = name.isNotEmpty() && name.length <= MAX_NAME_LENGTH &&
            unit.isNotEmpty() && unit.length <= MAX_UNIT_LENGTH &&
            quantity != null && quantity.isFinite() && quantity >= 0.0 &&
            BogotaCalendar.isIsoDate(date)
        return if (valid) InventoryItemWrite(name, checkNotNull(quantity), unit, date) else null
    }

    private companion object {
        // Backend limits (CreateInventoryItemDto).
        const val MAX_NAME_LENGTH = 150
        const val MAX_UNIT_LENGTH = 30
    }
}

/** Validated body of `POST inventory` / `PATCH inventory/{id}`. [expirationDate] is `YYYY-MM-DD`. */
data class InventoryItemWrite(
    val name: String,
    val quantity: Double,
    val unit: String,
    val expirationDate: String,
)

data class InventoryDraft(
    val draftId: String,
    /** Null for the add draft. */
    val targetItemId: String?,
    val fields: DraftFields,
    val updatedAtEpochMillis: Long,
    val lastSubmitOutcome: SubmitOutcome,
    val lastSubmitAtEpochMillis: Long?,
    val lastHttpCode: Int?,
    val lastErrorCode: String?,
)

/** A consume whose result is not confirmed yet (see `PendingConsumeEntity`). */
data class PendingConsume(
    val itemId: String,
    val itemName: String,
    val outcome: SubmitOutcome,
    val startedAtEpochMillis: Long,
)

/** What happened to one explicit Save or Consume. */
sealed interface MutationOutcome {

    /** The server confirmed it. [listRefreshed] is false when the follow-up sync failed: the list needs refresh. */
    data class Confirmed(val listRefreshed: Boolean) : MutationOutcome

    /** Not applied: refused offline ([offline]) or failed before reaching the server. The draft is kept. */
    data class NotSent(val offline: Boolean) : MutationOutcome

    /** The server refused it; nothing was applied. */
    data class Rejected(val httpCode: Int, val errorCode: String?) : MutationOutcome

    data object Unauthorized : MutationOutcome

    /** The server may have applied it. The marker is kept; reconcile before another attempt. */
    data object OutcomeUnknown : MutationOutcome

    /** A previous attempt's outcome is unknown and no complete sync has happened since. */
    data object NeedsReconciliation : MutationOutcome

    /** Consume only: the item was already gone (404 INVENTORY_ITEM_NOT_FOUND). */
    data object AlreadyConsumed : MutationOutcome

    /** The draft's fields are incomplete or invalid; nothing was sent. */
    data object InvalidInput : MutationOutcome

    /** The same draft or item is already being submitted. */
    data object AlreadyRunning : MutationOutcome

    /** The session ended before the request could start; nothing was sent. */
    data object SessionEnded : MutationOutcome
}
