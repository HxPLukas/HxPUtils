package de.hxp.hxpaddons.utils.skyblock.recipe

import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.utils.network.hypixelapi.BazaarApiData
import de.hxp.hxpaddons.utils.network.hypixelapi.RequestUtils
import de.hxp.hxpaddons.utils.readPurseBalance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.minecraft.world.item.ItemStack
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Scans every Bazaar product for "craft flips": acquire the recipe's ingredients via Buy Order, craft them
 * into the (Bazaar-tradeable) output, sell that via a Sell Offer. Only items whose recipe
 * bottoms out entirely in Bazaar-tradeable materials can be evaluated this way - most gear/dungeon items
 * drop out naturally here, which is expected, not a bug.
 *
 * Each ingredient is sourced recursively via [cheapestUnitSourcing]: if an ingredient is itself craftable
 * from further Bazaar-tradeable materials, and that works out cheaper per unit than just buying it
 * directly, the sub-craft is used instead (e.g. an item built from 10x some mid-tier item, where that
 * mid-tier item is itself built from 10x something cheaper - exactly the case this exists for). Recursion
 * is capped at [MAX_CRAFT_DEPTH] and cycle-guarded via a `visited` set, and memoized per item for the
 * whole scan (many different top-level items share common sub-ingredients like enchanted materials).
 *
 * Constraints: a single top-level craft may cost at most [MAX_PURSE_FRACTION] (90%) of the purse, and
 * `repeats` is capped so the full batch (all leaf ingredients bought + the crafted output held at once,
 * worst case before selling anything) fits the player's current free inventory space while still leaving
 * [INVENTORY_RESERVED_SLOTS] free - there is deliberately no minimum purse usage anymore, only the 90%
 * ceiling and the inventory-fit ceiling. Purse defaults to [de.hxp.hxpaddons.utils.readPurseBalance] (the live
 * sidebar reading already used for order sizing) and falls back to [DEFAULT_PURSE] (20m) when that can't be
 * read (not in-game).
 *
 * Also reports a realistic time estimate per candidate: filling every leaf ingredient's Buy Order (in
 * parallel, so the slowest one gates you) plus the output's Sell Order afterward, using the same
 * `sellMovingWeek`/`buyMovingWeek` -> hourly-rate approach and Bazaar sell tax
 * [BazaarFlipper.findBestFlips] ("Find Best Flip") already uses.
 */
object CraftFlipScanner {

    const val DEFAULT_PURSE = 20_000_000.0
    private const val MAX_PURSE_FRACTION = 0.90

    /** Repeats are capped so the batch fits the inventory while still leaving this many slots free (e.g. for
     * picking up drops mid-farm, or just not walking away with a completely full inventory). */
    private const val INVENTORY_RESERVED_SLOTS = 1

    /** Depth 3 = a top recipe's ingredient can be sub-crafted, and that sub-craft's own ingredients can be sub-crafted one more level - covers the requested "crafted from 10 items, which are themselves crafted from 10 items" case plus one level of headroom, without exploring so deep that a scan has to touch a large fraction of every item in the game (confirmed live: a much higher depth was the main reason scans got slow, not just the concurrency bug fixed alongside this). */
    private const val MAX_CRAFT_DEPTH = 3
    private const val CONCURRENCY = 32

    /** Mirrors [BazaarFlipper]'s own private `BAZAAR_TAX`/`HOURS_PER_WEEK` - kept as separate constants here rather than exposing those since this is the only other place that needs them. */
    private const val BAZAAR_TAX = 0.0125
    private const val HOURS_PER_WEEK = 168.0

    /** Main-inventory slot count (hotbar + main, excludes armor/offhand) - same scope [BazaarFlipper.freeInventoryCapacity] checks. */
    private const val INVENTORY_SLOTS = 36

    data class CraftFlip(
        val id: String,
        val name: String,
        val recipeType: String,
        val costPerCraft: Double,
        val profitPerCraft: Double,
        val itemsPerCraft: Int,
        val repeats: Int,
        val totalSpend: Double,
        val totalProfit: Double,
        val neededSlots: Int,
        val freeSlots: Int,
        val fitsInventory: Boolean,
        val buyPhaseHours: Double,
        val sellPhaseHours: Double,
        val totalHours: Double,
        val profitPerHour: Double,
        val usesSubCrafting: Boolean,
    )

    /** Cheapest known way to get one unit of an item: either buy it off the Bazaar directly, or craft it from its own cheapest-sourced ingredients. [leafPerUnit] is the fully-flattened bottom-of-the-tree Bazaar purchases needed for one unit - what you'd actually place Buy Orders for, regardless of how many craft steps happen above that. */
    private data class UnitSourcing(val unitCost: Double, val leafPerUnit: Map<String, Double>, val viaCraft: Boolean)

    @Volatile
    var scanning: Boolean = false
        private set

    /**
     * Runs the full scan. Does a lot of network I/O (first run especially - one request per Bazaar
     * product/ingredient without a cached recipe yet) - call from a coroutine, not the render/tick thread.
     * [onProgress] fires (done, total) after each top-level product finishes evaluating - purely for chat
     * feedback on a long scan, callers should throttle it themselves rather than messaging on every call.
     */
    suspend fun scan(
        purse: Double = readPurseBalance() ?: DEFAULT_PURSE,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<List<CraftFlip>> = coroutineScope {
        if (scanning) return@coroutineScope Result.failure(IllegalStateException("A craft-flip scan is already running."))
        scanning = true
        try {
            val index = RecipeLookup.ensureIndex() ?: return@coroutineScope Result.failure(Exception("Failed to load the Skyblock item list."))
            val bazaar = RequestUtils.getBazaar().getOrElse { return@coroutineScope Result.failure(it) }
            val products = bazaar.products
            val freeSlots = countFreeInventorySlots()
            val completed = java.util.concurrent.atomic.AtomicInteger(0)

            // Every recipe fetch - top-level product AND every recursive sub-ingredient - goes through this
            // one semaphore, so total concurrent outbound requests actually stays at CONCURRENCY. Confirmed
            // live this was missing on the recursive path in the previous pass: sourcingAsync's `async {}`
            // ran completely unbounded, so a scan could fire off many hundreds of simultaneous requests to
            // raw.githubusercontent.com at once - the likely reason scans got noticeably slower once
            // sub-crafting was added (either connection contention or the CDN throttling a burst that size).
            val semaphore = Semaphore(CONCURRENCY)

            // Memoized per item for the whole scan (ConcurrentHashMap.computeIfAbsent + Deferred coalesces
            // concurrent first-callers onto the same in-flight computation instead of racing/duplicating it).
            val sourcingMemo = ConcurrentHashMap<String, Deferred<UnitSourcing?>>()
            fun sourcingAsync(itemId: String, depth: Int, visited: Set<String>): Deferred<UnitSourcing?> {
                // itemId is an ancestor of this very call (a recipe cycle, e.g. A craftable from B and B
                // craftable from A) - the memo entry for itemId, if present, IS this in-flight computation,
                // so awaiting it here would deadlock the coroutine on itself. Break the cycle by sourcing
                // this occurrence directly off the Bazaar instead of recursing into the loop.
                if (itemId in visited) return CompletableDeferred(directSourcing(itemId, products))
                return sourcingMemo.computeIfAbsent(itemId) { async { computeSourcing(itemId, depth, visited, products, semaphore, ::sourcingAsync) } }
            }

            val total = products.size
            val flips = products.keys.map { id ->
                async {
                    try {
                        val outputProduct = products[id]?.takeIf { it.isTradeable() } ?: return@async null
                        val recipes = semaphore.withPermit {
                            runCatching { RecipeRepo.getRecipes(id) }.getOrElse {
                                HxPMod.logger.warn("CraftFlipScanner: failed to fetch the recipe for $id", it)
                                emptyList()
                            }
                        }

                        recipes.mapNotNull { recipe ->
                            evaluate(recipe, id, outputProduct, index, products, purse, freeSlots) { ingredientId ->
                                sourcingAsync(ingredientId, 1, setOf(id)).await()
                            }
                        }.maxByOrNull { it.profitPerHour }
                    } finally {
                        onProgress(completed.incrementAndGet(), total)
                    }
                }
            }.awaitAll().filterNotNull()

            Result.success(flips.sortedByDescending { it.profitPerHour })
        } finally {
            scanning = false
        }
    }

    private suspend fun computeSourcing(
        itemId: String,
        depth: Int,
        visited: Set<String>,
        products: Map<String, BazaarApiData.Product>,
        semaphore: Semaphore,
        sourcingAsync: (String, Int, Set<String>) -> Deferred<UnitSourcing?>,
    ): UnitSourcing? {
        val directPlan = directSourcing(itemId, products)

        val craftPlan = if (depth < MAX_CRAFT_DEPTH && itemId !in visited) {
            val recipes = semaphore.withPermit {
                runCatching { RecipeRepo.getRecipes(itemId) }.getOrElse {
                    HxPMod.logger.warn("CraftFlipScanner: failed to fetch the recipe for sub-ingredient $itemId", it)
                    emptyList()
                }
            }
            recipes.mapNotNull { recipe -> sourceViaRecipe(recipe, depth, visited + itemId, sourcingAsync) }.minByOrNull { it.unitCost }
        } else null

        return listOfNotNull(directPlan, craftPlan).minByOrNull { it.unitCost }
    }

    private suspend fun sourceViaRecipe(
        recipe: RecipeRepo.Recipe,
        depth: Int,
        visited: Set<String>,
        sourcingAsync: (String, Int, Set<String>) -> Deferred<UnitSourcing?>,
    ): UnitSourcing? {
        val aggregated = recipe.ingredients.groupingBy { it.id }.fold(0) { acc, ing -> acc + ing.count }
        var unitCost = 0.0
        val leafPerUnit = HashMap<String, Double>()
        for ((ingredientId, countPerExecution) in aggregated) {
            val sub = sourcingAsync(ingredientId, depth + 1, visited).await() ?: return null
            val perOutputUnit = countPerExecution.toDouble() / recipe.outputCount
            unitCost += sub.unitCost * perOutputUnit
            sub.leafPerUnit.forEach { (leaf, amount) -> leafPerUnit.merge(leaf, amount * perOutputUnit, Double::plus) }
        }
        return UnitSourcing(unitCost, leafPerUnit, true)
    }

    private fun directSourcing(itemId: String, products: Map<String, BazaarApiData.Product>): UnitSourcing? =
        products[itemId]?.takeIf { it.isTradeable() }?.quickStatus?.buyPrice?.let { UnitSourcing(it, mapOf(itemId to 1.0), false) }

    private fun BazaarApiData.Product.isTradeable(): Boolean {
        val qs = quickStatus ?: return false
        return qs.buyPrice > 0 && qs.sellPrice > 0
    }

    private fun countFreeInventorySlots(): Int {
        var empty = 0
        mc.player?.inventory?.forEachIndexed { i, stack: ItemStack -> if (i < INVENTORY_SLOTS && stack.isEmpty) empty++ }
        return empty
    }

    private fun slotsFor(amount: Int, maxStackSize: Int): Int = if (amount <= 0) 0 else (amount + maxStackSize - 1) / maxStackSize

    private suspend fun evaluate(
        recipe: RecipeRepo.Recipe,
        outputId: String,
        outputProduct: BazaarApiData.Product,
        index: Map<String, RecipeLookup.ItemMeta>,
        products: Map<String, BazaarApiData.Product>,
        purse: Double,
        freeSlots: Int,
        sourcingOf: suspend (String) -> UnitSourcing?,
    ): CraftFlip? {
        // Combine per-slot ingredients into per-item totals first - a recipe can reference the same
        // ingredient in several grid slots (e.g. Enchanted Diamond needs 5x "DIAMOND:32").
        val aggregated = recipe.ingredients.groupingBy { it.id }.fold(0) { acc, ing -> acc + ing.count }

        var costPerCraft = 0.0
        var usesSubCrafting = false
        val leafPerCraft = HashMap<String, Double>()
        for ((ingredientId, count) in aggregated) {
            val sourcing = sourcingOf(ingredientId) ?: return null // not Bazaar-tradeable and not craftable from anything that is
            costPerCraft += sourcing.unitCost * count
            if (sourcing.viaCraft) usesSubCrafting = true
            sourcing.leafPerUnit.forEach { (leaf, amount) -> leafPerCraft.merge(leaf, amount * count, Double::plus) }
        }
        if (costPerCraft <= 0 || costPerCraft > purse * MAX_PURSE_FRACTION) return null

        // quickStatus.buyPrice/sellPrice are confirmed live (see BazaarFlipper.findBestFlips' own doc) to mean
        // "what you'd pay buying"/"what you'd get selling" - i.e. Buy Order cost for ingredients (no tax on
        // the buy side) and Sell Offer proceeds for the crafted output (Bazaar's sell-side tax applies here),
        // paired with the sellMovingWeek/buyMovingWeek fill-time estimate below - not instant-buy/instant-sell,
        // which wouldn't need a fill-time model at all. Same formula shape as findBestFlips' own ask*(1-tax)-bid.
        val revenuePerCraft = outputProduct.quickStatus!!.sellPrice * (1 - BAZAAR_TAX) * recipe.outputCount
        val profitPerCraft = revenuePerCraft - costPerCraft
        if (profitPerCraft <= 0) return null

        // "Items per craft" is the flattened leaf-purchase total (what actually gets bought off the Bazaar
        // after any sub-crafting substitution), not the top recipe's face-value ingredient count.
        val itemsPerCraftExact = leafPerCraft.values.sum()
        if (itemsPerCraftExact <= 0) return null

        // Repeats are capped by whatever actually fits the inventory (leaf ingredients bought + crafted
        // output held at once, worst case before selling), leaving INVENTORY_RESERVED_SLOTS free - not a
        // flat item-count ceiling, since stack size varies per item (unstackables like sacks/wands take a
        // full slot each regardless of amount). neededSlotsFor is monotonically non-decreasing in repeats,
        // so the max repeats that still fits is found via binary search rather than a plain division.
        fun neededSlotsFor(r: Int): Int = leafPerCraft.entries.sumOf { (leafId, amountPerCraft) ->
            slotsFor(ceil(amountPerCraft * r).toInt(), RecipeLookup.maxStackSize(index, leafId))
        } + slotsFor(recipe.outputCount * r, RecipeLookup.maxStackSize(index, outputId))

        val maxByPurse = (purse * MAX_PURSE_FRACTION / costPerCraft).toInt()
        val maxSlots = freeSlots - INVENTORY_RESERVED_SLOTS
        if (maxByPurse < 1 || maxSlots < 0 || neededSlotsFor(1) > maxSlots) return null

        var lo = 1
        var hi = maxByPurse
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            if (neededSlotsFor(mid) <= maxSlots) lo = mid else hi = mid - 1
        }
        val repeats = lo

        val totalSpend = repeats * costPerCraft

        // Realistic fill time: every leaf ingredient's Buy Order can run in parallel, so the slowest one
        // gates the "buy phase"; the output's Sell Order only starts once everything's actually crafted.
        var buyPhaseHours = 0.0
        for ((leafId, amountPerCraft) in leafPerCraft) {
            val qs = products[leafId]?.quickStatus ?: return null
            val sellRatePerHour = qs.sellMovingWeek / HOURS_PER_WEEK
            if (sellRatePerHour <= 0) return null // no real sell-side activity to fill a Buy Order against - not a realistic flip
            buyPhaseHours = maxOf(buyPhaseHours, (amountPerCraft * repeats) / sellRatePerHour)
        }

        val buyRatePerHour = outputProduct.quickStatus!!.buyMovingWeek / HOURS_PER_WEEK
        if (buyRatePerHour <= 0) return null // no real buy-side activity to fill the Sell Order against
        val outputAmount = recipe.outputCount * repeats
        val sellPhaseHours = outputAmount / buyRatePerHour

        val totalHours = buyPhaseHours + sellPhaseHours
        val profitPerHour = (repeats * profitPerCraft) / totalHours

        val neededSlots = neededSlotsFor(repeats)

        return CraftFlip(
            id = outputId,
            name = RecipeLookup.displayName(index, outputId),
            recipeType = recipe.type,
            costPerCraft = costPerCraft,
            profitPerCraft = profitPerCraft,
            itemsPerCraft = ceil(itemsPerCraftExact).toInt(),
            repeats = repeats,
            totalSpend = totalSpend,
            totalProfit = repeats * profitPerCraft,
            neededSlots = neededSlots,
            freeSlots = freeSlots,
            fitsInventory = neededSlots <= freeSlots,
            buyPhaseHours = buyPhaseHours,
            sellPhaseHours = sellPhaseHours,
            totalHours = totalHours,
            profitPerHour = profitPerHour,
            usesSubCrafting = usesSubCrafting,
        )
    }
}
