package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.events.RenderItemDecorationsEvent
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.noControlCodes
import net.minecraft.core.component.DataComponents

object Terminator : Module(
    name = "Terminator",
    description = "Detects the ultimate enchantment on a Terminator bow.",
    category = Category.SKYBLOCK
) {
    val labelColor by ColorSetting("Label Color", Color(255, 255, 255), desc = "Color of the enchant label on the Terminator.")

    private val enchantAbbrev = mapOf(
        "Duplex" to "D",
        "Soul Eater" to "SE",
        "Fatal Tempo" to "FT",
        "Swarm" to "SW",
        "Rend" to "RD",
        "Inferno" to "IF",
        "Ultimate Wise" to "UW"
    )

    private val ultimateEnchants = enchantAbbrev.keys.toList()

    private fun getEnchant(stack: net.minecraft.world.item.ItemStack): String? {
        val lore = stack.get(DataComponents.LORE) ?: return null
        return lore.lines().firstNotNullOfOrNull { line ->
            val text = line.string.noControlCodes
            ultimateEnchants.firstOrNull { text.contains(it) }
        }
    }

    init {
        on<RenderItemDecorationsEvent> {
            if (!enabled) return@on
            if (stack.isEmpty || !stack.hoverName.string.noControlCodes.contains("Terminator")) return@on
            val enchant = getEnchant(stack) ?: return@on
            val abbrev = enchantAbbrev[enchant] ?: return@on
            guiGraphics.text(mc.font, abbrev, x + 17 - mc.font.width(abbrev), y + 9, labelColor.rgba, true)
        }

    }
}
