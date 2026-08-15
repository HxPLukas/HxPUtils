package de.hxp.hxpaddons.features.impl.render

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.AlwaysActive
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
 * actually sitting on the right entity from a distance without needing to physically walk closer.
 *
 * [zoomKey] is deliberately named literally "Keybind", not "Zoom Key" - [de.hxp.hxpaddons.clickgui.settings.ModuleButton]
 * only ever renders a module's compact keybind chip (the one visible spot in this Click GUI to actually bind
 * a key) by looking up `module.settings["Keybind"]` specifically, and separately excludes every
 * [KeybindSetting] from the expandable settings column outright ("it's already covered by the compact chip
 * below"). A [KeybindSetting] under any other name - the very first version of this, called "Zoom Key" -
 * is therefore invisible in the GUI entirely: not the chip (wrong name) and not the settings column (blanket
 * exclusion), which is exactly what got reported ("ich hab keine zoom key row"). `key = null` on the [Module]
 * constructor above stops [de.hxp.hxpaddons.features.ModuleManager] from *also* auto-generating its own
 * separate "Keybind" setting (a plain press-to-toggle of [enabled] via [de.hxp.hxpaddons.events.InputEvent] -
 * fired once on press only, no release signal, so it can't express "held" in the first place) that would
 * otherwise collide with this one and, before this fix, caused a different confusing report on its own
 * ("ich kann das feature enablen und disablen aber nicht zoomen" - the generic toggle chip got bound instead
 * of the zoom key, which only flips [enabled] and has nothing to do with actually zooming). `toggled = true`
 * starts the module enabled by default, since there's no separate "enable" step needed anymore - holding the
 * chip's bound key is the entire feature. [zoomKey] is polled every tick via [isDown] (same idea as
 * [de.hxp.hxpaddons.features.impl.skyblock.Combat]'s `combatKey`/`rodKey`) so holding/releasing can actually
 * be told apart, unlike the discrete press-only [de.hxp.hxpaddons.events.InputEvent] path.
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
 *
 * [AlwaysActive] is required here, not optional: `toggled = true` only sets [Module.enabled]'s *initial
 * value* - it does not itself subscribe this module to the event bus, which normally only happens through an
 * actual [Module.toggle] transition (see [Module.onEnable]). Without [AlwaysActive], the `on<TickEvent.End>`
 * listener below would simply never fire despite `enabled` already reading `true` from the start - silently
 * starving [zoomKey]'s poll of any tick to run on at all, which is exactly what happened before this fix
 * ("es passiert nichts wenn ich den keybind drücke").
 */
@AlwaysActive
object Zoom : Module(
    name = "Zoom",
    key = null,
    description = "Hold this module's keybind (set via its chip in the module list) to reduce FOV; scroll while held to zoom further in/out. Release to instantly go back to normal.",
    category = Category.RENDER,
    toggled = true
) {
    private val zoomKey by KeybindSetting("Keybind", GLFW.GLFW_KEY_UNKNOWN, desc = "Hold to zoom in, release to zoom back out.")
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
