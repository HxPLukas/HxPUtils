package de.hxp.hxpaddons.features.impl.render

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Toggleable zoom, e.g. to double-check an ESP box is actually sitting on the right entity from a distance
 * without needing to physically walk closer. Drives the same [net.minecraft.client.Options.fov] slider
 * vanilla's own FOV option uses (restoring the exact pre-zoom value on disable) rather than hooking into
 * GameRenderer's FOV computation directly - in this Minecraft version that computation doesn't even live in
 * GameRenderer as an isolated method to hook anymore, whereas driving the option itself works regardless of
 * wherever it's actually consumed.
 *
 * While enabled, the mouse wheel is intercepted (see [de.hxp.hxpaddons.mixin.mixins.MouseHandlerMixin],
 * `onScroll`) to adjust the zoom level instead of its usual hotbar-slot-switching behavior - scroll up to
 * zoom in further, down to zoom back out, clamped between [minFov] and the FOV that was active before Zoom
 * got enabled (zooming back "out" past that just turns Zoom back off, going back to normal FOV/scroll
 * behavior instead of ballooning past your normal field of view). Only intercepts scroll while no screen is
 * open, so inventories/chat/the Click GUI keep scrolling normally.
 */
object Zoom : Module(
    name = "Zoom",
    description = "Hold-toggle a reduced FOV; scroll while active to zoom further in/out.",
    category = Category.RENDER
) {
    private val startFov by NumberSetting("Start FOV", 30, 1, 90, 1, desc = "FOV as soon as Zoom is enabled, before any scrolling.")
    private val minFov by NumberSetting("Min FOV", 5, 1, 90, 1, desc = "The lowest FOV scrolling further in can reach.")
    private val scrollStep by NumberSetting("Scroll Step", 3, 1, 20, 1, desc = "How much FOV changes per scroll notch.")

    private var preZoomFov: Int? = null
    private var currentFov: Int = 0

    override fun onEnable() {
        super.onEnable()
        val original = mc.options.fov().get()
        preZoomFov = original
        currentFov = startFov.toInt().coerceIn(minFov.toInt(), original)
        mc.options.fov().set(currentFov)
    }

    override fun onDisable() {
        super.onDisable()
        preZoomFov?.let { mc.options.fov().set(it) }
        preZoomFov = null
    }

    /**
     * Called from [de.hxp.hxpaddons.mixin.mixins.MouseHandlerMixin]'s `onScroll` injection - returns true if
     * the scroll was consumed (zoom adjusted, or Zoom itself turned back off), telling the mixin to cancel
     * vanilla's own scroll handling for this event; false lets it through unchanged (Zoom disabled, or a
     * screen is open).
     */
    @JvmStatic
    fun onScroll(deltaY: Double): Boolean {
        if (!enabled || mc.screen != null) return false
        val original = preZoomFov ?: return false
        val step = (sign(deltaY) * scrollStep.toDouble()).roundToInt()
        val next = currentFov - step
        if (next >= original) {
            // Scrolled back out past the original FOV - just turn Zoom off instead of exceeding it.
            toggle()
            return true
        }
        currentFov = next.coerceIn(minFov.toInt(), original)
        mc.options.fov().set(currentFov)
        return true
    }
}
