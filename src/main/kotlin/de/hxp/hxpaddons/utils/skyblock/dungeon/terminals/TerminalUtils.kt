package de.hxp.hxpaddons.utils.skyblock.dungeon.terminals

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.events.ChatPacketEvent
import de.hxp.hxpaddons.events.GuiEvent
import de.hxp.hxpaddons.events.TerminalEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.core.EventPriority
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.events.core.onReceive
import de.hxp.hxpaddons.events.core.onSend
import de.hxp.hxpaddons.features.impl.dungeon.TerminalSolver
import de.hxp.hxpaddons.utils.devMessage
import de.hxp.hxpaddons.utils.skyblock.dungeon.terminals.terminalhandler.TerminalHandler
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.*
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack

object TerminalUtils {

    private val termSolverRegex = Regex("^(.{1,16}) activated a terminal! \\((\\d)/(\\d)\\)$")
    private var lastClickTime = 0L

    @JvmStatic var currentTerm: TerminalHandler? = null
        private set
    var lastTermOpened: TerminalHandler? = null
        private set

    init {
        onReceive<ClientboundOpenScreenPacket> (EventPriority.HIGHEST) {
            val windowName = title.string
            currentTerm?.let { if (!it.isClicked && it.windowCount <= 2) leftTerm() }
            val newType = TerminalTypes.entries.find { it.regex.matches(windowName) } ?: return@onReceive

            if (newType != currentTerm?.type) newType.openHandler(windowName)?.let {
                devMessage("§aNew terminal: §6${it.type.name}")
                currentTerm = it
                TerminalEvent.Open(it).postAndCatch()
                lastTermOpened = it
            }
            currentTerm?.openScreen()
        }

        on<GuiEvent.SlotUpdate> {
            currentTerm?.updateSlot(this)
        }

        onReceive<ClientboundContainerClosePacket> { leftTerm() }
        onSend<ServerboundContainerClosePacket> { leftTerm() }

        onSend<ServerboundContainerClickPacket> {
            lastClickTime = System.currentTimeMillis()
            currentTerm?.isClicked = true
        }

        on<TickEvent.End> {
            val term = currentTerm ?: return@on
            if (System.currentTimeMillis() - lastClickTime >= TerminalSolver.terminalReloadThreshold && term.isClicked) {
                val screen = (mc.screen as? AbstractContainerScreen<*>) ?: return@on
                GuiEvent.SlotUpdate(
                    screen,
                    ClientboundContainerSetSlotPacket(screen.menu.containerId, 0, term.type.windowSize - 1, ItemStack.EMPTY),
                    screen.menu
                ).postAndCatch()
                term.isClicked = false
            }
        }

        on<ChatPacketEvent> {
            termSolverRegex.find(value)?.let { message ->
                if (message.groupValues[1] == mc.player?.name?.string) lastTermOpened?.let {
                    TerminalEvent.Solve(it).postAndCatch()
                }
            }
        }

        on<GuiEvent.SlotClick> (EventPriority.HIGH) {
            lastClickTime = System.currentTimeMillis()
            currentTerm?.isClicked = true
        }

    }

    private fun leftTerm() {
        currentTerm?.let {
            devMessage("§cLeft terminal: §6${it.type.name}")
            currentTerm = null
            TerminalEvent.Close(it).postAndCatch()
        }
    }
}