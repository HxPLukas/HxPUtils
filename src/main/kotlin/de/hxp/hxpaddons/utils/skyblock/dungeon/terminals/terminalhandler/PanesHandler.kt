package de.hxp.hxpaddons.utils.skyblock.dungeon.terminals.terminalhandler

import de.hxp.hxpaddons.features.impl.dungeon.TerminalSolver
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.skyblock.dungeon.terminals.TerminalTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class PanesHandler: TerminalHandler(TerminalTypes.PANES) {

    override fun solve(items: List<ItemStack>): List<Int> {
        return items.mapIndexedNotNull { index, item ->
            if (item.item == Items.RED_STAINED_GLASS_PANE) index else null
        }
    }

    override fun renderSlot(slotIndex: Int): Pair<Color, String?> = TerminalSolver.panesColor to null
}