package com.campusmeal.android.feature.recipes.data.local

import androidx.room.withTransaction
import com.campusmeal.android.core.database.CampusMealDatabase
import com.campusmeal.android.core.network.ApiClientFactory
import com.campusmeal.android.feature.recipes.data.mapper.CatalogValidation
import com.campusmeal.android.feature.recipes.data.mapper.validate
import com.campusmeal.android.feature.recipes.data.remote.RecipeCatalogResponseDto
import com.campusmeal.android.feature.recipes.data.remote.RecipeDto
import com.campusmeal.android.feature.recipes.domain.model.RecipeCatalogSnapshot
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException

/**
 * Room storage of the single active catalog version. Global, non-personal data: it is not an
 * `AccountScopedStore` and survives logout (plan §7.4); only an identified session reaches the screens.
 */
class RoomRecipeCatalogStore(private val database: CampusMealDatabase) {

    private val dao = database.recipeCatalogDao()

    /** The stored catalog now and after every replacement; null while none was ever fetched. */
    fun observe(): Flow<RecipeCatalogSnapshot?> =
        database.invalidationTracker.createFlow(METADATA_TABLE, RECIPES_TABLE)
            .map { read() }
            .distinctUntilChanged()
            // Decoding the stored JSON is CPU work (plan §6.1).
            .flowOn(Dispatchers.Default)

    suspend fun metadata(): RecipeCatalogMetadataEntity? = dao.getMetadata()

    /**
     * Rebuilds the catalog from its rows and re-validates it. Rows that no longer form a valid catalog
     * (which only a corrupted database could produce) read as "no catalog" rather than a partial one.
     */
    private suspend fun read(): RecipeCatalogSnapshot? =
        database.withTransaction {
            val metadata = dao.getMetadata() ?: return@withTransaction null
            val recipes = try {
                dao.getRecipes().map { ApiClientFactory.json.decodeFromString(RecipeDto.serializer(), it.recipeJson) }
            } catch (_: SerializationException) {
                return@withTransaction null
            }
            val response = RecipeCatalogResponseDto(metadata.contractVersion, metadata.catalogVersion, metadata.generatedAt, recipes)
            when (val validation = response.validate()) {
                is CatalogValidation.Valid -> RecipeCatalogSnapshot(validation.catalog, metadata.fetchedAtEpochMillis)
                is CatalogValidation.Invalid -> null
            }
        }

    /** Replaces recipes and metadata in one transaction; recipes absent from [response] disappear with it. */
    suspend fun replace(response: RecipeCatalogResponseDto, contentHash: String, fetchedAtEpochMillis: Long) {
        val rows = response.recipes.mapIndexed { index, recipe ->
            RecipeCatalogRecipeEntity(
                recipeId = recipe.id,
                catalogVersion = response.catalogVersion,
                position = index,
                recipeJson = ApiClientFactory.json.encodeToString(RecipeDto.serializer(), recipe),
            )
        }
        dao.replace(
            RecipeCatalogMetadataEntity(
                contractVersion = response.contractVersion,
                catalogVersion = response.catalogVersion,
                generatedAt = response.generatedAt,
                contentHash = contentHash,
                fetchedAtEpochMillis = fetchedAtEpochMillis,
            ),
            rows,
        )
    }

    /** The same version and content arrived again: only the local fetch time moves. */
    suspend fun renewFetchedAt(fetchedAtEpochMillis: Long) = dao.renewFetchedAt(fetchedAtEpochMillis)

    companion object {
        private const val METADATA_TABLE = "recipe_catalog_metadata"
        private const val RECIPES_TABLE = "recipe_catalog_recipes"

        /** SHA-256 of the canonical JSON of a validated response (it includes `generatedAt`). */
        fun contentHash(response: RecipeCatalogResponseDto): String =
            MessageDigest.getInstance("SHA-256")
                .digest(ApiClientFactory.json.encodeToString(RecipeCatalogResponseDto.serializer(), response).toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
