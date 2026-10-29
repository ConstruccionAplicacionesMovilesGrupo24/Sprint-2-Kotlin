package com.campusmeal.android.feature.recipes.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.recipes.domain.model.PREPARATION_MINUTES_RANGE
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** Validated BQ3 filters. [maxPreparationMinutes] is always within [PREPARATION_MINUTES_RANGE]. */
data class RecipeFilters(
    val dietaryPreferences: Set<DietaryPreference>,
    val maxPreparationMinutes: Int,
) {
    init {
        require(maxPreparationMinutes in PREPARATION_MINUTES_RANGE)
    }

    companion object {
        const val DEFAULT_MAX_PREPARATION_MINUTES = 30
    }
}

/**
 * `recipes.lastFilters.{accountId}` (plan §5.3): the last filters each account used. Personal data:
 * deleted on logout and when another account is activated. No coordinates or context data beyond the
 * dietary preferences are stored.
 */
class RecipeFilterStore(
    private val preferences: DataStore<Preferences>,
    private val coordinator: AccountSessionCoordinator,
) : AccountScopedStore {

    /** Null when the account never saved filters, or the stored value is no longer valid. */
    suspend fun read(scope: AccountSessionScope): RecipeFilters? {
        if (!coordinator.isCurrent(scope)) return null
        val raw = preferences.data.first()[key(scope.accountId)] ?: return null
        val stored = try {
            ApiClientFactory.json.decodeFromString(StoredFilters.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        }
        if (stored.maxPreparationMinutes !in PREPARATION_MINUTES_RANGE) return null
        val diet = stored.dietaryPreferences.mapNotNull { name -> DietaryPreference.entries.firstOrNull { it.name == name } }.toSet()
        return RecipeFilters(diet, stored.maxPreparationMinutes)
    }

    /** Written only while [scope] is active, so a late write can never land in another session. */
    suspend fun write(scope: AccountSessionScope, filters: RecipeFilters) {
        val raw = ApiClientFactory.json.encodeToString(
            StoredFilters.serializer(),
            StoredFilters(filters.dietaryPreferences.map { it.name }.sorted(), filters.maxPreparationMinutes),
        )
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

    @Serializable
    private data class StoredFilters(val dietaryPreferences: List<String>, val maxPreparationMinutes: Int)

    private companion object {
        const val KEY_PREFIX = "recipes.lastFilters."

        fun key(accountId: String) = stringPreferencesKey("$KEY_PREFIX$accountId")
    }
}
