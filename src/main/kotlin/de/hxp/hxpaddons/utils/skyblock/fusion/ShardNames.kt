package de.hxp.hxpaddons.utils.skyblock.fusion

/**
 * Normalizes a shard's display name for cross-source matching - [FusionRepo]'s own names have no trailing
 * "Shard" (e.g. "Coralot"), while the Bazaar GUI's item names do (e.g. "Coralot Shard"), and players
 * naturally type either. Confirmed live (2026-08-13) this mismatch was a real, silent bug: [LiveShardPrices]
 * originally used a plain trim+lowercase match, so "queen ant shard" != "queen ant" and every live-price
 * lookup silently fell back to the (staler) Bazaar API despite `FuseCheck` reporting a successful scan.
 * Shared by [LiveShardPrices], [FuseIgnoreList], and [ShardFusionScanner]'s own by-name lookups (factored out
 * once a third call site needed the exact same logic) so all three agree on the same key.
 */
internal fun normalizeShardName(name: String): String {
    val trimmed = name.trim()
    val stripped = if (trimmed.endsWith(" shard", ignoreCase = true)) trimmed.dropLast(6).trim() else trimmed
    return stripped.lowercase()
}
