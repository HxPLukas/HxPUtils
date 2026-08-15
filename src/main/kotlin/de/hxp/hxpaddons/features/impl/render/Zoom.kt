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
import kotlin.math.sign

/**
 * Hold [zoomKey] to reduce FOV, release to instantly go back to normal - e.g. to double-check an ESP box is
 * actually sitting on the right entity from a distance without needing to physically walk closer. Not the
 * usual press-to-toggle keybind every other module's [key][Module.key] uses (see [de.hxp.hxpaddons.events.InputEvent],
 * fired once on press only, no release signal) - [zoomKey] is its own explicit [KeybindSetting] (same idea as
 * [de.hxp.hxpaddons.features.impl.skyblock.Combat]'s `combatKey`/`rodKey`) polled every tick via [isDown]
 * instead, so holding/releasing can actually be told apart.
 *
 * Overrides [net.minecraft.client.Camera]'s own per-frame FOV computation directly (see
 * [de.hxp.hxpaddons.mixin.mixins.CameraMixin], injecting `calculateFov`'s return value) rather than driving
 * the persisted [net.minecraft.client.Options.fov] slider - that option is backed by a plain Java `Integer`
 * clamped to vanilla's own [30, 110] range for its *codec* (irrelevant here, but the type itself can't
 * represent anything below FOV 1 meaningfully, and going lower than that on an integer scale is a dead end).
 * [Camera.calculateFov] works in raw `float`, recomputed fresh every frame, so overriding its return value
 * lets zoom go arbitrarily far in (fractional FOV, e.g. 0.5) with no such floor, and never touches - let
 * alone persists - the real FOV option at all.
 *
 * While actively zoomed, the mouse wheel is intercepted (see [de.hxp.hxpaddons.mixin.mixins.MouseHandlerMixin],
 * `onScroll`) to adjust the zoom level further instead of its usual hotbar-slot-switching behavior - scroll
 * up to zoom in further, down to zoom back out, clamped between [minFov] and [startFov]. Only intercepts
 * scroll while actually zoomed and no screen is open, so inventories/chat/the Click GUI keep scrolling
 * normally, same as when the key isn't held at all.
 */
object Zoom : Module(
    name = "Zoom",
    description = "Hold the Zoom Key to reduce FOV; scroll while held to zoom further in/out. Release to instantly go back to normal.",
    category = Category.RENDER
) {
    private val zoomKey by KeybindSetting("Zoom Key", GLFW.GLFW_KEY_UNKNOWN, desc = "Hold to zoom in, release to zoom back out.")
    private val startFov by NumberSetting("Start FOV", 20.0, 0.1, 90.0, 0.5, desc = "FOV as soon as the key is pressed, before any scrolling.")
    private val minFov by NumberSetting("Min FOV", 0.5, 0.1, 90.0, 0.1, desc = "The lowest FOV scrolling further in can reach - no floor beyond the setting's own range, unlike vanilla's FOV slider.")
    private val scrollStep by NumberSetting("Scroll Step", 1.0, 0.1, 20.0, 0.1, desc = "How much FOV changes per scroll notch.")

    private var currentFov: Float? = null
    private var wasHeld = false

    override fun onDisable() {
        super.onDisable()
        currentFov = null
        wasHeld = false
    }

    init {
        on<TickEvent.End> {
            if (!enabled) {
                currentFov = null
                wasHeld = false
                return@on
            }
            val held = zoomKey.isDown()
            if (held && !wasHeld) currentFov = startFov.toFloat()
            else if (!held && wasHeld) currentFov = null
            wasHeld = held
        }
    }

    /** Read every frame by [de.hxp.hxpaddons.mixin.mixins.CameraMixin] - null means "not zoomed, leave FOV alone". */
    @JvmStatic
    fun currentFovOverride(): Float? = currentFov

    /**
     * Called from [de.hxp.hxpaddons.mixin.mixins.MouseHandlerMixin]'s `onScroll` injection - returns true if
     * the scroll was consumed (zoom adjusted), telling the mixin to cancel vanilla's own scroll handling for
     * this event; false lets it through unchanged (not currently zoomed, or a screen is open).
     */
    @JvmStatic
    fun onScroll(deltaY: Double): Boolean {
        val fov = currentFov ?: return false
        if (mc.screen != null) return false
        val step = (sign(deltaY) * scrollStep).toFloat()
        currentFov = (fov - step).coerceIn(minFov.toFloat(), startFov.toFloat())
        return true
    }
}
