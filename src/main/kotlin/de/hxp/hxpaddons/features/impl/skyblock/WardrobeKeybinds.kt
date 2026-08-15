package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.WipModule
import de.hxp.hxpaddons.clickgui.settings.impl.KeybindSetting
import de.hxp.hxpaddons.events.ScreenEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.clickSlot
import de.hxp.hxpaddons.utils.sendCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.lwjgl.glfw.GLFW

/**
 * Equips a wardrobe slot from anywhere via keybind, unlike OdinFabric's WardrobeKeybinds (the mod this
 * project is forked from) and NoammAddons' equivalent, which both only react to keypresses while the
 * wardrobe screen is already open. Here, if the wardrobe isn't open yet, it's opened first (`/wardrobe`),
 * and the target slot gets equipped as soon as that screen actually appears (see [ScreenEvent.Open]).
 * Slot clicking uses the same slot-36-plus-index scheme as OdinFabric's version. Auto-closing the wardrobe
 * afterward is ported from NoammAddons' "Auto Close On Use" setting (features/impl/general/WardrobeKeybinds.kt),
 * except here it's unconditional (always closes) rather than an optional toggle.
 */
@WipModule
object WardrobeKeybinds : Module(
    name = "Wardrobe Keybinds",
    description = "Equips a wardrobe slot from anywhere - opens the wardrobe if needed, equips the slot, then closes it again.",
    category = Category.SKYBLOCK
) {
    // Regular Hypixel still titles it "Wardrobe (X/Y)" like OdinFabric/NoammAddons expect, but the Alpha
    // network titles it "(X/Y) Armor Sets" instead - matches either.
    private val wardrobeRegex = Regex("(Wardrobe \\(\\d+/\\d+\\)|\\(\\d+/\\d+\\) Armor Sets)")

    private var pendingSlot: Int? = null
    private var lastAutoCloseTime = 0L

    init {
        // Keybinds are unbound by default: 1-9 are already vanilla's hotbar-switch keys, and this feature
        // needs to work from anywhere (not just while the wardrobe is already open, unlike the reference
        // implementations), so defaulting to plain number keys would hijack normal hotbar switching.
        for (slot in 1..9) {
            registerSetting(KeybindSetting("Wardrobe Slot $slot", GLFW.GLFW_KEY_UNKNOWN, desc = "Equips wardrobe slot $slot.").onPress { equipSlot(slot) })
        }

        on<ScreenEvent.Open> {
            val containerScreen = screen as? AbstractContainerScreen<*> ?: return@on
            if (!wardrobeRegex.matches(containerScreen.title.string)) return@on

            val slot = pendingSlot
            if (slot != null) {
                pendingSlot = null
                val containerId = containerScreen.menu.containerId
                // A short grace period after the screen actually opens, before clicking - the container's
                // client-side state isn't necessarily fully settled the instant the open packet arrives.
                HxPMod.scope.launch {
                    delay(50L)
                    equipAndClose(containerId, slot)
                }
                return@on
            }

            // Hypixel sometimes reopens the wardrobe screen on its own shortly after equipping, to refresh
            // its display with the newly-equipped slot - if that happens right after we just closed it
            // ourselves, close it again instead of leaving it stuck open against what was asked for.
            if (System.currentTimeMillis() - lastAutoCloseTime < 1000L) {
                mc.setScreen(null)
                lastAutoCloseTime = System.currentTimeMillis()
            }
        }
    }

    private fun equipSlot(slot: Int) {
        if (!enabled) return

        val openScreen = mc.screen as? AbstractContainerScreen<*>
        if (openScreen != null && wardrobeRegex.matches(openScreen.title.string)) {
            equipAndClose(openScreen.menu.containerId, slot)
            return
        }

        pendingSlot = slot
        sendCommand("wardrobe")
    }

    private fun equipAndClose(containerId: Int, slot: Int) {
        mc.player?.clickSlot(containerId, 35 + slot)
        // Closing via the screen (like Escape does) rather than player.closeContainer() directly - that
        // alone doesn't dismiss the GUI Screen itself, which left the mouse stuck in GUI mode. Calling both
        // together caused the equip click to stop registering, so this handles closing on its own.
        mc.setScreen(null)
        lastAutoCloseTime = System.currentTimeMillis()
    }
}
