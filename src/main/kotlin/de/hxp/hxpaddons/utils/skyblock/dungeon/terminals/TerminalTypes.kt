package de.hxp.hxpaddons.utils.skyblock.dungeon.terminals

import com.github.stivais.commodore.parsers.CommandParsable
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.skyblock.dungeon.terminals.terminalhandler.*
import net.minecraft.world.item.DyeColor

@CommandParsable
enum class TerminalTypes(
    val termName: String,
    val regex: Regex,
    val windowSize: Int
) {
    PANES("Correct all the panes!", Regex("^Correct all the panes!$"), 45),
    RUBIX("Change all to same color!", Regex("^Change all to same color!$"), 45),
    NUMBERS("Click in order!", Regex("^Click in order!$"), 36),
    STARTS_WITH("What starts with: \"*\"?", Regex("^What starts with: '(\\w)'\\?$"), 45),
    SELECT("Select all the \"*\" items!", Regex("^Select all the ([\\w ]+) items!$"), 54),
    MELODY("Click the button on time!", Regex("^Click the button on time!$"), 54);

    fun openHandler(guiName: String): TerminalHandler? {
        return when (this) {
            PANES -> PanesHandler()
            RUBIX -> RubixHandler()
            NUMBERS -> NumbersHandler()
            STARTS_WITH -> StartsWithHandler(regex.find(guiName)?.groupValues?.get(1) ?: run {
                modMessage("Failed to find letter, please report this!")
                return null
            })
            SELECT -> {
                SelectAllHandler(DyeColor.entries.find {
                    it.name.replace("_", " ")
                        .equals(regex.find(guiName)?.groupValues?.get(1)?.replace("SILVER", "LIGHT GRAY"), true)
                } ?: run {
                    modMessage("Failed to find letter, please report this!")
                    return null
                })
            }
            MELODY -> MelodyHandler()
        }
    }
}
