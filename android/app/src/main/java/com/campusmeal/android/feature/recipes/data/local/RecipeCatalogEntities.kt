package com.campusmeal.android.feature.recipes.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * The single stored catalog version (global, non-personal data). The row exists after any successful
 * fetch, including an empty catalog, so "no catalog on this device" is distinguishable from "valid empty".
 *
 * [generatedAt] is the backend author's production time, kept exactly as received; [fetchedAtEpochMillis]
 * is the local instant of the last successful fetch and drives the 24-hour TTL. [contentHash] is the
 * SHA-256 of the validated response, used to detect a changed payload under an unchanged version.
 */
@Entity(tableName = "recipe_catalog_metadata")
data class RecipeCatalogMetadataEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val contractVersion: Int,
    val catalogVersion: String,
    val generatedAt: String,
    val contentHash: String,
    val fetchedAtEpochMillis: Long,
) {
    companion object {
        const val SINGLE_ROW_ID = 0
    }
}

/** One recipe of the stored version, as its validated contract JSON. [position] keeps the backend order. */
@Entity(tableName = "recipe_catalog_recipes")
data class RecipeCatalogRecipeEntity(
    @PrimaryKey val recipeId: String,
    val catalogVersion: String,
    val position: Int,
    val recipeJson: String,
)

@Dao
abstract class RecipeCatalogDao {

    @Query("SELECT * FROM recipe_catalog_metadata WHERE id = 0")
    abstract suspend fun getMetadata(): RecipeCatalogMetadataEntity?

    @Query("SELECT * FROM recipe_catalog_recipes ORDER BY position ASC")
    abstract suspend fun getRecipes(): List<RecipeCatalogRecipeEntity>

    @Upsert
    abstract suspend fun upsertMetadata(metadata: RecipeCatalogMetadataEntity)

    @Query("UPDATE recipe_catalog_metadata SET fetchedAtEpochMillis = :fetchedAtEpochMillis WHERE id = 0")
    abstract suspend fun renewFetchedAt(fetchedAtEpochMillis: Long)

    @Insert
    abstract suspend fun insertRecipes(recipes: List<RecipeCatalogRecipeEntity>)

    @Query("DELETE FROM recipe_catalog_recipes")
    abstract suspend fun deleteRecipes()

    /** Replaces every recipe and the metadata at once: never a mix of two versions. */
    @Transaction
    open suspend fun replace(metadata: RecipeCatalogMetadataEntity, recipes: List<RecipeCatalogRecipeEntity>) {
        deleteRecipes()
        insertRecipes(recipes)
        upsertMetadata(metadata)
    }
}
