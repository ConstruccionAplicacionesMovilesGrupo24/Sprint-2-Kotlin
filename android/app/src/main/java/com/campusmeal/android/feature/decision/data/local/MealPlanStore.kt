package com.campusmeal.android.feature.decision.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
data class PlanIngredient(val itemId: String, val name: String)

/**
 * The alternative the user chose, shown on Home while it is still lunch time: from [chosenAtEpochMillis]
 * for the context's [availableMinutes]. A Cook plan lists the expiring [ingredients] it uses, so Home
 * can ask afterwards whether they were cooked and mark them as consumed.
 */
@Serializable
data class MealPlan(
    val recommendationId: String,
    /** `COOK`, `WALK` or `ORDER`. */
    val alternative: String,
    val chosenAtEpochMillis: Long,
    val availableMinutes: Int,
    val restaurantId: String? = null,
    val restaurantName: String? = null,
    val ingredients: List<PlanIngredient> = emptyList(),
) {
    val endsAtEpochMillis: Long get() = chosenAtEpochMillis + availableMinutes * 60_000L
}

/**
 * `decision.plan.{accountId}`: one plan per account, replaced by the next choice and removed once it
 * is over (or answered, for Cook). Personal data: deleted on logout and when another account is activated.
 */
class MealPlanStore(
    private val preferences: DataStore<Preferences>,
    private val coordinator: AccountSessionCoordinator,
) : AccountScopedStore {

    fun observe(scope: AccountSessionScope): Flow<MealPlan?> {
        if (!coordinator.isCurrent(scope)) return flowOf(null)
        return preferences.data.map { prefs ->
            prefs[key(scope.accountId)]?.let { raw ->
                try {
                    ApiClientFactory.json.decodeFromString(MealPlan.serializer(), raw)
                } catch (_: SerializationException) {
                    null
                }
            }
        }
    }

    suspend fun write(scope: AccountSessionScope, plan: MealPlan) {
        val raw = ApiClientFactory.json.encodeToString(MealPlan.serializer(), plan)
        coordinator.runIfCurrent(scope) { preferences.edit { it[key(scope.accountId)] = raw } }
    }

    /** Removes the plan only if it is still [recommendationId], so a newer choice is never dropped. */
    suspend fun clear(scope: AccountSessionScope, recommendationId: String) {
        coordinator.runIfCurrent(scope) {
            preferences.edit { prefs ->
                val raw = prefs[key(scope.accountId)] ?: return@edit
                val stored = try {
                    ApiClientFactory.json.decodeFromString(MealPlan.serializer(), raw)
                } catch (_: SerializationException) {
                    null
                }
                if (stored == null || stored.recommendationId == recommendationId) prefs.remove(key(scope.accountId))
            }
        }
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

    private companion object {
        const val KEY_PREFIX = "decision.plan."

        fun key(accountId: String) = stringPreferencesKey("$KEY_PREFIX$accountId")
    }
}
