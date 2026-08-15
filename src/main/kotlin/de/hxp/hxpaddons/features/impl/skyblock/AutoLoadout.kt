package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.events.ScreenEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.clickSlot
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
 * settles 450ms, scrolls if needed, clicks the target slot, waits 200ms, then closes - no confirmation wait
 * on the final click, it just closes after the delay.
 *
 * Slot layout confirmed live (2026-08-15): loadouts 1-12 sit 3-per-row at slots 14/15/16, 23/24/25, 32/33/34,
 * 41/42/43 (each row +9 from the last). Loadout 13+ needs the "Next Page" button at slot 44 clicked once
 * first (13-24 then reuse the exact same 1-12 layout); further pages would need another scroll click per 12
 * loadouts, unverified since only up to 24 was confirmed.
 */
object AutoLoadout : Module(
    name = "Auto Loadout",
    description = "Equips a loadout by number via /hxp loadout <n> - opens /loadout, clicks the slot, closes it again.",
    category = Category.SKYBLOCK
) {
    private const val LOADOUTS_PER_PAGE = 12
    private const val FIRST_ROW_SLOT = 14
    private const val COLUMNS_PER_ROW = 3
    private const val SLOTS_PER_ROW_DOWN = 9
    private const val SCROLL_DOWN_SLOT = 44

    private const val GUI_UPDATE_TIMEOUT_MS = 5000L
    private const val CLOSE_DELAY_MS = 200L

    private var pendingLoadout: Int? = null
    private var job: Job? = null

    override fun onDisable() {
        super.onDisable()
        job?.cancel()
        job = null
    }

    init {
        on<ScreenEvent.Open> {
            val loadout = pendingLoadout ?: return@on
            val containerScreen = screen as? AbstractContainerScreen<*> ?: return@on
            // Doesn't consume pendingLoadout on a non-matching screen - a transitional/unrelated screen
            // opening in between shouldn't cancel waiting for the real loadout GUI.
            if (!containerScreen.title.string.noControlCodes.contains("loadout", ignoreCase = true)) return@on
            pendingLoadout = null

            job = HxPMod.scope.launch {
                runCatching {
                    delay(450L)
                    equipLoadout(loadout)
                }.onFailure {
                    HxPMod.logger.error("AutoLoadout failed", it)
                    modMessage("§cAuto Loadout: failed unexpectedly (${it.message}).")
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
        // module has no toggled=true/AlwaysActive, so without this, running the command while the module is
        // off in the Click GUI (the default state) would mean the listener above never fires.
        if (!enabled) toggle()
        pendingLoadout = loadout
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
                modMessage("§cAuto Loadout: scroll ${scrollIndex + 1}/$page didn't refresh in time, aborting.")
                return
            }
            previous = updated.contentSignature()
        }

        click(slot)
        delay(CLOSE_DELAY_MS)
        // Must run on the main/render thread - closing off-thread (this whole function runs in a coroutine)
        // skips Minecraft's normal mouse-grab handling that a real ESC/close does.
        mc.execute { mc.setScreen(null) }
    }

    /** Bounds-checks and re-fetches the container fresh at click time, same reasoning as [Fuser]'s own `click` - never reuses a containerId from an earlier, possibly already-replaced screen. */
    private fun click(slotIndex: Int) {
        mc.execute {
            val menu = mc.player?.containerMenu ?: return@execute
            if (slotIndex !in menu.slots.indices) return@execute
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
     * briefly and re-checks before returning it - only used for the scroll step, since the final equip click
     * closes unconditionally after a fixed delay instead of waiting for confirmation.
     */
    private suspend fun waitForGuiUpdate(previousSignature: String?, timeoutMs: Long = GUI_UPDATE_TIMEOUT_MS): AbstractContainerScreen<*>? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            (mc.screen as? AbstractContainerScreen<*>)?.let {
                if (it.contentSignature() != previousSignature) {
                    delay(200L)
                    val settled = mc.screen as? AbstractContainerScreen<*>
                    if (settled != null && settled.contentSignature() != previousSignature) return settled
                }
            }
            delay(50L)
        }
        return null
    }
}
