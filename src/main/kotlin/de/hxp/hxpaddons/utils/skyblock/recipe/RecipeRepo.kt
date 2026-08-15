package de.hxp.hxpaddons.utils.skyblock.recipe

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.utils.network.WebUtils
import java.io.File

/**
 * Fetches/caches raw crafting-table recipe data from the community-maintained NotEnoughUpdates-REPO
 * (github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) - confirmed live that Hypixel's own API stopped
 * exposing recipes for regular items (see [RecipeLookup]'s doc). Shared by [RecipeLookup] (the /hxp recipe
 * command) and [de.hxp.hxpaddons.utils.skyblock.recipe.CraftFlipScanner] (bulk bazaar craft-flip scan) so
 * both reuse the same on-disk cache instead of each hitting the repo independently.
 *
 * Recipe files come in two schema generations, both handled here: an older singular `"recipe": {A1..C3}`
 * object, and a newer `"recipes": [{type, A1..C3, count}]` array.
 */
object RecipeRepo {

    private const val REPO_RAW_URL = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/items/"
    private val cacheDir = File(HxPMod.configFile, "reporepo/items").apply { mkdirs() }
    private val gridSlots = listOf("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3")

    data class Ingredient(val id: String, val count: Int)
    data class Recipe(val type: String, val outputCount: Int, val ingredients: List<Ingredient>)

    suspend fun getRecipes(id: String): List<Recipe> = fetchItemJson(id)?.let(::parseRecipes) ?: emptyList()

    /** Reads (or fetches + caches) the repo's raw item json. Recipe data is effectively static once an item exists, so cache entries never expire. */
    private suspend fun fetchItemJson(id: String): JsonObject? {
        val cacheFile = File(cacheDir, "$id.json")
        if (cacheFile.exists()) {
            val cached = cacheFile.readText()
            if (cached.isBlank()) return null // cached "no recipe data" marker
            return runCatching { JsonParser.parseString(cached).asJsonObject }.getOrNull()
        }

        val text = WebUtils.fetchString(REPO_RAW_URL + "$id.json").getOrElse { error ->
            if (error is WebUtils.InputStreamException && error.code == 404) cacheFile.writeText("")
            return null
        }

        val parsed = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        cacheFile.writeText(text)
        return parsed
    }

    private fun parseSlots(obj: JsonObject, defaultCount: Int): Recipe? {
        val ingredients = gridSlots.mapNotNull { slot ->
            val raw = obj.get(slot)?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            if (raw.isBlank()) return@mapNotNull null
            val parts = raw.split(":", limit = 2)
            Ingredient(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 1)
        }
        if (ingredients.isEmpty()) return null

        val type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: "crafting"
        val count = obj.get("count")?.takeIf { it.isJsonPrimitive }?.asInt ?: defaultCount
        return Recipe(type, count, ingredients)
    }

    private fun parseRecipes(json: JsonObject): List<Recipe> {
        json.getAsJsonArray("recipes")?.let { array ->
            return array.mapNotNull { it.takeIf { entry -> entry.isJsonObject }?.asJsonObject?.let { obj -> parseSlots(obj, 1) } }
        }

        json.getAsJsonObject("recipe")?.let { obj -> parseSlots(obj, 1)?.let { return listOf(it) } }

        return emptyList()
    }
}
