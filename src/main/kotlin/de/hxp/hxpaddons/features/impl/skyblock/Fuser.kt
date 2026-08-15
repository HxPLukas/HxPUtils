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
 * Automates the Hunting Box "Fusion" menu flow: right-clicks the held Fusion item, picks both configured
 * shards across the follow-up menus, confirms with the result item, then clicks "Fusion" and repeats via
 * the menu's own "Repeat" button as many times as the shards on hand allow (5 of each per fuse - read from
 * the "Hunting Box: <amount>" lore line both shard items show in the very first menu).
 *
 * Reverse engineered from a description of the live flow rather than a screen dump, same as every other
 * GUI-automation module here started out (see [BazaarFlipper]'s own doc) - every screen touched is dumped
 * (item names + lore) via [devMessage], so the fastest way to fix a wrong keyword/step is: turn on Dev
 * Messages (`/hxpdev`), run `/hxp fuse run` once, and send back what got logged.
 */
object Fuser : Module(
    name = "Fuser",
    description = "Automates fusing two Hunting Box shards into a result via the Fusion menu, repeating as often as the shards on hand allow.",
    category = Category.SKYBLOCK
) {
    private const val SHARDS_PER_FUSE = 5

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

    private const val TEST_BUY_AMOUNT = 10

    private var job: Job? = null

    override fun onDisable() {
        super.onDisable()
        job?.cancel()
        job = null
    }

    /**
     * Entry point for the `/hxp fuse run` command. Accepts either `<shard 1> <shard 2> <result>` (plain
     * space-separated, for single-word item names) or `<shard 1> | <shard 2> | <result>` (for item names
     * that contain spaces themselves, e.g. "Wither Shard") - `|` in the raw args picks the latter.
     */
    fun start(rawArgs: String) {
        val parts = if (rawArgs.contains("|")) {
            rawArgs.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            rawArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        }
        if (parts.size != 3) {
            modMessage("§cFuser: usage is /hxp fuse run <shard 1> <shard 2> <result> (or, for multi-word item names, /hxp fuse run <shard 1> | <shard 2> | <result>).")
            return
        }
        if (job?.isActive == true) {
            modMessage("§eFuser is already running.")
            return
        }
        if (!enabled) toggle()

        val (shard1, shard2, result) = parts
        job = HxPMod.scope.launch {
            runCatching { runFuseCycle(shard1, shard2, result) }
                .onFailure {
                    HxPMod.logger.error("Fuser failed", it)
                    modMessage("§cFuser: failed unexpectedly (${it.message}) - see log.")
                }
        }
    }

    /**
     * Entry point for the `/hxp fuse test` command - a standalone sanity check for [waitForGuiUpdate] against a
     * much simpler real interaction than the full fuse cycle: right-click open a shop NPC, then click the
     * exact same "potato" slot [TEST_BUY_AMOUNT] times in a row. Unlike the fuse flow (a different slot/menu
     * each step), this repeatedly targets one unchanging slot in what's likely the very same [Screen] the
     * whole time - the only thing proving each click actually landed and was acted on by the server before
     * the next one fires is [contentSignature] genuinely changing (e.g. the purse/lore updating) each time,
     * exactly the mechanism this test exists to validate before trusting it for the real fuse flow.
     */
    fun startTest() {
        if (job?.isActive == true) {
            modMessage("§eFuser is already running.")
            return
        }
        if (!enabled) toggle()

        job = HxPMod.scope.launch {
            runCatching { runPotatoTest() }
                .onFailure {
                    HxPMod.logger.error("Fuser test failed", it)
                    modMessage("§cFuser test: failed unexpectedly (${it.message}) - see log.")
                }
        }
    }

    private suspend fun runPotatoTest() {
        modMessage("§7Fuser test: right-clicking to open the shop...")
        var previous = currentGuiSignature()
        simulateRightClick()

        val shopMenu = waitForGuiUpdate(previous, 5000) { it.findSlot("potato") != null } ?: run {
            modMessage("§cFuser test: no shop menu opened (or no \"potato\" item found in it) - look at the NPC before running /hxp fuse test.")
            return
        }
        dumpScreen(shopMenu, "Shop menu")

        val potatoSlot = shopMenu.findSlot("potato") ?: run {
            modMessage("§cFuser test: couldn't find \"potato\" in the shop menu.")
            return
        }

        previous = shopMenu.contentSignature()
        for (i in 1..TEST_BUY_AMOUNT) {
            click(potatoSlot)
            val updated = waitForGuiUpdate(previous, 5000) { true } ?: run {
                modMessage("§cFuser test: timed out waiting for the shop to update after buy #$i/$TEST_BUY_AMOUNT - it may not refresh its content per click, or the click didn't land.")
                return
            }
            dumpScreen(updated, "After buy #$i")
            previous = updated.contentSignature()
        }

        modMessage("§aFuser test: done - clicked \"potato\" $TEST_BUY_AMOUNT time(s), each after a confirmed content update. Check the dumps above to confirm it actually bought that many.")
    }

    private suspend fun runFuseCycle(shard1: String, shard2: String, result: String) {
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

        var remaining = minOf(amount1, amount2) / SHARDS_PER_FUSE
        if (remaining <= 0) {
            modMessage("§cFuser: not enough shards - need at least $SHARDS_PER_FUSE of both (have ${amount1}x $shard1, ${amount2}x $shard2).")
            return
        }
        modMessage("§7Fuser: ${amount1}x $shard1, ${amount2}x $shard2 on hand - running $remaining fuse(s).")

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

    /** Bounds-checks against whatever menu is actually open at execution time, same reasoning as [BazaarFlipper]'s own `click`. */
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
     * still hold before returning it - the settle exists for the same title-before-content race [BazaarFlipper]
     * documents on its own equivalent wait.
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
