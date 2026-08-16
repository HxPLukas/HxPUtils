package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.clickSlot
import de.hxp.hxpaddons.utils.devMessage
import de.hxp.hxpaddons.utils.loreString
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.simulateRightClick
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput

/**
 * Automates the Hunting Box "Fusion" menu flow for the `/hxp fuse run` command: right-clicks the held Fusion
 * item, picks both configured shards across the follow-up menus, confirms with the result item, then clicks
 * "Fusion" and repeats via the menu's own "Repeat" button as many times as the shards on hand allow. Per-fuse
 * quantity of each shard is either given explicitly in the command or defaults to [DEFAULT_SHARDS_PER_FUSE] -
 * not every recipe needs 5 of both, see [start]/[parseArgs] - and the amount on hand is read from the
 * "Hunting Box: <amount>" lore line both shard items show in the very first menu.
 *
 * Ported from HxPAddons's own Fuser (only the `run` flow - its `test`/other fuse-related commands aren't part
 * of HxPUtils). Reverse engineered from a description of the live flow rather than a screen dump - every
 * screen touched is dumped (item names + lore) via [devMessage], so the fastest way to fix a wrong
 * keyword/step is: turn on Dev Messages, run `/hxp fuse run` once, and check what got logged.
 */
object Fuser : Module(
    name = "Fuser",
    description = "Automates fusing two Hunting Box shards into a result via the Fusion menu, repeating as often as the shards on hand allow.",
    category = Category.SKYBLOCK
) {
    /** Fallback per-shard quantity when the command doesn't specify one explicitly - matches the common case, but plenty of recipes need less (see [start]'s parsing). */
    private const val DEFAULT_SHARDS_PER_FUSE = 5

    /**
     * The server re-sends the container's contents after every single click here, even when whatever the
     * next step wants to click on happens to already be visible in the pre-click GUI - so every step below
     * waits for that content to actually change away from a signature snapshotted right before its click,
     * rather than just checking that *some* screen matching a predicate is currently open (which could just
     * be the stale pre-click one, matching immediately with no real wait at all). Once a genuinely changed
     * screen also matches the step's predicate, this is how long to additionally sit before acting on it.
     */
    private const val GUI_UPDATE_SETTLE_MS = 200L

    private val huntingBoxRegex = Regex("hunting box:\\s*([\\d,]+)", RegexOption.IGNORE_CASE)

    private var job: Job? = null

    override fun onDisable() {
        super.onDisable()
        job?.cancel()
        job = null
    }

    /**
     * Entry point for the `/hxp fuse run` command. Each shard argument optionally ends in a number giving
     * that shard's own per-fuse quantity (recipes aren't all 5+5 - some need less on one or both legs), e.g.
     * `/hxp fuse run Cod 5 Verdant 5 Dumpster Diver`. A shard with no trailing number defaults to
     * [DEFAULT_SHARDS_PER_FUSE]. Accepts either plain space-separated args (for single-word shard names) or
     * `<shard 1> [qty]> | <shard 2> [qty]> | <result>` (for item names that contain spaces themselves, e.g.
     * "Wither Shard 3") - `|` in the raw args picks the latter.
     */
    fun start(rawArgs: String) {
        val parsed = parseArgs(rawArgs)
        if (parsed == null) {
            modMessage("§cFuser: usage is /hxp fuse run <shard 1> [qty] <shard 2> [qty] <result> (or, for multi-word item names, /hxp fuse run <shard 1> [qty] | <shard 2> [qty] | <result>). Quantity defaults to $DEFAULT_SHARDS_PER_FUSE if omitted.")
            return
        }
        if (job?.isActive == true) {
            modMessage("§eFuser is already running.")
            return
        }
        if (!enabled) toggle()

        val (shard1, qty1, shard2, qty2, result) = parsed
        job = HxPMod.scope.launch {
            runCatching { runFuseCycle(shard1, qty1, shard2, qty2, result) }
                .onFailure {
                    HxPMod.logger.error("Fuser failed", it)
                    modMessage("§cFuser: failed unexpectedly (${it.message}) - see log.")
                }
        }
    }

    private data class FuseArgs(val shard1: String, val qty1: Int, val shard2: String, val qty2: Int, val result: String)

    private fun parseArgs(rawArgs: String): FuseArgs? {
        if (rawArgs.contains("|")) {
            val parts = rawArgs.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size != 3) return null
            val (shard1, qty1) = parseShardAndQty(parts[0]) ?: return null
            val (shard2, qty2) = parseShardAndQty(parts[1]) ?: return null
            return FuseArgs(shard1, qty1, shard2, qty2, parts[2])
        }

        val tokens = rawArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // "<shard1> <qty1> <shard2> <qty2> <result...>" - both quantity tokens must be numeric, or this isn't that format.
        if (tokens.size >= 5) {
            val qty1 = tokens[1].toIntOrNull()
            val qty2 = tokens[3].toIntOrNull()
            if (qty1 != null && qty2 != null) {
                return FuseArgs(tokens[0], qty1, tokens[2], qty2, tokens.drop(4).joinToString(" "))
            }
        }
        // Legacy "<shard1> <shard2> <result>" (single-word names only) - both default to DEFAULT_SHARDS_PER_FUSE.
        if (tokens.size == 3) {
            return FuseArgs(tokens[0], DEFAULT_SHARDS_PER_FUSE, tokens[1], DEFAULT_SHARDS_PER_FUSE, tokens[2])
        }
        return null
    }

    /** Splits a single `|`-delimited part into shard name + per-fuse quantity - a trailing numeric token is the quantity, otherwise it defaults to [DEFAULT_SHARDS_PER_FUSE]. */
    private fun parseShardAndQty(part: String): Pair<String, Int>? {
        val tokens = part.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val lastAsQty = tokens.last().toIntOrNull()
        return if (lastAsQty != null && tokens.size > 1) {
            tokens.dropLast(1).joinToString(" ") to lastAsQty
        } else {
            part to DEFAULT_SHARDS_PER_FUSE
        }
    }

    private suspend fun runFuseCycle(shard1: String, qty1: Int, shard2: String, qty2: Int, result: String) {
        modMessage("§7Fuser: opening the Fusion menu...")
        val beforeOpen = currentGuiSignature()
        simulateRightClick()

        val firstMenu = waitForGuiUpdate(beforeOpen, 5000) { it.findSlot(shard1) != null || it.findSlot(shard2) != null } ?: run {
            modMessage("§cFuser: the Fusion menu never opened (or neither shard was found in it) - is the Fusion item in your hand?")
            return
        }
        dumpScreen(firstMenu, "Fusion menu")

        val amount1 = huntingBoxAmount(firstMenu, shard1)
        val amount2 = huntingBoxAmount(firstMenu, shard2)
        if (amount1 == null || amount2 == null) {
            modMessage("§cFuser: couldn't read a \"Hunting Box: <amount>\" lore line off both shards - see dev log.")
            return
        }

        var remaining = minOf(amount1 / qty1, amount2 / qty2)
        if (remaining <= 0) {
            modMessage("§cFuser: not enough shards - need at least ${qty1}x $shard1 and ${qty2}x $shard2 per fuse (have ${amount1}x $shard1, ${amount2}x $shard2).")
            return
        }
        modMessage("§7Fuser: ${amount1}x $shard1, ${amount2}x $shard2 on hand ($qty1/$qty2 per fuse) - running $remaining fuse(s).")

        val firstSlot = firstMenu.findSlot(shard1)
        val (clickSlotIndex, secondShardName) = if (firstSlot != null) firstSlot to shard2
        else (firstMenu.findSlot(shard2) ?: run {
            modMessage("§cFuser: neither shard could be found in the Fusion menu.")
            return
        }) to shard1
        var previous = firstMenu.contentSignature()
        click(clickSlotIndex)

        val secondMenu = waitForGuiUpdate(previous, 5000) { it.findSlot(secondShardName) != null } ?: return timedOut("the second shard's menu")
        dumpScreen(secondMenu, "After first shard")
        val secondSlot = secondMenu.findSlot(secondShardName) ?: run {
            modMessage("§cFuser: couldn't find \"$secondShardName\" in the second menu.")
            return
        }
        previous = secondMenu.contentSignature()
        click(secondSlot)

        val resultMenu = waitForGuiUpdate(previous, 5000) { it.findSlot(result) != null } ?: return timedOut("the result menu")
        dumpScreen(resultMenu, "Before result")
        val resultSlot = resultMenu.findSlot(result) ?: run {
            modMessage("§cFuser: couldn't find result item \"$result\" in the menu.")
            return
        }
        previous = resultMenu.contentSignature()
        click(resultSlot)

        val fusionMenu = waitForGuiUpdate(previous, 5000) { it.findSlotExact("fusion") != null } ?: return timedOut("the Fusion button")
        dumpScreen(fusionMenu, "Before Fusion click")
        val fusionSlot = fusionMenu.findSlotExact("fusion") ?: run {
            modMessage("§cFuser: couldn't find the \"Fusion\" button.")
            return
        }
        previous = fusionMenu.contentSignature()
        click(fusionSlot)
        remaining--

        while (remaining > 0 && enabled) {
            val repeatMenu = waitForGuiUpdate(previous, 5000) { it.findSlot("repeat") != null } ?: return timedOut("the Repeat button ($remaining fuse(s) left)")
            val repeatSlot = repeatMenu.findSlot("repeat") ?: return timedOut("the Repeat button ($remaining fuse(s) left)")
            previous = repeatMenu.contentSignature()
            click(repeatSlot)

            val nextFusionMenu = waitForGuiUpdate(previous, 5000) { it.findSlotExact("fusion") != null } ?: return timedOut("Fusion after Repeat ($remaining fuse(s) left)")
            val nextFusionSlot = nextFusionMenu.findSlotExact("fusion") ?: return timedOut("Fusion after Repeat ($remaining fuse(s) left)")
            previous = nextFusionMenu.contentSignature()
            click(nextFusionSlot)
            remaining--
        }

        modMessage("§aFuser: done.")
    }

    private fun timedOut(step: String) {
        modMessage("§cFuser: timed out waiting for $step.")
    }

    private fun huntingBoxAmount(screen: AbstractContainerScreen<*>, shardKeyword: String): Int? {
        val top = screen.topSlotCount()
        for (i in 0 until top) {
            val stack = screen.menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            if (!stack.hoverName.string.noControlCodes.contains(shardKeyword, ignoreCase = true)) continue
            val loreText = stack.loreString.joinToString(" ") { it.noControlCodes }
            val match = huntingBoxRegex.find(loreText) ?: continue
            return match.groupValues[1].replace(",", "").toIntOrNull()
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

    /**
     * Matches the item's own display name exactly (color-stripped, trimmed, case-insensitive) rather than a
     * substring of name+lore - confirmed live that a plain [findSlot] on "fusion"/"repeat" can match the
     * wrong slot, since another item in the same menu can also contain that word (e.g. in its own lore, or
     * as part of a longer button label) without actually being the Fusion/Repeat button itself.
     */
    private fun AbstractContainerScreen<*>.findSlotExact(name: String): Int? {
        val top = topSlotCount()
        for (i in 0 until top) {
            val stack = menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            if (stack.hoverName.string.noControlCodes.trim().equals(name, ignoreCase = true)) return i
        }
        return null
    }

    /** Bounds-checks against whatever menu is actually open at execution time, in case the screen changed underneath a queued click. */
    private fun click(slotIndex: Int, button: Int = 0, clickType: ContainerInput = ContainerInput.PICKUP) {
        mc.execute {
            val menu = mc.player?.containerMenu ?: return@execute
            if (slotIndex !in menu.slots.indices) {
                devMessage("§cFuser: skipped click on slot #$slotIndex - current menu only has ${menu.slots.size} slots (screen changed underneath us?).")
                return@execute
            }
            mc.player?.clickSlot(menu.containerId, slotIndex, button, clickType)
        }
    }

    private fun currentGuiSignature(): String? = (mc.screen as? AbstractContainerScreen<*>)?.contentSignature()

    /** Cheap content fingerprint - not identity/title, purely "what's actually in every top slot right now", since a re-sent GUI update can reuse the very same [Screen]/title and only change slot contents. */
    private fun AbstractContainerScreen<*>.contentSignature(): String {
        val top = topSlotCount()
        return (0 until top).joinToString("|") { i ->
            val stack = menu.items.getOrNull(i)
            if (stack == null || stack.isEmpty) "" else "${stack.hoverName.string}x${stack.count}#${stack.loreString.joinToString(",")}"
        }
    }

    /**
     * Waits for the open container's content to actually change away from [previousSignature] - not just
     * for *some* screen matching [predicate] to be open, since that could still be the stale pre-click one
     * (e.g. the next target item was already visible before this click's update landed). Once a genuinely
     * changed screen also matches [predicate], settles [GUI_UPDATE_SETTLE_MS] and re-checks both conditions
     * still hold before returning it - the settle exists to dodge a title-before-content race where the
     * server updates the screen's title slightly before its slot contents.
     */
    private suspend fun waitForGuiUpdate(previousSignature: String?, timeoutMs: Long = 5000, predicate: (AbstractContainerScreen<*>) -> Boolean): AbstractContainerScreen<*>? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            (mc.screen as? AbstractContainerScreen<*>)?.let {
                if (it.contentSignature() != previousSignature && predicate(it)) {
                    delay(GUI_UPDATE_SETTLE_MS)
                    val settled = mc.screen as? AbstractContainerScreen<*>
                    if (settled != null && settled.contentSignature() != previousSignature && predicate(settled)) {
                        val elapsed = System.currentTimeMillis() - start
                        devMessage("[Fuser] waitForGuiUpdate: content changed + matched after ${elapsed}ms total (incl. the ${GUI_UPDATE_SETTLE_MS}ms settle).")
                        return settled
                    }
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
        devMessage("[Fuser] waitForGuiUpdate timed out after ${timeoutMs}ms - screen at timeout: $described.")
        return null
    }

    private fun dumpScreen(screen: AbstractContainerScreen<*>, label: String) {
        val top = screen.topSlotCount()
        val text = buildString {
            append("[Fuser] ").append(label).append(" | title='").append(screen.title.string.noControlCodes).append("'\n")
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
