package com.campusmeal.android.core.session

import android.content.Context
import androidx.work.WorkManager

enum class DeactivationReason {
    /** The user logged out: the account's local read data is deleted. */
    LOGOUT,

    /** The backend definitively rejected the credentials: local data stays, unreadable until the same account is identified again. */
    EXPIRED,

    /** Credentials were replaced, or `auth/me` returned another account. */
    ACCOUNT_CHANGED,
}

/**
 * Session lifecycle hooks. Every callback runs inside the [AccountSessionCoordinator] lock, so it must
 * do local work only (Room, DataStore, WorkManager cancellation) and never wait for the network.
 */
interface AccountLifecycleListener {

    /** [accountId] stopped being the active account. */
    suspend fun onAccountDeactivated(accountId: String, reason: DeactivationReason) = Unit

    /** [accountId] is about to become active; nothing has been published for it yet. */
    suspend fun onAccountActivating(accountId: String) = Unit

    /** [scope] is now the active, identified session: after login, an account change, or restoration at startup. */
    suspend fun onAccountActivated(scope: AccountSessionScope) = Unit
}

/**
 * A local data set whose records belong to one account. Implementations delete only their own rows or
 * keys: never a whole database, so unrelated data (such as the S3-09 outbox) is preserved.
 */
interface AccountScopedStore {

    /** Deletes [accountId]'s records. Runs on explicit logout. */
    suspend fun deleteAccount(accountId: String)

    /**
     * Deletes the records of every other account, including ownerless legacy records. Runs before
     * [accountId] becomes active, so a different account never finds the previous one's read caches.
     */
    suspend fun deleteAllExcept(accountId: String)
}

/** Deletes account read caches on logout and purges other accounts' caches when an account is activated. */
class AccountDataCleaner(private val stores: List<AccountScopedStore>) : AccountLifecycleListener {

    override suspend fun onAccountDeactivated(accountId: String, reason: DeactivationReason) {
        // Expiry keeps the data for the same account's reauthentication; it is unreadable meanwhile.
        if (reason == DeactivationReason.LOGOUT) stores.forEach { it.deleteAccount(accountId) }
    }

    override suspend fun onAccountActivating(accountId: String) {
        stores.forEach { it.deleteAllExcept(accountId) }
    }
}

/** WorkManager names shared with S3-09, so session code can cancel an account's work without knowing its workers. */
object AccountWork {
    fun outboxSyncName(accountId: String) = "event-outbox-sync:$accountId"
    fun tag(accountId: String) = "account:$accountId"
}

/**
 * Cancels an account's background work when it stops being active. No worker exists yet: event
 * delivery and scheduling after identification are S3-09, which adds them in [onAccountActivated].
 */
class AccountWorkCanceller(context: Context) : AccountLifecycleListener {

    private val appContext = context.applicationContext

    override suspend fun onAccountDeactivated(accountId: String, reason: DeactivationReason) {
        // Cancellation only; pending outbox rows stay owned by the account (plan §7.4).
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelUniqueWork(AccountWork.outboxSyncName(accountId))
        workManager.cancelAllWorkByTag(AccountWork.tag(accountId))
    }
}
