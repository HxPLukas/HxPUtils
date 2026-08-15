package de.hxp.hxpaddons.utils.skyblock.recipe

import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.network.hypixelapi.RequestUtils
import de.hxp.hxpaddons.utils.noControlCodes

/**
 * Answers "can I craft this, and if so with what" for the /hxp recipe command by combining Hypixel's public
 * item list (id/name/stackability resolution only) with [RecipeRepo] (actual recipe data - the official API
 * stopped exposing crafting recipes for regular items, confirmed live that /v2/resources/skyblock/items
 * currently returns a recipe for exactly one item, PRECURSOR_APPARATUS).
 */
object RecipeLookup {

    /** [unstackable] mirrors the API's own field - true means max stack size 1, false means the standard 64 (see [maxStackSize]). No Skyblock item has a custom stack size between those two as of writing. */
    data class ItemMeta(val name: String, val unstackable: Boolean)

    private var itemIndex: Map<String, ItemMeta>? = null // id -> metadata

    /** Id->metadata index, loaded once and reused (also by [CraftFlipScanner]). */
    suspend fun ensureIndex(): Map<String, ItemMeta>? {
        itemIndex?.let { return it }
        val reply = RequestUtils.getItems().getOrElse {
            HxPMod.logger.warn("RecipeLookup: failed to load the Skyblock item list", it)
            return null
        }
        return reply.items.associate { it.id to ItemMeta(it.name, it.unstackable) }.also { itemIndex = it }
    }

    fun displayName(index: Map<String, ItemMeta>, id: String): String = index[id]?.name?.takeIf { it.isNotBlank() } ?: id

    fun maxStackSize(index: Map<String, ItemMeta>, id: String): Int = if (index[id]?.unstackable == true) 1 else 64

    /** Resolves a user-typed item name or id to an internal Skyblock item id, or null if nothing matches. */
    private fun resolveId(index: Map<String, ItemMeta>, query: String): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        val idLike = trimmed.uppercase().replace(" ", "_")
        if (index.containsKey(idLike)) return idLike

        val cleanQuery = trimmed.noControlCodes.lowercase()
        index.entries.find { it.value.name.noControlCodes.lowercase() == cleanQuery }?.let { return it.key }
        return index.entries
            .filter { it.value.name.noControlCodes.lowercase().contains(cleanQuery) }
            .minByOrNull { it.value.name.length }
            ?.key
    }

    /** Looks up [query] and posts the result to chat. Does network/disk I/O - call from a coroutine. */
    suspend fun lookupAndPrint(query: String) {
        val index = ensureIndex()
        if (index == null) {
            modMessage("§cCouldn't load the Skyblock item list, try again later.")
            return
        }

        val id = resolveId(index, query)
        if (id == null) {
            modMessage("§cNo item found matching '§f$query§c'.")
            return
        }

        val name = displayName(index, id)
        val recipes = RecipeRepo.getRecipes(id)
        if (recipes.isEmpty()) {
            modMessage("§e$name §7is not craftable in a crafting table.")
            return
        }

        recipes.forEachIndexed { i, recipe ->
            val yieldSuffix = if (recipe.outputCount > 1) " §7(makes §f${recipe.outputCount}x§7)" else ""
            val header = if (recipes.size > 1) "§a$name §7- recipe ${i + 1}/${recipes.size}, ${recipe.type}$yieldSuffix§7:"
            else "§a$name §7can be crafted (${recipe.type})$yieldSuffix§7:"
            modMessage(header)

            recipe.ingredients
                .groupingBy { it.id }
                .fold(0) { acc, ingredient -> acc + ingredient.count }
                .forEach { (ingredientId, count) ->
                    modMessage("§8 • §f${count}x ${displayName(index, ingredientId)}")
                }
        }
    }
}
