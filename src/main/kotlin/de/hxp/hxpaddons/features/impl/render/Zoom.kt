package de.hxp.hxpaddons.features.impl.render

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.impl.KeybindSetting
import de.hxp.hxpaddons.clickgui.settings.impl.KeybindSetting.Companion.isDown
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Hold [zoomKey] to reduce FOV, release to instantly go back to normal - e.g. to double-check an ESP box is
 * actually sitting on the right entity from a distance without needing to physically walk closer. Not the
 * usual press-to-toggle keybind every other module's [key][Module.key] uses (see [de.hxp.hxpaddons.events.InputEvent],
 * fired once on press only, no release signal) - [zoomKey] is its own explicit [KeybindSetting] (same idea as
 * [de.hxp.hxpaddons.features.impl.skyblock.Combat]'s `combatKey`/`rodKey`) polled every tick via
 * [isDown] instead, so holding/releasing can actually be told apart.
 *
 * Drives the same [net.minecraft.client.Options.fov] slider vanilla's own FOV option uses (restoring the
 * exact pre-zoom value the moment the key is released) rather than hooking into GameRenderer's FOV
 * computation directly - in this Minecraft version that computation doesn't even live in GameRenderer as an
 * isolated method to hook anymore, whereas driving the option itself works regardless of wherever it's
 * actually consumed.
 *
 * While actively zoomed, the mouse wheel is intercepted (see [de.hxp.hxpaddons.mixin.mixins.MouseHandlerMixin],
 * `onScroll`) to adjust the zoom level further instead of its usual hotbar-slot-switching behavior - scroll
 * up to zoom in further, down to zoom back out, clamped between [minFov] and the FOV that was active right
 * before the key was pressed. Only intercepts scroll while actually zoomed and no screen is open, so
 * inventories/chat/the Click GUI keep scrolling normally, same as when the key isn't held at all.
 */
object Zoom : Module(
    name = "Zoom",
    description = "Hold the Zoom Key to reduce FOV; scroll while held to zoom further in/out. Release to instantly go back to normal.",
    category = Category.RENDER
) {
    private val zoomKey by KeybindSetting("Zoom Key", GLFW.GLFW_KEY_UNKNOWN, desc = "Hold to zoom in, release to zoom back out.")
    private val startFov by NumberSetting("Start FOV", 30, 1, 90, 1, desc = "FOV as soon as the key is pressed, before any scrolling.")
    private val minFov by NumberSetting("Min FOV", 1, 1, 90, 1, desc = "The lowest FOV scrolling further in can reach.")
    private val scrollStep by NumberSetting("Scroll Step", 3, 1, 20, 1, desc = "How much FOV changes per scroll notch.")

    private var preZoomFov: Int? = null
    private var currentFov: Int = 0
    private var wasHeld = false

    override fun onDisable() {
        super.onDisable()
        stopZoom()
    }

    init {
        on<TickEvent.End> {
            if (!enabled) return@on
            val held = zoomKey.isDown()
            if (held && !wasHeld) startZoom()
            else if (!held && wasHeld) stopZoom()
            wasHeld = held
        }
    }

    private fun startZoom() {
        val original = mc.options.fov().get()
        preZoomFov = original
        currentFov = startFov.toInt().coerceIn(minFov.toInt(), original)
        mc.options.fov().set(currentFov)
    }

    private fun stopZoom() {
        preZoomFov?.let { mc.options.fov().set(it) }
        preZoomFov = null
        wasHeld = false
    }

    /**
     * Called from [de.hxp.hxpaddons.mixin.mixins.MouseHandlerMixin]'s `onScroll` injection - returns true if
     * the scroll was consumed (zoom adjusted), telling the mixin to cancel vanilla's own scroll handling for
     * this event; false lets it through unchanged (not currently zoomed, or a screen is open).
     */
    @JvmStatic
    fun onScroll(deltaY: Double): Boolean {
        val original = preZoomFov ?: return false
        if (mc.screen != null) return false
        val step = (sign(deltaY) * scrollStep.toDouble()).roundToInt()
        currentFov = (currentFov - step).coerceIn(minFov.toInt(), original)
        mc.options.fov().set(currentFov)
        return true
    }
}
