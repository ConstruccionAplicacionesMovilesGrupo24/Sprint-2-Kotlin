package com.campusmeal.android.feature.recipes.domain

import com.campusmeal.android.feature.context.domain.DietaryPreference
import com.campusmeal.android.feature.context.domain.DietaryPreference.GLUTEN_FREE
import com.campusmeal.android.feature.context.domain.DietaryPreference.VEGAN
import com.campusmeal.android.feature.context.domain.DietaryPreference.VEGETARIAN
import com.campusmeal.android.feature.inventory.domain.model.InventoryItem
import com.campusmeal.android.feature.recipes.domain.model.Recipe
import com.campusmeal.android.feature.recipes.domain.model.RecipeIngredient
import com.campusmeal.android.feature.recipes.domain.model.RecipeUnit
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S4-05 equivalence check (`android/docs/SPRINT4_MICROOPT_JP.md`): the complete BQ3 output of the evaluator must
 * stay identical, bit for bit, before and after the ingredient-lookup optimization.
 *
 * The golden files were written by the **unoptimized** evaluator (baseline commit) with
 * `BQ3_WRITE_GOLDEN=1 ./gradlew :app:testDebugUnitTest --tests '*Bq3EquivalenceTest*'`. They are never rewritten
 * by the optimization commit.
 */
class Bq3EquivalenceTest {

    @Test
    fun edgeCasesMatchGolden() {
        val dump = FILTERS.joinToString("\n") { (diet, minutes) ->
            "## diet=${diet.sorted()} maxMinutes=$minutes\n" +
                dump(evaluateBq3(EDGE_RECIPES, EDGE_INVENTORY, diet, minutes, TODAY))
        } + "\n"
        compareWithGolden("bq3-golden-edge.txt", dump)
    }

    @Test
    fun syntheticDatasetsMatchGolden() {
        val datasets = listOf(
            "representative" to Bq3SyntheticDataset.representative(TODAY),
            "stress" to Bq3SyntheticDataset.stress(TODAY),
            "seed7-60x120" to Bq3SyntheticDataset.generate(60, 120, TODAY, seed = 7L),
            "seed11-10x500" to Bq3SyntheticDataset.generate(10, 500, TODAY, seed = 11L),
        )
        val lines = datasets.flatMap { (name, dataset) ->
            FILTERS.map { (diet, minutes) ->
                val result = evaluateBq3(dataset.recipes, dataset.inventory, diet, minutes, TODAY)
                val text = dump(result)
                "$name diet=${diet.sorted().joinToString("+").ifEmpty { "none" }} max=$minutes " +
                    "matches=${result.matchIds.size} lines=${text.lines().size} sha256=${sha256(text)}"
            }
        }
        compareWithGolden("bq3-golden-synthetic.txt", lines.joinToString("\n") + "\n")
    }

    private fun compareWithGolden(name: String, actual: String) {
        val file = File(GOLDEN_DIR, name)
        if (System.getenv("BQ3_WRITE_GOLDEN") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(actual)
        }
        assertEquals("BQ3 output changed for $name", file.readText(), actual)
    }

    private companion object {
        const val TODAY = "2026-11-25"
        val GOLDEN_DIR = File("src/test/resources/bq3")

        val FILTERS: List<Pair<Set<DietaryPreference>, Int>> = listOf(
            emptySet<DietaryPreference>() to 180,
            emptySet<DietaryPreference>() to 30,
            setOf(VEGETARIAN) to 60,
            setOf(VEGAN, GLUTEN_FREE) to 45,
            setOf(GLUTEN_FREE) to 15,
        )

        /** Every field of the result; doubles as exact bits so a different summation order is caught. */
        fun dump(result: Bq3Result): String = buildString {
            append("matchIds=").append(result.matchIds.joinToString(",")).append('\n')
            append("summary=").append(result.summary).append('\n')
            result.evaluations.forEach { (id, e) ->
                append("recipe ").append(id).append(" dietOk=").append(e.dietOk).append(" timeOk=").append(e.timeOk)
                    .append(" priority=").append(e.priorityDate).append(" matches=").append(e.matches).append('\n')
                e.ingredients.forEach { c ->
                    append("  ").append(c.ingredient.name).append(" | ").append(c.status)
                        .append(" | available=").append(c.availableBase).append(" (").append(java.lang.Double.doubleToLongBits(c.availableBase)).append(')')
                        .append(" | required=").append(c.requiredBase).append(" (").append(java.lang.Double.doubleToLongBits(c.requiredBase)).append(')')
                        .append(" | ").append(c.baseUnit)
                        .append(" | matched=").append(c.matchedItemNames.joinToString("␟"))
                        .append(" | earliest=").append(c.earliestExpiration).append('\n')
                }
            }
        }

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        fun ingredient(name: String, quantity: Double, unit: RecipeUnit, optional: Boolean = false, aliases: List<String> = emptyList()) =
            RecipeIngredient(name, normalizeIngredientName(name), aliases.map(::normalizeIngredientName), quantity, unit, optional)

        fun recipe(id: String, name: String, minutes: Int, tags: Set<DietaryPreference>, vararg ingredients: RecipeIngredient) =
            Recipe(id, name, minutes, 1, tags, ingredients.toList(), listOf("step"))

        fun item(id: String, name: String, quantity: Double, unit: String, expiration: String) =
            InventoryItem(id, name, quantity, unit, expiration, 0)

        /** Hand-written cases named in the S4-05 plan. */
        val EDGE_RECIPES = listOf(
            // Aliases: a row matching both the name and an alias counts once.
            recipe(
                "r01", "Arroz con pollo", 40, emptySet(),
                ingredient("Arroz", 200.0, RecipeUnit.G, aliases = listOf("arroz blanco", "Arroz")),
                ingredient("Pollo", 0.3, RecipeUnit.KG),
                ingredient("Cebolla", 1.0, RecipeUnit.UNIT, optional = true),
            ),
            // Mixed units in one dimension plus a non-comparable unit that must not subtract.
            recipe("r02", "Avena con leche", 10, setOf(VEGETARIAN, GLUTEN_FREE), ingredient("Leche", 1.0, RecipeUnit.L), ingredient("Avena", 80.0, RecipeUnit.G)),
            // Floating conversion: 0.15 kg against 150 g.
            recipe("r03", "Pan casero", 90, setOf(VEGETARIAN), ingredient("Harina", 150.0, RecipeUnit.G), ingredient("Huevo", 2.0, RecipeUnit.UNIT)),
            // Only a non-comparable unit; only expired rows; a missing ingredient.
            recipe("r04", "Tostada", 5, setOf(VEGAN), ingredient("Pan", 2.0, RecipeUnit.UNIT)),
            recipe("r05", "Ensalada", 15, setOf(VEGAN, GLUTEN_FREE), ingredient("Espinaca", 100.0, RecipeUnit.G), ingredient("Limón", 1.0, RecipeUnit.UNIT, optional = true)),
            recipe("r06", "Sopa de lentejas", 45, setOf(VEGAN), ingredient("Lenteja", 250.0, RecipeUnit.G), ingredient("Zanahoria", 2.0, RecipeUnit.UNIT)),
            // Insufficient: rows exist but add up to less.
            recipe("r07", "Huevos revueltos", 10, setOf(VEGETARIAN, GLUTEN_FREE), ingredient("Huevo", 6.0, RecipeUnit.UNIT)),
            // Ties: same priority date, minutes and name; ordered by id.
            recipe("r09", "Tie", 20, emptySet(), ingredient("Arroz", 100.0, RecipeUnit.G)),
            recipe("r08", "Tie", 20, emptySet(), ingredient("Arroz", 100.0, RecipeUnit.G)),
            // Shared alias: "Tomate chonto" rows count for "Tomate"; duplicates by spelling are separate rows.
            recipe("r10", "Hogao", 25, setOf(VEGAN, GLUTEN_FREE), ingredient("Tomate", 300.0, RecipeUnit.G, aliases = listOf("Tomate chonto")), ingredient("Cebolla", 1.0, RecipeUnit.UNIT)),
            // Volume against mass rows of the same name: not comparable.
            recipe("r11", "Batido", 5, setOf(VEGETARIAN), ingredient("Yogur", 200.0, RecipeUnit.ML), ingredient("Fresa", 100.0, RecipeUnit.G, optional = true)),
            // No ingredients at all.
            recipe("r12", "Agua", 5, setOf(VEGAN, VEGETARIAN, GLUTEN_FREE)),
        )

        val EDGE_INVENTORY = listOf(
            item("i01", "  ARROZ ", 150.0, "g", "2026-11-30"),
            item("i02", "arroz blanco", 0.1, "kg", "2026-11-27"),
            item("i03", "Pollo", 300.0, "gr", "2026-11-26"),
            item("i04", "Cebólla", 2.0, "und", "2026-12-10"),
            item("i05", "Leche", 500.0, "ml", "2026-11-28"),
            item("i06", "leche", 0.5, "L", "2026-11-29"),
            item("i07", "LECHE", 2.0, "cup", "2026-11-26"),
            item("i08", "Avena", 500.0, "g", "2027-01-15"),
            item("i09", "Harina", 0.15, "kg", "2026-12-20"),
            item("i10", "Huevo", 4.0, "unidades", "2026-12-01"),
            item("i11", "Pan", 1.0, "loaf", "2026-11-26"),
            item("i12", "Espinaca", 200.0, "g", "2026-11-20"), // expired
            item("i13", "Limón", 0.0, "unit", "2026-12-01"), // zero
            item("i14", "Zanahoria", Double.NaN, "unit", "2026-12-01"), // not finite
            item("i15", "Lenteja", 500.0, "g", "2026-02-30"), // invalid date
            item("i16", "Tomate", 100.0, "g", "2026-11-28"),
            item("i17", "Tomate  chonto", 0.25, "kg", "2026-11-27"),
            item("i18", "tomate", 50.0, "g", "2026-11-25"), // expires today: usable
            item("i19", "Yogur", 250.0, "g", "2026-11-30"),
            item("i20", "Huevo", -2.0, "unit", "2026-12-01"), // negative
            item("i21", "Fresa", 3.0, "lt", "2026-11-29"),
        )
    }
}
