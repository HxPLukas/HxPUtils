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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * Equips a loadout by number via `/hxp loadout <n>`: runs `/loadout`, waits for the server's screen to open,
 * settles 600ms, scrolls if needed (each scroll click waits for the GUI to actually refresh before the next
 * step, not a fixed delay - see [waitForGuiUpdate]), clicks the target slot, waits 200ms, then closes.
 * Replaces the old WardrobeKeybinds (`/wardrobe`, per-slot keybinds 1-9) - loadouts go well past 9 now, so a
 * command that takes any number fits better than a fixed set of keybind slots.
 *
 * Slot layout confirmed live (2026-08-15): loadouts 1-12 sit 3-per-row at slots 15/16/17, 24/25/26, 33/34/35,
 * 42/43/44 (each row +9 from the last). Loadout 13+ needs the "scroll down" button at slot 45 clicked once
 * first (13-24 then reuse the exact same 1-12 layout); further pages would need another scroll click per 12
 * loadouts, unverified since only up to 24 was confirmed. The GUI's own title isn't confirmed live either, so
 * this doesn't gate on it - it just trusts that whatever screen opens right after `/loadout`, while a loadout
 * number is pending, is the loadout GUI (same pattern the old WardrobeKeybinds used for `/wardrobe`, just
 * without the title check since that text hasn't been seen live for `/loadout`).
 */
object AutoLoadout : Module(
    name = "Auto Loadout",
    description = "Equips a loadout by number via /hxp loadout <n> - opens /loadout, clicks the slot, closes it again.",
    category = Category.SKYBLOCK
) {
    /** Loadouts per page before the "scroll down" button (slot [SCROLL_DOWN_SLOT]) needs clicking once more. */
    private const val LOADOUTS_PER_PAGE = 12

    /** The 3 loadout-slot columns' first row, e.g. loadout 1 (index 0) -> [FIRST_ROW_SLOT] + 0. */
    private const val FIRST_ROW_SLOT = 15
    private const val COLUMNS_PER_ROW = 3
    private const val SLOTS_PER_ROW_DOWN = 9
    private const val SCROLL_DOWN_SLOT = 45

    /** [waitForGuiUpdate]'s ceiling on how long a single scroll click gets to actually refresh the GUI. */
    private const val SCROLL_UPDATE_TIMEOUT_MS = 5000L

    private var pendingLoadout: Int? = null

    init {
        on<ScreenEvent.Open> {
            val containerScreen = screen as? AbstractContainerScreen<*> ?: return@on
            val loadout = pendingLoadout ?: return@on
            pendingLoadout = null

            devMessage("[AutoLoadout] Screen opened for /hxp loadout $loadout: '${containerScreen.title.string}'")
            val containerId = containerScreen.menu.containerId
            HxPMod.scope.launch {
                delay(600L)
                equipLoadout(containerId, loadout)
            }
        }
    }

    fun equip(loadout: Int) {
        if (loadout < 1) {
            modMessage("§cUsage: /hxp loadout <number ≥ 1>")
            return
        }
        pendingLoadout = loadout
        sendCommand("loadout")
    }

    private suspend fun equipLoadout(containerId: Int, loadout: Int) {
        val page = (loadout - 1) / LOADOUTS_PER_PAGE
        val indexInPage = (loadout - 1) % LOADOUTS_PER_PAGE
        val row = indexInPage / COLUMNS_PER_ROW
        val column = indexInPage % COLUMNS_PER_ROW
        val slot = FIRST_ROW_SLOT + row * SLOTS_PER_ROW_DOWN + column

        val player = mc.player ?: return
        repeat(page) { scrollIndex ->
            val screen = mc.screen as? AbstractContainerScreen<*> ?: return
            val beforeScroll = screen.contentSignature()
            player.clickSlot(containerId, SCROLL_DOWN_SLOT)

            // Waits for the server to actually send back the next page's contents (Hypixel refreshes this
            // GUI's slots in place rather than opening a new Screen, so ScreenEvent.Open won't fire again
            // here) before clicking again - a fixed delay would either wait longer than needed or risk
            // clicking scroll/the target slot before the previous scroll's response ever arrived.
            if (waitForGuiUpdate(beforeScroll) == null) {
                devMessage("[AutoLoadout] /hxp loadout $loadout: scroll ${scrollIndex + 1}/$page didn't refresh the GUI in time, aborting.")
                return
            }
        }

        player.clickSlot(containerId, slot)
        delay(200L)
        mc.setScreen(null)
    }

    private fun AbstractContainerScreen<*>.topSlotCount(): Int = (menu.items.size - 36).coerceAtLeast(0)

    /** Concatenation of every top-slot's name+count+lore - lets [waitForGuiUpdate] tell a genuinely-refreshed page apart from the pre-scroll one still sitting there. */
    private fun AbstractContainerScreen<*>.contentSignature(): String =
        (0 until topSlotCount()).joinToString("|") { i ->
            val stack = menu.items.getOrNull(i)
            if (stack == null || stack.isEmpty) "" else "${stack.hoverName.string}:${stack.count}:${stack.loreString.joinToString(";") { l -> l.noControlCodes }}"
        }

    /** Polls until the open screen's [contentSignature] differs from [previousSignature] (a genuine scroll update, not the same pre-click page), settles 200ms, and re-verifies before returning. */
    private suspend fun waitForGuiUpdate(previousSignature: String, timeoutMs: Long = SCROLL_UPDATE_TIMEOUT_MS): AbstractContainerScreen<*>? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val screen = mc.screen as? AbstractContainerScreen<*>
            if (screen != null && screen.contentSignature() != previousSignature) {
                delay(200L)
                val settled = mc.screen as? AbstractContainerScreen<*>
                if (settled != null && settled.contentSignature() != previousSignature) return settled
            }
            delay(50L)
        }
        return null
    }
}
