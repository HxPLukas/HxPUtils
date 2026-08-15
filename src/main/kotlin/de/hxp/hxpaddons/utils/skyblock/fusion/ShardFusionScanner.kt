package de.hxp.hxpaddons.utils.skyblock.fusion

import de.hxp.hxpaddons.features.impl.skyblock.BazaarFlipper
import de.hxp.hxpaddons.utils.network.hypixelapi.BazaarApiData
import de.hxp.hxpaddons.utils.network.hypixelapi.RequestUtils

/**
 * Scans every known Attribute Shard fusion recipe ([FusionRepo]) and scores it by expected coins/hour,
 * using the exact same model [BazaarFlipper.findBestFlips] ("Find Best Flip") uses for plain Bazaar flips -
 * just applied to a 2-in/1-out fusion instead of a single item's buy/sell spread:
 *
 * - Inputs are priced at `quick_status.sellPrice` (what a Buy Order for that shard fills at - "bid" in
 *   [BazaarFlipper]'s own terms) since [de.hxp.hxpaddons.features.impl.skyblock.Fuser] consumes shards you
 *   already hold, and the realistic way to keep holding enough of them is buying more via Buy Order rather
 *   than instant-buying.
 * - The fused output is priced at `quick_status.buyPrice * (1 - BAZAAR_TAX)` (a Sell Offer's fill price,
 *   minus Bazaar's sell tax) - "ask" in the same terms.
 * - Throughput is `min` across all three legs of (that leg's own `sellMovingWeek`/`buyMovingWeek` volume,
 *   converted to fuses/hour by dividing by how many units of that leg one fuse actually needs) - mirrors
 *   [BazaarFlipper.findBestFlips]'s own "a flip is only as fast as its slowest side" reasoning, just extended
 *   from 2 legs (one item's buy/sell) to 3 (two different input shards' buy-order fill rate, one output
 *   shard's sell-order fill rate).
 *
 * No recursive sub-sourcing here unlike [de.hxp.hxpaddons.utils.skyblock.recipe.CraftFlipScanner] - every
 * fusion recipe is a flat 2-shard-in/1-shard-out relationship, there is no "fuse the ingredients of the
 * ingredients" case to handle.
 *
 * Unlike [BazaarFlipper.findBestFlips], there is deliberately no ROI sanity cap here (2026-08-12, on request -
 * "der war nur für bz relevant" / that cap was only meant for plain Bazaar flips) - shard-fusion ROI above
 * 50% is expected and fine, not treated as a stale-price red flag.
 *
 * Instead (2026-08-12, same request), manipulation/stale-price protection happens directly against the order
 * book: the `quick_status` price actually used (`sellPrice` for an input, `buyPrice` for the output) is
 * compared against the volume-weighted average price across that side's *entire* visible `sell_summary`/
 * `buy_summary` depth ([isPriceSane]) - if they diverge by more than [MAX_PRICE_DEVIATION_PERCENT], that leg
 * is treated as manipulated/unreliable and the whole recipe is skipped, independent of its ROI. Verified live
 * this catches a real case: `SHARD_HIDEONWALL`'s `sellPrice` (131,488) sat 53% above its book-wide VWAP
 * (85,733) because two large, deep, far-off-price orders (1,024 and 1,000 units at ~3,000 each, vs. everything
 * else clustered 125,001-131,875) drag the true depth-weighted price down - `quick_status` itself doesn't
 * reflect that at all.
 *
 * 2026-08-13: [resolveInputLeg]/[resolveOutputLeg] prefer [LiveShardPrices] (populated by `FuseCheck`'s live
 * Bazaar-GUI scan) over the API's own `quick_status` price for any shard a scan covered, on request - the GUI
 * read is fresher than the API's own cache. Only the two price fields are ever substituted; moving-week
 * volume has no GUI equivalent and always comes from the API.
 *
 * 2026-08-13: [FusionRepo]'s recipe list turns out to be a genuinely exhaustive enumeration of the game's
 * "any shard of a matching category+rarity is a valid filler input" ID-Fusion rule (up to ~11,150 alternate
 * second-input pairs observed for a single output shard, 257k+ recipe rows total from ~320 underlying
 * shards) - so [scanBestPerOutput] already surfaces the cheapest valid substitute for any given output purely
 * by ranking that pool, no separate substitution algorithm needed. What DID need fixing at this scale: the
 * same handful of shards each recur across thousands of rows, so [scanAll] now resolves each shard's
 * price/rate/[isPriceSane] once per shard code ([resolveInputLeg]/[resolveOutputLeg], cached via [memoized])
 * instead of redoing that per recipe row - was up to ~770k redundant order-book VWAP scans per call before.
 */
object ShardFusionScanner {

    /** Mirrors [BazaarFlipper]'s own private `BAZAAR_TAX`/`HOURS_PER_WEEK` - kept as separate constants here for the same reason [de.hxp.hxpaddons.utils.skyblock.recipe.CraftFlipScanner] does (only other place that needs them). */
    private const val BAZAAR_TAX = 0.0125
    private const val HOURS_PER_WEEK = 168.0

    /** See the class doc's manipulation-guard paragraph. 20%, tightened from 30% (2026-08-13, on request - the looser 30% still let e.g. Hideonring-based fusions show ~120% ROI where a legitimately-priced one should be closer to ~50%). */
    private const val MAX_PRICE_DEVIATION_PERCENT = 20.0

    data class ShardFusion(
        val outputName: String,
        val outputRarity: String,
        val input1Name: String,
        val input1Qty: Int,
        val input1Price: Double,
        val input2Name: String,
        val input2Qty: Int,
        val input2Price: Double,
        val outputQty: Int,
        val outputPrice: Double,
        val costPerFuse: Double,
        val profitPerFuse: Double,
        val fusesPerHour: Double,
        val profitPerHour: Double,
        val roiPercent: Double,
        /** "live" if [LiveShardPrices] actually had a fresh price for that leg, "api" if it fell back to `quick_status` - lets a caller show *why* a cost looks off instead of guessing (2026-08-13, debugging a reported cost mismatch). */
        val input1Source: String,
        val input2Source: String,
        val outputSource: String,
        /** Raw units/hour a Buy Order for this input could realistically fill at (`sellMovingWeek / 168`, NOT divided by [input1Qty]/[input2Qty] the way [fusesPerHour]'s own per-leg rates are) - lets a caller size an actual order amount against a time budget, e.g. [[project_shard_fusion_scanner]]'s 2026-08-13 "/hxp fuse best budget" budget+fill-time sizing. */
        val input1RatePerHour: Double,
        val input2RatePerHour: Double,
        /** Same idea as [input1RatePerHour]/[input2RatePerHour] but for the OUTPUT side (`buyMovingWeek / 168`, raw units/hour a Sell Offer could realistically fill at, NOT divided by [outputQty]) - added 2026-08-13 so a caller can show each of the three legs' own individual market throughput instead of only the combined [fusesPerHour]. */
        val outputRatePerHour: Double,
    )

    @Volatile
    var scanning: Boolean = false
        private set

    /**
     * Runs the full scan. Does network I/O (first call fetches+caches [FusionRepo]'s data, every call fetches
     * live Bazaar prices) - call from a coroutine. No budget/cost ceiling on purpose (per explicit user
     * request) - a fusion is ranked purely on coins/hour, however expensive a single fuse is.
     */
    suspend fun scan(limit: Int = 10): Result<List<ShardFusion>> {
        return scanAll().map { it.sortedByDescending { f -> f.profitPerHour }.take(limit) }
    }

    /**
     * Same underlying scan as [scan], but collapses multiple recipes that produce the same output shard down
     * to just each output's single best (highest coins/hour) recipe before ranking - [scan] on its own can
     * fill its whole top-N with N different input pairs that all happen to produce the same output (e.g.
     * "2x Coralot + 5x X -> 2x Newt" for many different cheap X's), which isn't useful when the point is to
     * see a variety of *targets* worth fusing (2026-08-13, on request - "top 5 mit unique shards ... heißt nur
     * einmal newt und nicht 8 mal").
     */
    suspend fun scanBestPerOutput(limit: Int = 5): Result<List<ShardFusion>> {
        return scanAll().map { all ->
            all.groupBy { it.outputName }
                .map { (_, fusions) -> fusions.maxBy { it.profitPerHour } }
                .sortedByDescending { it.profitPerHour }
                .take(limit)
        }
    }

    /**
     * Same idea as [scanBestPerOutput] (unique output shards) plus a second constraint: no input shard may
     * appear in more than one of the returned fusions either - on request (2026-08-13), so the whole result
     * set can be run in parallel (buy-ordering/fusing all of them at once) without two different fusions
     * competing for the same shard's limited Buy Order liquidity/budget ("er darf nur 1 shard mit ember z.b.
     * craften ... um mehr gleichzeitig machen zu koennen").
     *
     * Greedy, not globally optimal: walks every viable fusion sorted by profit/hour and takes the first one
     * for each not-yet-used output whose two inputs are ALSO both not yet used by an earlier (higher-profit)
     * pick. A fusion can therefore lose its spot to a shard-cheaper alternative for the same output if its
     * ideal recipe's inputs were already claimed by a more profitable, unrelated fusion picked first - this
     * favours overall parallel throughput over any single fusion being individually optimal.
     */
    suspend fun scanUniqueInputsAndOutputs(limit: Int = 3): Result<List<ShardFusion>> {
        return scanAll().map { all ->
            val usedOutputs = HashSet<String>()
            val usedInputs = HashSet<String>()
            val picked = ArrayList<ShardFusion>()
            for (f in all.sortedByDescending { it.profitPerHour }) {
                if (picked.size >= limit) break
                if (f.outputName in usedOutputs) continue
                if (f.input1Name in usedInputs || f.input2Name in usedInputs) continue
                picked.add(f)
                usedOutputs.add(f.outputName)
                usedInputs.add(f.input1Name)
                usedInputs.add(f.input2Name)
            }
            picked
        }
    }

    /**
     * Same viable-fusion pool as [scan], filtered to only fusions that use [shardName] as one of the two
     * inputs, ranked by coins/hour - "top 10 shards I can fuse [shardName] into" (2026-08-13, on request).
     * Matches via [normalizeShardName] against both [ShardFusion.input1Name]/`input2Name`, so it doesn't
     * matter whether [shardName] is typed with or without a trailing "Shard".
     *
     * 2026-08-13 follow-up: [shardName]'s own `isPriceSane` check is skipped (passed as [scanAll]'s
     * `ignoreSanityCheckFor`) - the player is asking about a shard they already intend to use regardless of
     * its own book shape ("da es mir dann ja egal ist"), so the guard shouldn't block results just because
     * *that* leg looks off. The OTHER input and the output are still checked normally - the guard still
     * protects against recommending a fusion whose numbers only look good due to a different leg's skewed
     * price.
     */
    suspend fun scanBestTargetsFor(shardName: String, limit: Int = 10): Result<List<ShardFusion>> {
        val normalized = normalizeShardName(shardName)
        return scanAll(ignoreSanityCheckFor = shardName).map { all ->
            all.filter { normalizeShardName(it.input1Name) == normalized || normalizeShardName(it.input2Name) == normalized }
                .sortedByDescending { it.profitPerHour }
                .take(limit)
        }
    }

    /**
     * Same viable-fusion pool as [scan], filtered to only fusions whose OUTPUT is [outputName], ranked by
     * coins/hour - the reverse question of [scanBestTargetsFor] ("what can I fuse shard X into?" vs. this:
     * "what are the best ways to fuse INTO shard X?"). Added 2026-08-13 on request, so a player who already
     * knows an item is worth having (e.g. it came up as a `BazaarFlipper` "Find Best Flip" candidate) can check
     * whether producing it via fusion - buy-ordering two input shards instead of instant-buying/Buy-Ordering
     * the item itself - beats the alternative. Matches via [normalizeShardName] against [ShardFusion.outputName],
     * so it doesn't matter whether [outputName] is typed with or without a trailing "Shard".
     */
    suspend fun scanBestRecipesFor(outputName: String, limit: Int = 10): Result<List<ShardFusion>> {
        val normalized = normalizeShardName(outputName)
        return scanAll().map { all ->
            all.filter { normalizeShardName(it.outputName) == normalized }
                .sortedByDescending { it.profitPerHour }
                .take(limit)
        }
    }

    /** One [ShardFusion] sized against a caller's budget and [maxFillHours] cap - see [scanBestByBudgetProfit]. */
    data class SizedFusion(
        val fusion: ShardFusion,
        val sets: Long,
        val amount1: Long,
        val amount2: Long,
        val spend: Double,
        val expectedProfit: Double,
        /** "budget" or "fill-time" - which of the two caps actually limited [sets]. */
        val boundBy: String,
    )

    /**
     * Same viable-fusion pool as [scan], but ranked by the actual total profit achievable within a real
     * budget + fill-time constraint rather than by ROI% or raw coins/hour (2026-08-13, on request - "nicht
     * der beste return on invest ist sondern der beste profit für das geld in 7 std order"). ROI% alone is
     * misleading once you're capital/liquidity-constrained: a high-ROI shard with tiny order-book depth might
     * only support 1 fuse-set in [maxFillHours] hours, while a lower-ROI but far more liquid shard nets more
     * total coins for the same budget - so this sizes every candidate FIRST, then ranks by the resulting
     * [SizedFusion.expectedProfit], and drops anything that can't even fit one full fuse-set.
     *
     * Sizing per candidate: `setsByBudget = floor(budget / costPerFuse)`, `setsByTime = floor(min(input1RatePerHour
     * * maxFillHours / input1Qty, input2RatePerHour * maxFillHours / input2Qty))` (fill-time cap uses each
     * input's own raw hourly trade rate, NOT [ShardFusion.fusesPerHour] which is already min'd across all 3
     * legs and would double-apply the output-side throughput limit) - actual `sets = min(setsByBudget,
     * setsByTime)`.
     */
    suspend fun scanBestByBudgetProfit(budget: Double, maxFillHours: Double = 7.0, limit: Int = 5): Result<List<SizedFusion>> {
        return scanAll().map { all ->
            all.mapNotNull { f ->
                val setsByBudget = kotlin.math.floor(budget / f.costPerFuse)
                val maxUnits1 = f.input1RatePerHour * maxFillHours
                val maxUnits2 = f.input2RatePerHour * maxFillHours
                val setsByTime = kotlin.math.floor(minOf(maxUnits1 / f.input1Qty, maxUnits2 / f.input2Qty))
                val sets = minOf(setsByBudget, setsByTime)
                if (sets < 1.0) return@mapNotNull null
                SizedFusion(
                    fusion = f,
                    sets = sets.toLong(),
                    amount1 = (sets * f.input1Qty).toLong(),
                    amount2 = (sets * f.input2Qty).toLong(),
                    spend = sets * f.costPerFuse,
                    expectedProfit = sets * f.profitPerFuse,
                    boundBy = if (setsByBudget <= setsByTime) "budget" else "fill-time",
                )
            }.sortedByDescending { it.expectedProfit }.take(limit)
        }
    }

    /**
     * Shared scan body for [scan]/[scanBestPerOutput]/[scanUniqueInputsAndOutputs]/[scanBestByBudgetProfit]/
     * [scanBestTargetsFor] - every viable fusion, unsorted/untruncated.
     *
     * [ignoreSanityCheckFor]: normally null (every leg's `isPriceSane` check applies as usual). When set
     * (only [scanBestTargetsFor] does this, 2026-08-13 on request), the `isPriceSane` check is skipped for
     * whichever leg matches this shard by name - the OTHER input and the output are still checked as normal.
     * Rationale: `/hxp fuse for <shard>` is asked with a shard the player already intends to use regardless of
     * what its own book shape looks like ("da es mir dann ja egal ist") - the guard's whole point is to avoid
     * *recommending* a manipulated price, which doesn't apply to a leg the player isn't relying on the mod to
     * evaluate in the first place. This was the exact reason `/hxp fuse for Cod` returned nothing earlier: Cod's
     * own price sits ~20.04% off its book VWAP (just over the 20% cutoff) for unrelated reasons, which
     * shouldn't block every recipe that merely uses Cod as an ingredient.
     */
    private suspend fun scanAll(ignoreSanityCheckFor: String? = null): Result<List<ShardFusion>> {
        if (scanning) return Result.failure(IllegalStateException("A shard-fusion scan is already running."))
        scanning = true
        try {
            val data = FusionRepo.load() ?: return Result.failure(Exception("Failed to load the shard fusion recipe database."))
            val bazaar = RequestUtils.getBazaar().getOrElse { return Result.failure(it) }
            val products = bazaar.products
            val skipNormalized = ignoreSanityCheckFor?.let { normalizeShardName(it) }

            // [FusionRepo]'s data source (as of 2026-08-13) is a genuinely exhaustive enumeration of every
            // valid input pair - a single output can have 1000s of alternate second-input shards (the game's
            // "any shard of the right category+rarity is a valid filler" ID-Fusion rule), so the same ~320
            // shards each recur across many thousands of the 250k+ recipe rows. Every per-shard resolution
            // below (price/source/rate, and the isPriceSane VWAP-summary scan) depends only on the shard
            // itself, never on which recipe it's currently paired in - so it's memoized per shard code here
            // instead of being redone on every recipe row that happens to use it.
            val inputLegCache = HashMap<String, InputLeg?>()
            val outputLegCache = HashMap<String, OutputLeg?>()

            fun inputLeg(shard: FusionRepo.ShardInfo): InputLeg? = inputLegCache.memoized(shard.code) {
                val skip = skipNormalized != null && normalizeShardName(shard.name) == skipNormalized
                resolveInputLeg(shard, products, skip)
            }
            fun outputLeg(shard: FusionRepo.ShardInfo): OutputLeg? = outputLegCache.memoized(shard.code) {
                resolveOutputLeg(shard, products)
            }

            val seen = HashSet<Triple<String, String, String>>()
            val results = ArrayList<ShardFusion>()

            for (recipe in data.recipes) {
                val shard1 = data.shards[recipe.input1Code] ?: continue
                val shard2 = data.shards[recipe.input2Code] ?: continue
                val output = data.shards[recipe.outputCode] ?: continue

                // A+B->X and B+A->X are the same real-world fusion (order picked in the menu doesn't matter) -
                // evaluated and reported once, same dedup approach the reference profit-tracker script uses.
                val canon = Triple(minOf(shard1.internalId, shard2.internalId), maxOf(shard1.internalId, shard2.internalId), output.internalId)
                if (!seen.add(canon)) continue

                val leg1 = inputLeg(shard1) ?: continue
                val leg2 = inputLeg(shard2) ?: continue
                val legOut = outputLeg(output) ?: continue

                val fusion = combine(shard1, shard2, output, recipe.outputQty, leg1, leg2, legOut) ?: continue
                results.add(fusion)
            }

            return Result.success(results)
        } finally {
            scanning = false
        }
    }

    /** [get]s [key], computing+storing it via [compute] only the first time - unlike [MutableMap.getOrPut], a `null` result is itself cached rather than recomputed on every subsequent call. */
    private fun <K, V> HashMap<K, V?>.memoized(key: K, compute: () -> V?): V? {
        if (containsKey(key)) return get(key)
        return compute().also { put(key, it) }
    }

    /**
     * True if `usedPrice` (a `quick_status` field) is within [MAX_PRICE_DEVIATION_PERCENT] of the
     * volume-weighted average price across `summary`'s full visible depth - i.e. `quick_status` isn't being
     * skewed by a handful of orders sitting far from where the bulk of the book actually is.
     */
    private fun isPriceSane(usedPrice: Double, summary: List<BazaarApiData.Order>): Boolean {
        val totalAmount = summary.sumOf { it.amount }
        if (totalAmount <= 0L) return false
        val vwap = summary.sumOf { it.pricePerUnit * it.amount } / totalAmount
        if (vwap <= 0.0) return false
        return kotlin.math.abs(usedPrice - vwap) / vwap <= MAX_PRICE_DEVIATION_PERCENT / 100.0
    }

    /** Everything about a shard usable as a fusion INPUT that depends only on the shard itself, never on which recipe/partner it's currently being evaluated with - see [ShardFusionScanner.memoized]. */
    private data class InputLeg(val price: Double, val source: String, val ratePerHour: Double)

    /** Same idea as [InputLeg], for a shard used as a fusion OUTPUT (buy-side price instead of sell-side). */
    private data class OutputLeg(val price: Double, val source: String, val ratePerHour: Double)

    /**
     * Resolves [shard]'s price/source/rate as a fusion input (bid side), or null if it's currently
     * unusable (ignored, missing from the Bazaar snapshot, non-positive price, or - unless [skipSaneCheck] -
     * fails [isPriceSane]). Pure function of the shard + live Bazaar snapshot, safe to memoize per shard code
     * for the duration of one [scanAll] call.
     */
    private fun resolveInputLeg(shard: FusionRepo.ShardInfo, products: Map<String, BazaarApiData.Product>, skipSaneCheck: Boolean): InputLeg? {
        // Manual override (see FuseIgnoreList's own doc) - user-flagged manipulated shard.
        if (FuseIgnoreList.isIgnored(shard.name)) return null

        val product = products[shard.internalId] ?: return null
        val qs = product.quickStatus ?: return null

        // Prefer a fresher price from FuseCheck's live Bazaar-GUI scan (LiveShardPrices, see its own doc) over
        // the public API's quick_status - moving-week volume still only ever comes from the API, the GUI scan
        // has no equivalent of it.
        val live = LiveShardPrices.get(shard.name)?.sellPrice
        val price = live ?: qs.sellPrice
        if (price <= 0.0) return null

        // Manipulation/stale-price guard - independent of ROI (see class doc), bypassable per-shard via
        // [skipSaneCheck] (see [scanBestTargetsFor]'s own doc for why).
        if (!skipSaneCheck && !isPriceSane(price, product.sellSummary)) return null

        return InputLeg(price = price, source = if (live != null) "live" else "api", ratePerHour = qs.sellMovingWeek / HOURS_PER_WEEK)
    }

    /** Same as [resolveInputLeg], for a shard used as a fusion output (ask side) - the output's own `isPriceSane` check is never bypassable, unlike an input's. */
    private fun resolveOutputLeg(shard: FusionRepo.ShardInfo, products: Map<String, BazaarApiData.Product>): OutputLeg? {
        if (FuseIgnoreList.isIgnored(shard.name)) return null

        val product = products[shard.internalId] ?: return null
        val qs = product.quickStatus ?: return null

        val live = LiveShardPrices.get(shard.name)?.buyPrice
        val price = live ?: qs.buyPrice
        if (price <= 0.0) return null
        if (!isPriceSane(price, product.buySummary)) return null

        return OutputLeg(price = price, source = if (live != null) "live" else "api", ratePerHour = qs.buyMovingWeek / HOURS_PER_WEEK)
    }

    /** Combines one already-resolved [InputLeg]/[InputLeg]/[OutputLeg] triple - the only part of a fusion's evaluation that actually depends on which specific pair+recipe it is, everything else lives in [resolveInputLeg]/[resolveOutputLeg]. */
    private fun combine(
        shard1: FusionRepo.ShardInfo,
        shard2: FusionRepo.ShardInfo,
        output: FusionRepo.ShardInfo,
        outputQty: Int,
        leg1: InputLeg,
        leg2: InputLeg,
        legOut: OutputLeg,
    ): ShardFusion? {
        // "bid" (sellPrice) for inputs: what our own Buy Order fills at. "ask" (buyPrice) for the output: what
        // our own Sell Offer fills at, minus Bazaar's sell tax - same convention as BazaarFlipper.findBestFlips.
        val input1Cost = shard1.fuseAmount * leg1.price
        val input2Cost = shard2.fuseAmount * leg2.price
        val costPerFuse = input1Cost + input2Cost

        val revenuePerFuse = outputQty * legOut.price * (1 - BAZAAR_TAX)
        val profitPerFuse = revenuePerFuse - costPerFuse
        if (profitPerFuse <= 0.0 || costPerFuse <= 0.0) return null

        val roiPercent = profitPerFuse / costPerFuse * 100.0

        val rate1 = leg1.ratePerHour / shard1.fuseAmount
        val rate2 = leg2.ratePerHour / shard2.fuseAmount
        val rateOut = legOut.ratePerHour / outputQty
        val fusesPerHour = minOf(rate1, rate2, rateOut)
        if (fusesPerHour <= 0.0) return null // no realistic acquisition/sale throughput for one of the three legs

        return ShardFusion(
            outputName = output.name,
            outputRarity = output.rarity,
            input1Name = shard1.name, input1Qty = shard1.fuseAmount, input1Price = leg1.price,
            input2Name = shard2.name, input2Qty = shard2.fuseAmount, input2Price = leg2.price,
            outputQty = outputQty, outputPrice = legOut.price,
            costPerFuse = costPerFuse, profitPerFuse = profitPerFuse,
            fusesPerHour = fusesPerHour, profitPerHour = profitPerFuse * fusesPerHour,
            roiPercent = roiPercent,
            input1Source = leg1.source, input2Source = leg2.source, outputSource = legOut.source,
            input1RatePerHour = leg1.ratePerHour, input2RatePerHour = leg2.ratePerHour,
            outputRatePerHour = legOut.ratePerHour,
        )
    }
}
