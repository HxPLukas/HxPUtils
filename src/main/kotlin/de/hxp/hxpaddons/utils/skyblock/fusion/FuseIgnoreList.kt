package de.hxp.hxpaddons.utils.skyblock.fusion

/**
 * User-maintained "ignore this shard's fusions for a while" list, populated by `/hxp fuse ignore <name>` - lets
 * the player manually blacklist a shard they've spotted being actively manipulated beyond what
 * [ShardFusionScanner]'s own `isPriceSane` VWAP-deviation guard catches (that guard only flags a single
 * leg's price diverging from its own book depth, not e.g. a shard the player has independently noticed being
 * pumped/dumped in a way that still looks "sane" by the book alone). Every `/hxp fuse best*` scan skips any
 * fusion where the ignored shard appears as either input or the output (2026-08-13, on request).
 *
 * No explicit "unignore" command for a single shard - a manipulation window is expected to be temporary, so
 * entries just expire after [DURATION_MS] (10 minutes) on their own; re-running `/hxp fuse ignore` on an
 * already-ignored shard simply refreshes its timer instead of stacking a second entry. [clear] (wired to
 * `/hxp fuse ignore clear` - 2026-08-13, on request) empties the whole list at once instead of waiting out each
 * entry's own timer.
 */
object FuseIgnoreList {
    private const val DURATION_MS = 10 * 60_000L

    @Volatile
    private var ignoredUntil: Map<String, Long> = emptyMap()

    /** Adds/refreshes [name] on the ignore list for [DURATION_MS] from now. Returns true if it was already (still) ignored, i.e. this call only refreshed its timer rather than adding it fresh. */
    @Synchronized
    fun ignore(name: String): Boolean {
        val key = normalizeShardName(name)
        val alreadyIgnored = isIgnored(name)
        ignoredUntil = ignoredUntil + (key to (System.currentTimeMillis() + DURATION_MS))
        return alreadyIgnored
    }

    fun isIgnored(name: String): Boolean {
        val until = ignoredUntil[normalizeShardName(name)] ?: return false
        return System.currentTimeMillis() < until
    }

    /** Empties the whole list immediately. Returns how many entries were still actually active (not yet expired) at the time - purely for a nicer confirmation message, not used for any logic. */
    @Synchronized
    fun clear(): Int {
        val activeCount = ignoredUntil.count { (_, until) -> System.currentTimeMillis() < until }
        ignoredUntil = emptyMap()
        return activeCount
    }
}
