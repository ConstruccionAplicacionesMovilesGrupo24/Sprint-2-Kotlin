package com.campusmeal.android.feature.recipes.domain

import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem
import com.campusmeal.android.feature.recipes.domain.model.Recipe
import com.campusmeal.android.feature.recipes.domain.model.RecipeIngredient
import com.campusmeal.android.feature.recipes.domain.model.RecipeUnit
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Deterministic **synthetic** BQ3 inputs for the S4-05 measurement and equivalence check
 * (`android/docs/SPRINT4_MICROOPT_JP.md`). Never shown as real data: it only replaces the evaluator's input in
 * the `profile` build when explicitly selected ([Bq3ProfilingDataset]), and in the JVM equivalence test.
 *
 * The same (counts, seed, today) always produce the same lists. Rows exercise what the evaluator must handle:
 * names with accents, case and extra spaces; duplicate names; aliases shared with other ingredients; unit
 * aliases, unknown units and every dimension; expired, zero, negative, non-finite and invalid-date rows;
 * optional ingredients; ties in the final ordering.
 */
object Bq3SyntheticDataset {

    data class Dataset(val recipes: List<Recipe>, val inventory: List<InventoryItem>)

    /** Representative size from the S4-05 plan. */
    fun representative(todayIsoDate: String) = generate(recipeCount = 30, inventoryCount = 50, todayIsoDate = todayIsoDate, seed = 3005L)

    /** Stress size from the S4-05 plan. */
    fun stress(todayIsoDate: String) = generate(recipeCount = 300, inventoryCount = 500, todayIsoDate = todayIsoDate, seed = 3500L)

    fun generate(recipeCount: Int, inventoryCount: Int, todayIsoDate: String, seed: Long): Dataset {
        val random = Random(seed)
        val names = VOCABULARY
        // A pantry the inventory mostly draws from, so a share of recipes can match and the ordering is exercised.
        val pantry = names.take((inventoryCount / 3).coerceIn(8, names.size))
        val recipes = (0 until recipeCount).map { index ->
            val ingredientCount = 2 + random.nextInt(6)
            val chosen = (0 until ingredientCount).map {
                if (random.nextInt(4) == 0) names[random.nextInt(names.size)] else pantry[random.nextInt(pantry.size)]
            }.distinct()
            Recipe(
                id = "synthetic-recipe-%04d".format(Locale.ROOT, index),
                // Repeated names on purpose: ordering must fall back to the id.
                name = "Synthetic recipe ${index % 40}",
                preparationMinutes = listOf(10, 15, 20, 25, 30, 45, 60, 90)[random.nextInt(8)],
                servings = 1 + random.nextInt(4),
                dietaryTags = DietaryPreference.entries.filter { random.nextInt(3) == 0 }.toSet(),
                ingredients = chosen.map { display ->
                    val unit = RecipeUnit.entries[random.nextInt(RecipeUnit.entries.size)]
                    RecipeIngredient(
                        name = display,
                        normalizedName = normalizeIngredientName(display),
                        aliases = buildList {
                            // Another vocabulary entry as alias: a row may match through either key, once.
                            if (random.nextInt(3) == 0) add(normalizeIngredientName(names[random.nextInt(names.size)]))
                            // The ingredient's own key repeated as alias must not double count.
                            if (random.nextInt(5) == 0) add(normalizeIngredientName(display))
                        },
                        quantity = when (unit) {
                            RecipeUnit.G, RecipeUnit.ML -> (50 + random.nextInt(450)).toDouble()
                            RecipeUnit.KG, RecipeUnit.L -> (1 + random.nextInt(20)) / 10.0
                            RecipeUnit.UNIT -> (1 + random.nextInt(6)).toDouble()
                        },
                        unit = unit,
                        optional = random.nextInt(4) == 0,
                    )
                },
                steps = listOf("Synthetic step"),
            )
        }
        val inventory = (0 until inventoryCount).map { index ->
            val base = if (random.nextInt(5) == 0) names[random.nextInt(names.size)] else pantry[random.nextInt(pantry.size)]
            val quantity = when (random.nextInt(20)) {
                0 -> 0.0
                1 -> -1.0
                2 -> Double.NaN
                else -> when (random.nextInt(3)) {
                    0 -> (1 + random.nextInt(1500)).toDouble()
                    1 -> (1 + random.nextInt(30)) / 10.0
                    else -> (1 + random.nextInt(12)).toDouble()
                }
            }
            val expiration = when (random.nextInt(25)) {
                0 -> "2026-02-30" // not a real date: unusable
                else -> addDays(todayIsoDate, random.nextInt(46) - 6) // some already expired
            }
            InventoryItem(
                id = "synthetic-item-%04d".format(Locale.ROOT, index),
                name = spelledLikeAUser(base, random),
                quantity = quantity,
                unit = INVENTORY_UNITS[random.nextInt(INVENTORY_UNITS.size)],
                expirationDate = expiration,
                remainingDays = 0,
            )
        }
        return Dataset(recipes, inventory)
    }

    private fun spelledLikeAUser(name: String, random: Random): String = when (random.nextInt(5)) {
        0 -> name.uppercase(Locale.ROOT)
        1 -> "  ${name.lowercase(Locale.ROOT)} "
        2 -> name.replace(" ", "   ")
        else -> name
    }

    private fun addDays(isoDate: String, days: Int): String {
        val utc = TimeZone.getTimeZone("UTC")
        val calendar = Calendar.getInstance(utc, Locale.ROOT).apply {
            clear()
            set(isoDate.substring(0, 4).toInt(), isoDate.substring(5, 7).toInt() - 1, isoDate.substring(8, 10).toInt())
            add(Calendar.DAY_OF_MONTH, days)
        }
        return "%04d-%02d-%02d".format(
            Locale.ROOT,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** Inventory units as users type them, including aliases and units that never convert. */
    private val INVENTORY_UNITS = listOf("g", "gr", "Grams", "kg", " kilos ", "ml", "L", "lt", "litre", "unit", "und", "Unidades", "cup", "loaf")

    private val BASES = listOf(
        "Arroz", "Frijol", "Lenteja", "Garbanzo", "Pasta", "Avena", "Quinua", "Maíz", "Papa", "Yuca",
        "Plátano", "Tomate", "Cebolla", "Ajo", "Zanahoria", "Pimentón", "Espinaca", "Brócoli", "Calabacín", "Berenjena",
        "Champiñón", "Aguacate", "Limón", "Naranja", "Manzana", "Banano", "Fresa", "Mango", "Piña", "Pera",
        "Leche", "Yogur", "Queso", "Mantequilla", "Huevo", "Pollo", "Res", "Cerdo", "Atún", "Salmón",
        "Tofu", "Pan", "Arepa", "Harina", "Azúcar", "Sal", "Aceite", "Vinagre", "Miel", "Café",
    )
    private val QUALIFIERS = listOf("", " integral", " fresco", " rojo", " criollo")

    /** 250 display names; most reduce to distinct keys, a few collide after normalization. */
    private val VOCABULARY: List<String> = BASES.flatMap { base -> QUALIFIERS.map { base + it } }
}

/**
 * `profile` build only: which synthetic dataset, if any, replaces the BQ3 input for a measurement run. Set from the
 * launch intent by `MainActivity`; null in debug and release builds, so real users are never affected.
 */
object Bq3ProfilingDataset {
    const val EXTRA = "cm.bq3.dataset"

    @Volatile
    private var selected: String? = null

    @Volatile
    private var cached: Pair<String, Bq3SyntheticDataset.Dataset>? = null

    fun select(name: String?) {
        selected = name?.takeIf { it == REPRESENTATIVE || it == STRESS }
        cached = null
    }

    /** The selected dataset for [todayIsoDate], generated once per day, or null to use the real inputs. */
    fun current(todayIsoDate: String): Bq3SyntheticDataset.Dataset? {
        val name = selected ?: return null
        val key = "$name:$todayIsoDate"
        cached?.let { (cachedKey, dataset) -> if (cachedKey == key) return dataset }
        val dataset = if (name == STRESS) Bq3SyntheticDataset.stress(todayIsoDate) else Bq3SyntheticDataset.representative(todayIsoDate)
        cached = key to dataset
        return dataset
    }

    const val REPRESENTATIVE = "representative"
    const val STRESS = "stress"
}
