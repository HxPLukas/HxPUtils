package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.events.ScreenEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.clickSlot
import de.hxp.hxpaddons.utils.devMessage
import de.hxp.hxpaddons.utils.loreString
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.sendCommand
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * Equips a loadout by number via `/hxp loadout <n>`: runs `/loadout`, waits for the server's screen to open,
 * settles 600ms, scrolls if needed, clicks the target slot, waits 200ms, then closes. Replaces the old
 * WardrobeKeybinds (`/wardrobe`, per-slot keybinds 1-9) - loadouts go well past 9 now, so a command that
 * takes any number fits better than a fixed set of keybind slots.
 *
 * Hypixel re-sends this GUI's contents after every single click (confirmed live) - even the very next click
 * this module fires itself, not just the scroll one - so every step here re-fetches the *current* container
 * fresh right before clicking (see [click], same reasoning as [Fuser]'s own click helper) instead of reusing
 * the containerId the screen first opened with, and waits for the content to actually change afterward (see
 * [waitForGuiUpdate]) before the next step trusts what's on screen. Reusing a stale containerId across
 * multiple clicks is exactly the kind of thing Hypixel silently drops instead of erroring, which looks like
 * "the GUI opened but nothing after that ever happens."
 *
 * Slot layout confirmed live (2026-08-15): loadouts 1-12 sit 3-per-row at slots 14/15/16, 23/24/25, 32/33/34,
 * 41/42/43 (each row +9 from the last). Loadout 13+ needs the "scroll down" button (Next Page, confirmed at
 * slot 44) clicked once
 * first (13-24 then reuse the exact same 1-12 layout); further pages would need another scroll click per 12
 * loadouts, unverified since only up to 24 was confirmed. The GUI's own title isn't confirmed live either, so
 * this doesn't gate on it - it just trusts that whatever screen opens right after `/loadout`, while a loadout
 * number is pending, is the loadout GUI. Every screen touched is dumped via [devMessage], same convention
 * [Fuser]'s own doc explains - the fastest way to fix a wrong slot/step is: turn on Dev Messages, run
 * `/hxp loadout <n>` once, and send back what got logged.
 */
object AutoLoadout : Module(
    name = "Auto Loadout",
    description = "Equips a loadout by number via /hxp loadout <n> - opens /loadout, clicks the slot, closes it again.",
    category = Category.SKYBLOCK
) {
    /** Loadouts per page before the "scroll down" button (slot [SCROLL_DOWN_SLOT]) needs clicking once more. */
    private const val LOADOUTS_PER_PAGE = 12

    /**
     * The 3 loadout-slot columns' first row, e.g. loadout 1 (index 0) -> [FIRST_ROW_SLOT] + 0.
     * Confirmed live (2026-08-15): the initially assumed 15 was off by one - slot 16 actually holds
     * "Loadout 3", not "Loadout 2", so the real first slot is 14.
     */
    private const val FIRST_ROW_SLOT = 14
    private const val COLUMNS_PER_ROW = 3
    private const val SLOTS_PER_ROW_DOWN = 9

    /** Confirmed live (2026-08-15) via a full slot dump: "Next Page" sits at 44, not the originally assumed 45 - same -1 shift as [FIRST_ROW_SLOT]. */
    private const val SCROLL_DOWN_SLOT = 44

    private const val GUI_UPDATE_TIMEOUT_MS = 5000L
    private const val GUI_UPDATE_SETTLE_MS = 200L
    private const val DEBUG_LOG_WINDOW_MS = 4000L

    private var pendingLoadout: Int? = null
    private var job: Job? = null
    private var debugWindowStartMs: Long = 0L

    override fun onDisable() {
        super.onDisable()
        job?.cancel()
        job = null
    }

    init {
        on<ScreenEvent.Open> {
            // Temporary: while pendingLoadout is set, log EVERY screen open (even non-container / non-matching
            // ones) for a few seconds after the command, so it's visible in chat that this listener is even
            // firing at all - separate from the loadout-matching logic below.
            if (pendingLoadout != null && System.currentTimeMillis() - debugWindowStartMs <= DEBUG_LOG_WINDOW_MS) {
                modMessage("§7Auto Loadout debug: screen opened -> ${screen::class.simpleName}" +
                    ((screen as? AbstractContainerScreen<*>)?.let { " title=\"${it.title.string.noControlCodes}\"" } ?: ""))
            }

            // Not currently waiting for anything - stays silent, this fires on every unrelated screen open
            // in the game.
            val loadout = pendingLoadout ?: return@on

            // Temporarily modMessage (not devMessage) for every branch here, on request, while this is
            // still being debugged live - so it's visible without needing Dev Messages on.
            val containerScreen = screen as? AbstractContainerScreen<*>
            if (containerScreen == null) {
                modMessage("§cAuto Loadout debug: a screen opened while waiting for loadout $loadout, but it's not a container screen (${screen::class.simpleName}) - ignoring, still waiting.")
                return@on
            }

            val title = containerScreen.title.string.noControlCodes
            // Doesn't consume pendingLoadout on a non-matching screen - a transitional/unrelated screen
            // opening in between shouldn't cancel waiting for the real loadout GUI.
            if (!title.contains("loadout", ignoreCase = true)) {
                modMessage("§cAuto Loadout debug: screen opened with title \"$title\" - doesn't contain \"loadout\", ignoring, still waiting.")
                return@on
            }
            pendingLoadout = null
            modMessage("§bAuto Loadout debug: accepted screen with title \"$title\".")

            dumpScreen(containerScreen, "Opened for /hxp loadout $loadout")
            logLoadoutOneSlot(containerScreen)
            job = HxPMod.scope.launch {
                runCatching {
                    delay(600L)
                    equipLoadout(loadout)
                }.onFailure {
                    HxPMod.logger.error("AutoLoadout failed", it)
                    modMessage("§cAuto Loadout: failed unexpectedly (${it.message}) - see log.")
                }
            }
        }
    }

    fun equip(loadout: Int) {
        if (loadout < 1) {
            modMessage("§cUsage: /hxp loadout <number ≥ 1>")
            return
        }
        if (job?.isActive == true) {
            modMessage("§eAuto Loadout is already running.")
            return
        }
        // The module must be enabled/subscribed to the EventBus to receive ScreenEvent.Open at all - this
        // module has no toggled=true/AlwaysActive, so without this, running the command alone (module off in
        // the Click GUI, which is the default state) meant the listener below silently never fired, not even
        // its devMessage branches. Same pattern as Fuser's own run().
        if (!enabled) toggle()
        pendingLoadout = loadout
        debugWindowStartMs = System.currentTimeMillis()
        sendCommand("loadout")
    }

    private suspend fun equipLoadout(loadout: Int) {
        val page = (loadout - 1) / LOADOUTS_PER_PAGE
        val indexInPage = (loadout - 1) % LOADOUTS_PER_PAGE
        val row = indexInPage / COLUMNS_PER_ROW
        val column = indexInPage % COLUMNS_PER_ROW
        val slot = FIRST_ROW_SLOT + row * SLOTS_PER_ROW_DOWN + column

        var previous = currentGuiSignature()
        repeat(page) { scrollIndex ->
            click(SCROLL_DOWN_SLOT)
            val updated = waitForGuiUpdate(previous) ?: run {
                modMessage("§cAuto Loadout: scroll ${scrollIndex + 1}/$page didn't refresh the GUI in time, aborting.")
                return
            }
            dumpScreen(updated, "After scroll ${scrollIndex + 1}/$page")
            previous = updated.contentSignature()
        }

        click(slot)
        val afterClick = waitForGuiUpdate(previous) ?: run {
            modMessage("§cAuto Loadout: clicking slot $slot for loadout $loadout didn't refresh the GUI in time - it may not have registered.")
            return
        }
        dumpScreen(afterClick, "After equipping loadout $loadout (slot $slot)")

        delay(GUI_UPDATE_SETTLE_MS)
        // Must run on the main/render thread - closing off-thread (this whole function runs in a coroutine)
        // skips Minecraft's normal mouse-grab handling that a real ESC/close does, which is what left the
        // mouse "stuck" behaving like the GUI was still open.
        mc.execute { mc.setScreen(null) }
        modMessage("§aAuto Loadout: equipped loadout $loadout.")
    }

    /** Bounds-checks and re-fetches the container fresh at click time, same reasoning as [Fuser]'s own `click` - never reuses a containerId from an earlier, possibly already-replaced screen. */
    private fun click(slotIndex: Int) {
        mc.execute {
            val menu = mc.player?.containerMenu ?: return@execute
            if (slotIndex !in menu.slots.indices) {
                devMessage("§cAuto Loadout: skipped click on slot #$slotIndex - current menu only has ${menu.slots.size} slots (screen changed underneath us?).")
                return@execute
            }
            mc.player?.clickSlot(menu.containerId, slotIndex)
        }
    }

    private fun currentGuiSignature(): String? = (mc.screen as? AbstractContainerScreen<*>)?.contentSignature()

    private fun AbstractContainerScreen<*>.topSlotCount(): Int = (menu.items.size - 36).coerceAtLeast(0)

    /** Cheap content fingerprint - not identity/title, purely "what's actually in every top slot right now", since a re-sent GUI update can reuse the very same [net.minecraft.client.gui.screens.Screen]/title and only change slot contents. */
    private fun AbstractContainerScreen<*>.contentSignature(): String =
        (0 until topSlotCount()).joinToString("|") { i ->
            val stack = menu.items.getOrNull(i)
            if (stack == null || stack.isEmpty) "" else "${stack.hoverName.string}:${stack.count}:${stack.loreString.joinToString(";") { l -> l.noControlCodes }}"
        }

    /**
     * Waits for the open container's content to actually change away from [previousSignature] - not just for
     * some screen to be open, since that could still be the stale pre-click one. Once changed, settles
     * [GUI_UPDATE_SETTLE_MS] and re-checks before returning it - same pattern/reasoning as [Fuser]'s own
     * equivalent wait.
     */
    private suspend fun waitForGuiUpdate(previousSignature: String?, timeoutMs: Long = GUI_UPDATE_TIMEOUT_MS): AbstractContainerScreen<*>? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            (mc.screen as? AbstractContainerScreen<*>)?.let {
                if (it.contentSignature() != previousSignature) {
                    delay(GUI_UPDATE_SETTLE_MS)
                    val settled = mc.screen as? AbstractContainerScreen<*>
                    if (settled != null && settled.contentSignature() != previousSignature) return settled
                }
            }
            delay(50L)
        }
        val finalScreen = mc.screen
        val described = when {
            finalScreen == null -> "none"
            finalScreen is AbstractContainerScreen<*> -> "${finalScreen::class.simpleName} (title='${finalScreen.title.string.noControlCodes}', changed=${finalScreen.contentSignature() != previousSignature})"
            else -> finalScreen::class.simpleName ?: "unknown"
        }
        devMessage("[AutoLoadout] waitForGuiUpdate timed out after ${timeoutMs}ms - screen at timeout: $described.")
        return null
    }

    /**
     * Temporary diagnostic (on request, printed to regular chat instead of Dev Messages so it's visible
     * without extra setup): searches *every* slot (not just the assumed top/GUI range - [topSlotCount]'s
     * "menu size - 36" formula could itself be wrong for this menu) for an item name containing "loadout"
     * (not "loadout 1" specifically anymore, in case the real label doesn't literally read that) and
     * reports every match's exact slot index + exact name, so both [FIRST_ROW_SLOT] (assumed 15) and the
     * expected label text can be checked against what's actually there.
     */
    private fun logLoadoutOneSlot(screen: AbstractContainerScreen<*>) {
        val matches = mutableListOf<String>()
        for (i in screen.menu.items.indices) {
            val stack = screen.menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val name = stack.hoverName.string.noControlCodes
            if (name.contains("loadout", ignoreCase = true)) matches += "#$i=\"$name\""
        }
        if (matches.isEmpty()) {
            modMessage("§cAuto Loadout debug: no item containing \"loadout\" anywhere in this menu (${screen.menu.items.size} slots total, topSlotCount()=${screen.topSlotCount()}).")
        } else {
            modMessage("§bAuto Loadout debug: ${matches.size} match(es) - ${matches.joinToString(", ")}")
        }
    }

    private fun dumpScreen(screen: AbstractContainerScreen<*>, label: String) {
        val top = screen.topSlotCount()
        val text = buildString {
            append("[AutoLoadout] ").append(label).append(" | title='").append(screen.title.string.noControlCodes).append("'\n")
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
