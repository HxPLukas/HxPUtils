package de.hxp.hxpaddons.utils.skyblock.fusion

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.utils.network.WebUtils
import java.io.File

/**
 * Fetches/caches the community-maintained Attribute Shard fusion recipe database from
 * `Campionnn/SkyShards`'s `fusion-data.json` on GitHub - Hypixel's own API exposes no fusion data at all
 * (same gap [RecipeRepo] fills for crafting-table recipes). Backs the skyshards.com site, auto-synced from a
 * companion parser repo (`Campionnn/SkyShards-Parser`) via GitHub Actions rather than hand-edited, and as of
 * 2026-08-12 covers all 321 shards the live Bazaar trades (`fuse_amount` matches the Attribute Fusion wiki:
 * Reptile/Amphibian/Elemental-family shards fuse 2-at-a-time, every other family 5-at-a-time).
 *
 * Previously used `HichamIDDIR/Hypixel-Skyblock-Shards-Profit-Tracker`'s file of the same name/schema -
 * swapped 2026-08-12 after finding it stale (181 of the live Bazaar's 320 `SHARD_*` products, last commit
 * 2026-03-27, missing e.g. Flip Flopper entirely). Same schema, so this was a one-line URL swap.
 *
 * Schema: `shards` maps a short internal code (e.g. "C1") to `{name, family, type, rarity, fuse_amount,
 * internal_id}` where `internal_id` is the Bazaar product id (`SHARD_...`); `recipes` maps an output shard
 * code to `{outputQty -> [[inputCode1, inputCode2], ...]}` - i.e. every known input pair that can produce
 * that many of that output. This is flattened into a flat [FusionRecipe] list by [load].
 *
 * Unlike [RecipeRepo]'s NEU-repo cache (never expires - that repo is effectively the game's own crafting
 * data and essentially never changes retroactively), this is a single fan-maintained file that can get
 * corrections or new shards added after the fact, so the on-disk cache expires after [CACHE_TTL_MS] (24h)
 * rather than being kept forever.
 */
object FusionRepo {

    private const val RAW_URL = "https://raw.githubusercontent.com/Campionnn/SkyShards/master/public/fusion-data.json"
    private val cacheFile = File(HxPMod.configFile, "shard-fusion-data.json")
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    data class ShardInfo(val code: String, val name: String, val family: String, val type: String, val rarity: String, val fuseAmount: Int, val internalId: String)
    data class FusionRecipe(val outputCode: String, val outputQty: Int, val input1Code: String, val input2Code: String)
    data class FusionData(val shards: Map<String, ShardInfo>, val recipes: List<FusionRecipe>)

    @Volatile
    private var cached: FusionData? = null

    /** Loaded once per client session (plus the on-disk TTL cache) - this file is ~2MB of static recipe listings, no reason to re-parse it every scan. */
    suspend fun load(): FusionData? {
        cached?.let { return it }

        val text = readCacheIfFresh() ?: fetchAndCache() ?: return null
        val parsed = runCatching { parse(text) }.getOrElse {
            HxPMod.logger.error("FusionRepo: failed to parse fusion-data.json", it)
            null
        }
        parsed?.let { cached = it }
        return parsed
    }

    private fun readCacheIfFresh(): String? {
        if (!cacheFile.exists()) return null
        if (System.currentTimeMillis() - cacheFile.lastModified() > CACHE_TTL_MS) return null
        return cacheFile.readText().takeIf { it.isNotBlank() }
    }

    private suspend fun fetchAndCache(): String? {
        val text = WebUtils.fetchString(RAW_URL).getOrElse {
            HxPMod.logger.warn("FusionRepo: failed to fetch fusion-data.json, falling back to stale cache if any", it)
            return cacheFile.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
        }
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(text)
        }
        return text
    }

    private fun parse(text: String): FusionData {
        val root = JsonParser.parseString(text).asJsonObject

        val shards = HashMap<String, ShardInfo>()
        root.getAsJsonObject("shards")?.entrySet()?.forEach { (code, el) ->
            val obj = el.asJsonObject
            shards[code] = ShardInfo(
                code = code,
                name = obj.get("name")?.asString ?: code,
                family = obj.get("family")?.asString ?: "",
                type = obj.get("type")?.asString ?: "",
                rarity = obj.get("rarity")?.asString ?: "",
                fuseAmount = obj.get("fuse_amount")?.asInt ?: 5,
                internalId = obj.get("internal_id")?.asString ?: "",
            )
        }

        val recipes = ArrayList<FusionRecipe>()
        root.getAsJsonObject("recipes")?.entrySet()?.forEach { (outputCode, qtyMapEl) ->
            qtyMapEl.asJsonObject.entrySet().forEach { (qtyStr, pairsEl) ->
                val qty = qtyStr.toIntOrNull() ?: return@forEach
                pairsEl.asJsonArray.forEach pairLoop@{ pairEl ->
                    val pair = pairEl.asJsonArray
                    if (pair.size() < 2) return@pairLoop
                    recipes.add(FusionRecipe(outputCode, qty, pair[0].asString, pair[1].asString))
                }
            }
        }

        return FusionData(shards, recipes)
    }
}
