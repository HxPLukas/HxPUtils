package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.clickgui.settings.WipModule
import de.hxp.hxpaddons.clickgui.settings.impl.KeybindSetting
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import org.lwjgl.glfw.GLFW

@WipModule
object Combat : Module(
    name = "Combat",
    description = "After reeling in, swaps to a combat slot, right-clicks, then swaps back to the rod.",
    category = Category.SKYBLOCK
) {
    val combatKey by KeybindSetting("Combat Slot", GLFW.GLFW_KEY_3, desc = "Key to simulate for switching to the combat slot.")
    val rodKey by KeybindSetting("Rod Slot", GLFW.GLFW_KEY_1, desc = "Key to simulate for switching back to the rod.")
    val mobHpThreshold by NumberSetting("Mob HP Threshold", default = 5_000_000, min = 0, max = 20_000_000, increment = 100_000, desc = "Auto Fish stops instead of attacking if the hooked mob's max HP is at or above this.", unit = " HP")
}
