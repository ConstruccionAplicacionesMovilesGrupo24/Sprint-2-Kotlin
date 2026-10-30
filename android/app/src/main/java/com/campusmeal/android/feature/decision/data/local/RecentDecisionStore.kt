package com.campusmeal.android.feature.decision.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.decision.domain.model.DecisionContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * The last current comparison of an account and the choice made on it. [contextKey] identifies the
 * context it was made for without coordinates; the response itself stays in `comparison_history`.
 */
@Serializable
data class RecentDecision(
    val recommendationId: String,
    val contextKey: String,
    val comparedAtEpochMillis: Long,
    val selectedAlternative: String? = null,
    val selectionEventId: String? = null,
    val selectionOccurredAtEpochMillis: Long? = null,
)

/**
 * `decision.recent.{accountId}`: lets Decide show the same recommendation and choice for a short
 * while after the process ends, instead of creating a new recommendation on every reopening.
 * Personal data: deleted on logout and when another account is activated.
 */
class RecentDecisionStore(
    private val preferences: DataStore<Preferences>,
    private val coordinator: AccountSessionCoordinator,
) : AccountScopedStore {

    suspend fun read(scope: AccountSessionScope): RecentDecision? {
        if (!coordinator.isCurrent(scope)) return null
        val raw = preferences.data.first()[key(scope.accountId)] ?: return null
        return try {
            ApiClientFactory.json.decodeFromString(RecentDecision.serializer(), raw)
        } catch (_: SerializationException) {
            null
        }
    }

    suspend fun write(scope: AccountSessionScope, decision: RecentDecision) {
        val raw = ApiClientFactory.json.encodeToString(RecentDecision.serializer(), decision)
        coordinator.runIfCurrent(scope) { preferences.edit { it[key(scope.accountId)] = raw } }
    }

    override suspend fun deleteAccount(accountId: String) {
        preferences.edit { it.remove(key(accountId)) }
    }

    override suspend fun deleteAllExcept(accountId: String) {
        val keep = key(accountId)
        preferences.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(KEY_PREFIX) && it != keep }.forEach { prefs.remove(it) }
        }
    }

    companion object {
        private const val KEY_PREFIX = "decision.recent."

        private fun key(accountId: String) = stringPreferencesKey("$KEY_PREFIX$accountId")

        /**
         * Campus, time, budget, diet and delivery: the same key whether the location was the campus
         * centre or a device fix on that campus (a restored context uses the centre). Coordinates are
         * never part of it. Null outside every campus, where the context is not restored either.
         */
        fun contextKey(context: DecisionContext): String? {
            val campusId = context.location.campusId ?: return null
            val diet = context.dietaryPreferences.map { it.name }.sorted().joinToString(",")
            return "$campusId|${context.availableMinutes}|${context.maximumBudget}|$diet|${context.includeDelivery}"
        }
    }
}
