package de.hxp.hxpaddons.utils.skyblock.fusion

/**
 * Freshest known Buy/Sell price per shard, written by `FuseCheck`'s live Bazaar-GUI scan
 * (`/hxp fuse check npc`/`/hxp fuse check command`) and read by [ShardFusionScanner] to override the public Bazaar API's
 * own (comparatively stale) `quick_status` price for whichever shards a scan actually covered - the API
 * remains the only source for moving-week volume, so this only ever replaces the two price fields, never
 * the whole product. Deliberately silent (no chat/log output of its own here) - per explicit user request,
 * these values only ever feed [ShardFusionScanner]'s math, they're never printed as a list anywhere.
 *
 * [MAX_AGE_MS]: a scan capturing prices once and then just sitting there for hours would defeat the entire
 * point ("fresher than the API") - past this age, [get] returns null for everything (falls back to the API
 * price) rather than serving silently-stale data.
 */
object LiveShardPrices {
    data class Entry(val buyPrice: Double?, val sellPrice: Double?)

    private const val MAX_AGE_MS = 30 * 60_000L

    @Volatile
    private var prices: Map<String, Entry> = emptyMap()

    @Volatile
    private var capturedAtMs: Long = 0L

    /** [entries] keyed by raw shard display name (any casing/whitespace/"Shard" suffix - see [normalizeShardName]). Replaces the previous snapshot wholesale. */
    fun update(entries: Map<String, Entry>) {
        prices = entries.mapKeys { normalizeShardName(it.key) }
        capturedAtMs = System.currentTimeMillis()
    }

    fun get(shardName: String): Entry? {
        if (prices.isEmpty()) return null
        if (System.currentTimeMillis() - capturedAtMs > MAX_AGE_MS) return null
        return prices[normalizeShardName(shardName)]
    }
}
