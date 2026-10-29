package com.campusmeal.android.feature.context.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.core.session.AccountScopedStore
import com.campusmeal.android.core.session.AccountSessionCoordinator
import com.campusmeal.android.core.session.AccountSessionScope
import com.campusmeal.android.feature.context.ContextForm
import com.campusmeal.android.feature.context.data.remote.RestaurantSearchRequestDto
import com.campusmeal.android.feature.context.data.remote.toRestaurantSearchRequestDto
import com.campusmeal.android.feature.context.domain.Campus
import com.campusmeal.android.feature.context.domain.CampusCatalog
import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.context.domain.MealContext
import com.campusmeal.android.feature.context.domain.MealLocation
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * The last context an account validated, restored after the process ends.
 *
 * [manualCampus] is set only when the location was a manually chosen campus centre. A device fix is
 * never kept: after a restart that context needs a new location, so only the form is restored.
 */
data class SavedContext(
    val form: ContextForm,
    val manualCampus: Campus?,
) {
    /** The full search context, rebuilt at [requestedAtEpochMillis]; null when the location must be asked again. */
    fun toRequest(requestedAtEpochMillis: Long = System.currentTimeMillis()): RestaurantSearchRequestDto? {
        val campus = manualCampus ?: return null
        val budget = form.maximumBudget.toLongOrNull() ?: return null
        return MealContext(
            location = MealLocation.forCampus(campus),
            availableMinutes = form.availableMinutes,
            maximumBudget = budget,
            dietaryPreferences = form.dietaryPreferences,
            includeDelivery = form.includeDelivery,
        ).toRestaurantSearchRequestDto(requestedAtEpochMillis)
    }
}

/**
 * `context.last.{accountId}`: the meal context of each account (time, budget, diet, delivery and,
 * for a manual campus, its id). **No coordinates are ever stored**: the campus centre is rebuilt from
 * [CampusCatalog]. Personal data: deleted on logout and when another account is activated.
 */
class ContextPreferencesStore(
    private val preferences: DataStore<Preferences>,
    private val coordinator: AccountSessionCoordinator,
) : AccountScopedStore {

    /** Null when the account never saved a context, or the stored value is no longer valid. */
    suspend fun read(scope: AccountSessionScope): SavedContext? {
        if (!coordinator.isCurrent(scope)) return null
        val raw = preferences.data.first()[key(scope.accountId)] ?: return null
        val stored = try {
            ApiClientFactory.json.decodeFromString(StoredContext.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        }
        if (stored.availableMinutes <= 0 || stored.maximumBudget < 0) return null
        val diet = stored.dietaryPreferences.mapNotNull { name -> DietaryPreference.entries.firstOrNull { it.name == name } }.toSet()
        return SavedContext(
            form = ContextForm(
                availableMinutes = stored.availableMinutes,
                maximumBudget = stored.maximumBudget.toString(),
                dietaryPreferences = diet,
                includeDelivery = stored.includeDelivery,
            ),
            manualCampus = stored.manualCampusId?.let { id -> CampusCatalog.campuses.firstOrNull { it.id == id } },
        )
    }

    /** Saves [request] for [scope] only while it is active, so a late write never lands in another session. */
    suspend fun write(scope: AccountSessionScope, request: RestaurantSearchRequestDto) {
        val stored = StoredContext(
            manualCampusId = manualCampusIdOf(request),
            availableMinutes = request.availableMinutes,
            maximumBudget = request.maximumBudget,
            dietaryPreferences = request.dietaryPreferences.sorted(),
            includeDelivery = request.includeDelivery,
        )
        val raw = ApiClientFactory.json.encodeToString(StoredContext.serializer(), stored)
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

    /**
     * The campus id only when the request used that campus centre exactly (manual selection, see
     * [MealLocation.forCampus]). A device fix, even one inside a campus zone, returns null.
     */
    private fun manualCampusIdOf(request: RestaurantSearchRequestDto): String? {
        val campus = CampusCatalog.campuses.firstOrNull { it.id == request.campusId } ?: return null
        val atCentre = request.location.latitude == campus.latitude && request.location.longitude == campus.longitude
        return campus.id.takeIf { atCentre }
    }

    @Serializable
    private data class StoredContext(
        val manualCampusId: String?,
        val availableMinutes: Int,
        val maximumBudget: Long,
        val dietaryPreferences: List<String>,
        val includeDelivery: Boolean,
    )

    private companion object {
        const val KEY_PREFIX = "context.last."

        fun key(accountId: String) = stringPreferencesKey("$KEY_PREFIX$accountId")
    }
}
