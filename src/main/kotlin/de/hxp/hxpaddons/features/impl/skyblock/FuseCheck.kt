package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.mixin.accessors.AbstractSignEditScreenAccessor
import de.hxp.hxpaddons.utils.clickSlot
import de.hxp.hxpaddons.utils.devMessage
import de.hxp.hxpaddons.utils.loreString
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.sendChatMessage
import de.hxp.hxpaddons.utils.sendCommand
import de.hxp.hxpaddons.utils.skyblock.fusion.LiveShardPrices
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ContainerInput

/**
 * `/hxp fuse check command` (via the `/bz` command) / `/hxp fuse check npc` (via right-clicking a nearby Bazaar NPC instead
 * - see [interactWithBazaarNpc], ported from [BazaarFlipper]'s own identical NPC-opening path): opens the
 * Bazaar, searches "shard", opens the first search result and immediately backs out via "Go Back" - which
 * (confirmed by the user's own description of the live flow, not yet verified here) lands on the item's
 * parent category page, i.e. the full "Shards" listing with every shard's Buy/Sell price visible at once -
 * then pages through it end to end via "Next Page", logging every shard's price off each page. Purpose: get a
 * fresher price read straight from the live client GUI than [ShardFusionScanner]'s public Bazaar API snapshot
 * (which lags behind the live market by however old the API's own cache is) for
 * [ShardFusionScanner]/[Fuser]'s coins/hour math.
 *
 * Reverse engineered from a text description of the flow, same starting point every other GUI-automation
 * module here had (see [BazaarFlipper]'s own doc) - every screen is dumped via [devMessage], so the fastest
 * way to fix a wrong keyword/step is: turn on Dev Messages (`/hxpdev`), run either command once, and send
 * back what got logged. Same "no click before the server actually sends a new GUI" rule [Fuser] already
 * established: every step snapshots [contentSignature] right before its click and waits for that to actually
 * change (not just for some screen to match a predicate, which could still be the stale pre-click one).
 */
object FuseCheck : Module(
    name = "Fuse Check",
    description = "Pages through the Bazaar's Shards listing and logs every shard's live Buy/Sell price.",
    category = Category.SKYBLOCK
) {
    private const val GUI_UPDATE_SETTLE_MS = 200L
    private const val MAX_PAGES = 20
    /** How many consecutive [GUI_UPDATE_SETTLE_MS]-spaced polls the content signature must stay byte-identical for before a page counts as "finished loading" - see [waitForStableSignature]. */
    private const val STABLE_CHECKS_REQUIRED = 3

    private const val BAZAAR_NPC_NAME = "Bazaar"
    private const val BAZAAR_NPC_SEARCH_RADIUS = 3.0
    private const val BAZAAR_NPC_INTERACT_RANGE = 3.0

    private val priceRegex = Regex("(buy|sell)\\s*price:\\s*([\\d,.]+)", RegexOption.IGNORE_CASE)

    data class ShardPrice(val name: String, val buyPrice: Double?, val sellPrice: Double?)

    private var job: Job? = null

    override fun onDisable() {
        super.onDisable()
        job?.cancel()
        job = null
    }

    /** [viaNpc]: `/hxp fuse check npc` opens via right-clicking a nearby Bazaar NPC instead of the default `/bz` command (`/hxp fuse check command`, and plain `/hxp fuse check` for backwards compat). */
    fun start(viaNpc: Boolean = false) {
        if (job?.isActive == true) {
            modMessage("§eFuse Check is already running.")
            return
        }
        if (!enabled) toggle()

        job = HxPMod.scope.launch {
            runCatching { runFuseCheck(viaNpc) }
                .onFailure {
                    HxPMod.logger.error("FuseCheck failed", it)
                    modMessage("§cFuse Check: failed unexpectedly (${it.message}) - see log.")
                }
        }
    }

    private suspend fun runFuseCheck(viaNpc: Boolean) {
        modMessage("§7Fuse Check: opening the Bazaar${if (viaNpc) " (via NPC)" else " (via /bz)"}...")
        if (mc.screen !is AbstractContainerScreen<*>) {
            if (viaNpc) {
                if (!interactWithBazaarNpc()) {
                    modMessage("§cFuse Check: no nearby Bazaar NPC found/reachable - not falling back to /bz.")
                    return
                }
            } else {
                sendCommand("bz")
            }
        }

        var screen = waitForScreen(5000) { true } ?: run {
            modMessage("§cFuse Check: the Bazaar menu never opened.")
            return
        }
        dumpScreen(screen, "Bazaar main")

        // /bz can reopen wherever the player last left off (a sub-category, an item page, ...) instead of the
        // true root - back out via "Go Back" until a "Search" button is actually findable, same pattern
        // BazaarFlipper's placeOrderViaSearch uses.
        var backAttempts = 0
        var searchSlot = screen.findSlot("search")
        while (searchSlot == null && backAttempts++ < 5) {
            val backSlot = screen.findSlot("go back") ?: break
            var previous = screen.contentSignature()
            click(backSlot)
            screen = waitForGuiUpdate(previous, timeoutMs = 5000) { true } ?: run {
                modMessage("§cFuse Check: lost the Bazaar screen while backing out to find 'Search'.")
                return
            }
            dumpScreen(screen, "After Go Back (finding Search)")
            searchSlot = screen.findSlot("search")
        }
        val foundSearchSlot = searchSlot ?: run {
            modMessage("§cFuse Check: couldn't find a 'Search' button in the Bazaar menu.")
            return
        }

        var previous = screen.contentSignature()
        click(foundSearchSlot)
        delay(GUI_UPDATE_SETTLE_MS)
        if (!submitTextInput("shard")) {
            modMessage("§cFuse Check: couldn't submit the search text - unexpected screen: ${mc.screen?.let { it::class.simpleName } ?: "none"}.")
            return
        }

        val resultsScreen = waitForGuiUpdate(previous, timeoutMs = 5000) { it.firstShardNamedSlot() != null } ?: run {
            modMessage("§cFuse Check: no search result with 'shard' in its name appeared for 'shard'.")
            return
        }
        dumpScreen(resultsScreen, "Search results")

        val firstResultSlot = resultsScreen.firstShardNamedSlot() ?: run {
            modMessage("§cFuse Check: no item with 'shard' in its name found in the search results.")
            return
        }
        previous = resultsScreen.contentSignature()
        click(firstResultSlot)

        val itemScreen = waitForGuiUpdate(previous, timeoutMs = 5000) { it.findSlot("go back") != null } ?: run {
            modMessage("§cFuse Check: no item page (or no 'Go Back' button on it) appeared after clicking the first search result.")
            return
        }
        dumpScreen(itemScreen, "Item page")

        val goBackSlot = itemScreen.findSlot("go back") ?: run {
            modMessage("§cFuse Check: no 'Go Back' button found on the item page.")
            return
        }
        previous = itemScreen.contentSignature()
        click(goBackSlot)

        var categoryScreen = waitForGuiUpdate(previous, timeoutMs = 5000) { true } ?: run {
            modMessage("§cFuse Check: lost the Bazaar screen after clicking 'Go Back' from the item page.")
            return
        }
        dumpScreen(categoryScreen, "Shards category (page 1)")
        categoryScreen.debugPrintFirstShard()

        val allShards = mutableListOf<ShardPrice>()
        val seenSignatures = mutableSetOf<String>()
        var page = 1
        while (page <= MAX_PAGES && enabled) {
            val signature = categoryScreen.contentSignature()
            if (!seenSignatures.add(signature)) {
                devMessage("[FuseCheck] Page $page's content signature repeats an earlier page - stopping (wrapped around).")
                break
            }
            allShards += categoryScreen.readShardPrices()

            val nextPageSlot = categoryScreen.findSlot("next page")
            if (nextPageSlot == null) {
                devMessage("[FuseCheck] No 'Next Page' button on page $page - assuming it's the last one.")
                break
            }
            previous = categoryScreen.contentSignature()
            click(nextPageSlot)
            val nextScreen = waitForGuiUpdate(previous, timeoutMs = 5000) { true }
            if (nextScreen == null) {
                modMessage("§cFuse Check: timed out waiting for page ${page + 1} to load - stopping with what's been logged so far.")
                break
            }
            categoryScreen = nextScreen
            page++
            dumpScreen(categoryScreen, "Shards category (page $page)")
        }

        closeScreen()

        if (allShards.isEmpty()) {
            modMessage("§cFuse Check: no shard prices could be read - see dev log dumps above.")
            return
        }

        val distinct = allShards.distinctBy { it.name }
        // Deliberately not printed here (chat or dev log) - per explicit user request, these values only ever
        // feed ShardFusionScanner's math via LiveShardPrices, never shown as a list on their own. Raw names
        // passed through as-is (e.g. "Queen Ant Shard") - LiveShardPrices.normalize() strips the "Shard"
        // suffix itself so it matches FusionRepo's own "Queen Ant"-style names on the read side.
        LiveShardPrices.update(distinct.associate { it.name to LiveShardPrices.Entry(it.buyPrice, it.sellPrice) })
        modMessage("§aFuse Check: captured ${distinct.size} shard price(s) across $page page(s) - ready for /hxp fuse best.")
    }

    /** Every top-slot item's name + parsed Buy/Sell price off its lore (see [priceRegex]'s doc for the expected "Buy Price: X"/"Sell Price: X" lore format - unconfirmed live). */
    private fun AbstractContainerScreen<*>.readShardPrices(): List<ShardPrice> {
        val top = topSlotCount()
        val result = mutableListOf<ShardPrice>()
        for (i in 0 until top) {
            val stack = menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val name = stack.hoverName.string.noControlCodes.trim()
            val loreText = stack.loreString.joinToString(" ") { it.noControlCodes }
            var buyPrice: Double? = null
            var sellPrice: Double? = null
            priceRegex.findAll(loreText).forEach { m ->
                val value = m.groupValues[2].replace(",", "").toDoubleOrNull()
                if (value != null) {
                    if (m.groupValues[1].equals("buy", ignoreCase = true)) buyPrice = value
                    else sellPrice = value
                }
            }
            if (buyPrice != null || sellPrice != null) result += ShardPrice(name, buyPrice, sellPrice)
        }
        return result
    }

    /**
     * One-off sanity print (regular chat, not [devMessage] - visible without toggling Dev Messages on) of the
     * first item on page 1 that [readShardPrices] actually managed to parse a Buy/Sell price out of: its raw
     * lore plus the parsed numbers, so a live run can be checked at a glance without digging through the log.
     *
     * Deliberately NOT just "slot 0" (an earlier version was) - the category page's very first top slot is
     * often a UI element (sort/filter button, decorative pane, ...) with no "Buy Price:"/"Sell Price:" lore at
     * all, which made this always print "(not found)" even while [readShardPrices] was correctly parsing
     * every real shard slot right next to it (confirmed live: a full page-1..12 run still captured 305 shard
     * prices while this printed "not found" for slot 0).
     */
    private fun AbstractContainerScreen<*>.debugPrintFirstShard() {
        val parsed = readShardPrices().firstOrNull() ?: run {
            modMessage("§cFuse Check debug: no item with a parseable Buy/Sell price found on page 1 at all - check the dumped lore in the dev log.")
            return
        }
        val top = topSlotCount()
        val stack = (0 until top).asSequence()
            .mapNotNull { menu.items.getOrNull(it) }
            .firstOrNull { !it.isEmpty && it.hoverName.string.noControlCodes.trim() == parsed.name }
        val loreText = stack?.loreString?.joinToString(" | ") { it.noControlCodes } ?: "(couldn't re-find the item's lore)"
        val buyText = parsed.buyPrice?.let { "%,.1f".format(it) } ?: "§c(not found)"
        val sellText = parsed.sellPrice?.let { "%,.1f".format(it) } ?: "§c(not found)"
        modMessage("§7Fuse Check debug - first parsed shard: §f${parsed.name}")
        modMessage("§7  lore: §f$loreText")
        modMessage("§7  parsed: §fbuy §a$buyText §7/ §fsell §a$sellText")
    }

    /** First (lowest-index) non-empty item slot among the top-of-screen (non-player-inventory) slots. */
    private fun AbstractContainerScreen<*>.firstItemSlot(): Int? {
        val top = topSlotCount()
        for (i in 0 until top) {
            val stack = menu.items.getOrNull(i) ?: continue
            if (!stack.isEmpty) return i
        }
        return null
    }

    /**
     * First (lowest-index) item slot whose own display name (not lore, unlike [findSlot]) actually contains
     * "shard" - the "shard" search can surface non-shard items too (e.g. anything whose lore mentions the
     * word), so this picks the first genuine shard result to click into rather than whatever happens to be
     * sorted first.
     */
    private fun AbstractContainerScreen<*>.firstShardNamedSlot(): Int? {
        val top = topSlotCount()
        for (i in 0 until top) {
            val stack = menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            if (stack.hoverName.string.noControlCodes.contains("shard", ignoreCase = true)) return i
        }
        return null
    }

    private fun AbstractContainerScreen<*>.topSlotCount(): Int = (menu.items.size - 36).coerceAtLeast(0)

    private fun AbstractContainerScreen<*>.findSlot(keyword: String): Int? {
        val top = topSlotCount()
        for (i in 0 until top) {
            val stack = menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val text = (stack.hoverName.string + " " + stack.loreString.joinToString(" ")).noControlCodes.lowercase()
            if (text.contains(keyword.lowercase())) return i
        }
        return null
    }

    /** Cheap content fingerprint - not identity/title, since Hypixel commonly re-sends the same Screen/title with just new slot contents. Same technique [Fuser]/`BazaarFlipper` use for their own equivalents. */
    private fun AbstractContainerScreen<*>.contentSignature(): String {
        val top = topSlotCount()
        return (0 until top).joinToString("|") { i ->
            val stack = menu.items.getOrNull(i)
            if (stack == null || stack.isEmpty) "" else "${stack.hoverName.string}x${stack.count}#${stack.loreString.joinToString(",")}"
        }
    }

    /**
     * Waits for the open container's content to actually change away from [previousSignature] and match
     * [predicate], then confirms the page has genuinely finished loading (see [waitForStableSignature])
     * before returning - the exact rule the user asked for: never act on a click's result before a
     * genuinely new GUI has arrived from the server AND settled, not just "some screen matching X is open"
     * (which could be the stale pre-click one) or "changed once" (which could be a half-updated page).
     */
    private suspend fun waitForGuiUpdate(previousSignature: String?, timeoutMs: Long = 5000, predicate: (AbstractContainerScreen<*>) -> Boolean): AbstractContainerScreen<*>? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            (mc.screen as? AbstractContainerScreen<*>)?.let {
                if (it.contentSignature() != previousSignature && predicate(it)) {
                    val remaining = timeoutMs - (System.currentTimeMillis() - start)
                    val stable = waitForStableSignature(previousSignature, remaining.coerceAtLeast(0), predicate)
                    if (stable != null) return stable
                }
            }
            delay(50)
        }
        val finalScreen = mc.screen
        val described = when {
            finalScreen == null -> "none"
            finalScreen is AbstractContainerScreen<*> -> "${finalScreen::class.simpleName} (title='${finalScreen.title.string.noControlCodes}', changed=${finalScreen.contentSignature() != previousSignature})"
            else -> finalScreen::class.simpleName ?: "unknown"
        }
        devMessage("[FuseCheck] waitForGuiUpdate timed out after ${timeoutMs}ms - screen at timeout: $described.")
        return null
    }

    /**
     * Hypixel doesn't always send a whole page's ~45 shard slots in one packet - it can trickle individual
     * slot updates in over several hundred ms. A single post-change settle check (the old behaviour, one
     * [GUI_UPDATE_SETTLE_MS] delay then re-check) could catch a page mid-update: some shard slots still
     * showing the previous page's items, or the "Next Page" button not rendered in yet - which made
     * pagination think it had hit the last page and stop early (2026-08-13, user reported only 143 shards
     * across 7 pages captured when there are visibly more). Fix: require [content signature][contentSignature]
     * to come back byte-identical across [STABLE_CHECKS_REQUIRED] consecutive polls, [GUI_UPDATE_SETTLE_MS]
     * apart, before treating the page as actually finished loading - any slot still changing resets the
     * streak, so a page is only ever read/paginated-from once it's genuinely done.
     */
    private suspend fun waitForStableSignature(previousSignature: String?, timeoutMs: Long, predicate: (AbstractContainerScreen<*>) -> Boolean): AbstractContainerScreen<*>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSignature: String? = null
        var stableCount = 0
        while (System.currentTimeMillis() < deadline) {
            delay(GUI_UPDATE_SETTLE_MS)
            val screen = mc.screen as? AbstractContainerScreen<*> ?: return null
            val signature = screen.contentSignature()
            if (signature == previousSignature || !predicate(screen)) return null
            if (signature == lastSignature) {
                stableCount++
                if (stableCount >= STABLE_CHECKS_REQUIRED) return screen
            } else {
                lastSignature = signature
                stableCount = 1
            }
        }
        return null
    }

    private suspend fun waitForScreen(timeoutMs: Long = 5000, predicate: (AbstractContainerScreen<*>) -> Boolean): AbstractContainerScreen<*>? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            (mc.screen as? AbstractContainerScreen<*>)?.let { if (predicate(it)) return it }
            delay(50)
        }
        return null
    }

    private fun submitTextInput(text: String): Boolean {
        val signScreen = mc.screen as? AbstractSignEditScreen
        if (signScreen != null) {
            val sign = (signScreen as AbstractSignEditScreenAccessor).sign
            val originalLines = Array(4) { i -> sign.frontText.getMessage(i, false).string }
            devMessage("[FuseCheck] Sign lines before edit: ${originalLines.joinToString(" | ")}")
            mc.execute {
                mc.player?.connection?.send(ServerboundSignUpdatePacket(sign.blockPos, true, text, originalLines[1], originalLines[2], originalLines[3]))
                mc.setScreen(null)
            }
            return true
        }
        if (mc.screen == null) {
            sendChatMessage(text)
            return true
        }
        return false
    }

    /**
     * Right-clicks the nearest entity within [BAZAAR_NPC_SEARCH_RADIUS] whose name contains [BAZAAR_NPC_NAME],
     * provided it's within [BAZAAR_NPC_INTERACT_RANGE]. Returns whether a packet was actually sent. Same
     * primitive as `BazaarFlipper.interactWithBazaarNpc` (own private copy per this file's usual convention),
     * including the same unconfirmed-live guess about the `ServerboundInteractPacket` shape.
     */
    private fun interactWithBazaarNpc(): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false

        val npc = level.entitiesForRendering()
            .filter { it.customName?.string?.noControlCodes?.contains(BAZAAR_NPC_NAME, ignoreCase = true) == true }
            .filter { it.distanceToSqr(player) <= BAZAAR_NPC_SEARCH_RADIUS * BAZAAR_NPC_SEARCH_RADIUS }
            .minByOrNull { it.distanceToSqr(player) }
        if (npc == null) {
            devMessage("§cFuse Check: no nearby NPC named '$BAZAAR_NPC_NAME' found within $BAZAAR_NPC_SEARCH_RADIUS blocks.")
            return false
        }
        val distance = player.distanceTo(npc)
        if (distance > BAZAAR_NPC_INTERACT_RANGE) {
            devMessage("§cFuse Check: found '$BAZAAR_NPC_NAME' NPC but it's ${"%.1f".format(distance)} blocks away (max $BAZAAR_NPC_INTERACT_RANGE).")
            return false
        }

        devMessage("[FuseCheck] Interacting with Bazaar NPC '${npc.customName?.string}' (id ${npc.id}, ${"%.1f".format(distance)} blocks away).")
        val relativeHit = npc.boundingBox.center.subtract(npc.position())
        mc.execute {
            val connection = mc.player?.connection ?: return@execute
            repeat(2) { connection.send(ServerboundInteractPacket(npc.id, InteractionHand.MAIN_HAND, relativeHit, player.isShiftKeyDown)) }
        }
        return true
    }

    private fun click(slotIndex: Int, button: Int = 0, clickType: ContainerInput = ContainerInput.PICKUP) {
        mc.execute {
            val menu = mc.player?.containerMenu ?: return@execute
            if (slotIndex !in menu.slots.indices) {
                devMessage("§cFuse Check: skipped click on slot #$slotIndex - current menu only has ${menu.slots.size} slots (screen changed underneath us?).")
                return@execute
            }
            mc.player?.clickSlot(menu.containerId, slotIndex, button, clickType)
        }
    }

    private suspend fun closeScreen() {
        if (mc.screen == null) return
        mc.execute { mc.setScreen(null) }
        var waited = 0L
        while (mc.screen != null && waited < 500) {
            delay(20)
            waited += 20
        }
    }

    private fun dumpScreen(screen: AbstractContainerScreen<*>, label: String) {
        val top = screen.topSlotCount()
        val text = buildString {
            append("[FuseCheck] ").append(label).append(" | title='").append(screen.title.string.noControlCodes).append("'\n")
            for (i in 0 until top) {
                val stack = screen.menu.items.getOrNull(i) ?: continue
                if (stack.isEmpty) continue
                append("  #").append(i).append(": ").append(stack.hoverName.string.noControlCodes)
                val lore = stack.loreString.joinToString(" / ") { it.noControlCodes }
                if (lore.isNotBlank()) append(" | ").append(lore)
                append('\n')
            }
        }
        devMessage(text)
    }
}
