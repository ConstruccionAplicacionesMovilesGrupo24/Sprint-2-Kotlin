package com.campusmeal.android.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One identified session of one account. [accountId] is the stable `id` from `GET auth/me`;
 * [generation] changes on every login, logout, account change and invalidation, so a result captured
 * under an older scope can be recognized and discarded even when the account is the same.
 */
data class AccountSessionScope(val accountId: String, val generation: Long)

/**
 * The coordinator's view of the session. [accountId] is null while no account is identified: either
 * there are no credentials, or credentials exist but `auth/me` has not answered yet.
 */
data class SessionState(val generation: Long, val accountId: String?) {
    val scope: AccountSessionScope? get() = accountId?.let { AccountSessionScope(it, generation) }
}

/**
 * The single owner of the active account and session generation (Sprint 3 plan §7.3).
 *
 * Every session transition and every account-scoped local write goes through [mutex]:
 * - a write captured under scope S runs only while S is still active ([runIfCurrent]);
 * - transitions persist `session.generation` / `session.activeAccountId` in DataStore and notify the
 *   [AccountLifecycleListener]s, all inside the same critical section.
 *
 * No network call may run while [mutex] is held. Room and DataStore stay separate stores: a write
 * that touches both is serialized by the lock, not made atomic.
 */
class AccountSessionCoordinator(
    private val preferences: DataStore<Preferences>,
    /** Whether credentials are stored. Read once, while restoring, to reconcile the persisted scope. */
    private val hasCredentials: suspend () -> Boolean,
    /** Resolved lazily so listeners can depend on objects built after the coordinator. */
    private val listeners: () -> List<AccountLifecycleListener>,
) {

    private val mutex = Mutex()

    /** Null until [restore] has read DataStore. */
    private val state = MutableStateFlow<SessionState?>(null)

    /** The restored session state and every later transition. */
    val sessionState: Flow<SessionState> = flow {
        restore()
        emitAll(state.filterNotNull())
    }

    /** The active scope, without waiting for the lock. Null when nothing is identified or not yet restored. */
    fun currentScopeOrNull(): AccountSessionScope? = state.value?.scope

    /** True while [scope] is still the active one. Lock-free: use it before publishing UI state. */
    fun isCurrent(scope: AccountSessionScope): Boolean = state.value?.scope == scope

    suspend fun currentState(): SessionState = restore()

    /**
     * Runs [block] only while [expected] is still the active scope, under the coordinator lock.
     * Returns null, without running it, when the scope is no longer active. [block] must be local
     * work only (Room transaction, DataStore edit, reading credentials): never HTTP.
     */
    suspend fun <T> runIfCurrent(expected: AccountSessionScope, block: suspend () -> T): T? =
        locked { current -> if (current.scope == expected) block() else null }

    /** Like [runIfCurrent], for operations of a session that may not be identified yet. */
    suspend fun <T> runIfGeneration(expectedGeneration: Long, block: suspend () -> T): T? =
        locked { current -> if (current.generation == expectedGeneration) block() else null }

    /**
     * A login or registration that started at [expectedGeneration] succeeded. Unless a newer
     * transition happened meanwhile, this clears and persists the old identity **before** running
     * [saveCredentials], so an interrupted replacement can never pair the previous account's id with
     * the new tokens. Returns the new generation, or null when the attempt was superseded.
     */
    suspend fun replaceCredentials(expectedGeneration: Long, saveCredentials: suspend () -> Unit): Long? =
        locked { current ->
            if (current.generation != expectedGeneration) return@locked null
            val next = SessionState(current.generation + 1, accountId = null)
            persist(next)
            current.accountId?.let { notifyDeactivated(it, DeactivationReason.ACCOUNT_CHANGED) }
            saveCredentials()
            next.generation
        }

    /**
     * `auth/me` identified [accountId] for the session of [expectedGeneration]. [writeLocal] stores the
     * profile; it runs before the identity is persisted, so the account is published only after its
     * local data is in place. A different account than the active one is an account change: the old
     * scope is invalidated first. Returns the published scope, or null when the session changed meanwhile.
     */
    suspend fun publishIdentity(
        expectedGeneration: Long,
        accountId: String,
        writeLocal: suspend () -> Unit,
    ): AccountSessionScope? = locked { current ->
        if (current.generation != expectedGeneration) return@locked null
        if (current.accountId == accountId) {
            writeLocal()
            return@locked current.scope
        }
        var generation = current.generation
        if (current.accountId != null) {
            generation += 1
            persist(SessionState(generation, accountId = null))
            notifyDeactivated(current.accountId, DeactivationReason.ACCOUNT_CHANGED)
        }
        listeners().forEach { it.onAccountActivating(accountId) }
        writeLocal()
        val published = SessionState(generation, accountId)
        persist(published)
        val scope = checkNotNull(published.scope)
        listeners().forEach { it.onAccountActivated(scope) }
        scope
    }

    /**
     * Ends the session: runs [clearCredentials] (which captures and deletes the tokens), then persists
     * a new generation with no identity and notifies the listeners. With [expectedGeneration] it only
     * ends that session, so an old refresh failure can never end a newer one. Returns the result of
     * [clearCredentials], or null when the session was not the expected one.
     */
    suspend fun <T> endSession(
        expectedGeneration: Long?,
        reason: DeactivationReason,
        clearCredentials: suspend () -> T,
    ): T? = locked { current ->
        if (expectedGeneration != null && current.generation != expectedGeneration) return@locked null
        val result = clearCredentials()
        persist(SessionState(current.generation + 1, accountId = null))
        current.accountId?.let { notifyDeactivated(it, reason) }
        result
    }

    private suspend fun restore(): SessionState =
        state.value ?: mutex.withLock { state.value ?: restoreLocked() }

    /**
     * Reads the persisted scope. Credentials missing while an identity is persisted means an
     * interrupted logout or expiry: the identity is cleared, so account-owned data stays unreadable.
     * A restored identified session is announced through [AccountLifecycleListener.onAccountActivated],
     * like a login, so S3-09 can schedule that account's work from one hook.
     */
    private suspend fun restoreLocked(): SessionState {
        val stored = preferences.data.first()
        val restored = SessionState(
            generation = stored[GENERATION] ?: 0L,
            accountId = stored[ACTIVE_ACCOUNT_ID],
        )
        if (restored.accountId != null && !hasCredentials()) {
            persist(SessionState(restored.generation + 1, accountId = null))
        } else {
            state.value = restored
            restored.scope?.let { scope -> listeners().forEach { it.onAccountActivated(scope) } }
        }
        return checkNotNull(state.value)
    }

    private suspend inline fun <T> locked(block: (SessionState) -> T): T =
        mutex.withLock { block(state.value ?: restoreLocked()) }

    /** Persists first and publishes in memory only after DataStore accepted the write. */
    private suspend fun persist(next: SessionState) {
        preferences.edit { prefs ->
            prefs[GENERATION] = next.generation
            if (next.accountId == null) prefs.remove(ACTIVE_ACCOUNT_ID) else prefs[ACTIVE_ACCOUNT_ID] = next.accountId
        }
        state.value = next
    }

    private suspend fun notifyDeactivated(accountId: String, reason: DeactivationReason) {
        listeners().forEach { it.onAccountDeactivated(accountId, reason) }
    }

    private companion object {
        val ACTIVE_ACCOUNT_ID = stringPreferencesKey("session.activeAccountId")
        val GENERATION = longPreferencesKey("session.generation")
    }
}
