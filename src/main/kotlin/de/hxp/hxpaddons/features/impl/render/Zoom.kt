package de.hxp.hxpaddons.features.impl.render

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.AlwaysActive
import de.hxp.hxpaddons.clickgui.settings.Setting.Companion.withDependency
import de.hxp.hxpaddons.clickgui.settings.WipModule
import de.hxp.hxpaddons.clickgui.settings.impl.BooleanSetting
import de.hxp.hxpaddons.clickgui.settings.impl.KeybindSetting
import de.hxp.hxpaddons.clickgui.settings.impl.KeybindSetting.Companion.isDown
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Hold [zoomKey] to reduce FOV, release to go back to normal - e.g. to double-check an ESP box is actually
 * sitting on the right entity from a distance without needing to physically walk closer.
 *
 * [zoomKey] is deliberately named literally "Keybind", not "Zoom Key" - [de.hxp.hxpaddons.clickgui.settings.ModuleButton]
 * only ever renders a module's compact keybind chip (the one visible spot in this Click GUI to actually bind
 * a key) by looking up `module.settings["Keybind"]` specifically, and separately excludes every
 * [KeybindSetting] from the expandable settings column outright. `key = null` on the [Module] constructor
 * stops [de.hxp.hxpaddons.features.ModuleManager] from *also* auto-generating its own separate "Keybind"
 * setting (a plain press-to-toggle of [enabled], no release signal) that would otherwise collide with this
 * one. `toggled = true` + [AlwaysActive] start the module enabled and subscribed from the start - `toggled`
 * alone only sets [Module.enabled]'s initial value, it does not itself subscribe to the event bus, so
 * [AlwaysActive] is required for `on<TickEvent.End>` below to ever actually fire. [zoomKey] is polled every
 * tick via [isDown] so holding/releasing can actually be told apart, unlike the discrete press-only
 * [de.hxp.hxpaddons.events.InputEvent] path.
 *
 * Overrides [net.minecraft.client.Camera]'s own per-frame FOV computation directly (see
 * [de.hxp.hxpaddons.mixin.mixins.CameraMixin], injecting `calculateFov`'s return value via [applyFov]) rather
 * than driving the persisted [net.minecraft.client.Options.fov] slider - that option is backed by a plain
 * Java `Integer` (a dead end below FOV 1), while `calculateFov` works in raw `float`, recomputed fresh every
 * frame, letting zoom go arbitrarily far in (fractional FOV) with no such floor.
 *
 * [sensitivityScale] (on request, "wenn ich weiter rein zoome weniger sensitivity habe"): scales mouse
 * sensitivity down the further in you're currently zoomed, same idea as a real camera/scope - implemented via
 * a mixin into [net.minecraft.client.MouseHandler]'s `turnPlayer` (see [de.hxp.hxpaddons.mixin.mixins.MouseHandlerMixin],
 * `hxp$scaleZoomSensitivity`) that scales the raw accumulated mouse-delta fields the method reads, computed
 * from [displayedFov] (the *actual* current visual FOV, so it tracks mid-fade too, not just the target) via
 * [sensitivityMultiplier].
 *
 * [smoothMode] (on request, "ein mode der so smooth reinzoomt als so fade in ... und wenn man die kamera
 * bewegt es expotential und wenn meine maus still ist ... dass er dann noch so weiter dreht immer weniger"):
 * bundles two effects, both off by default (snap-instant zoom, raw mouse turning) unless enabled:
 *  - FOV fades toward its target exponentially (framerate-independent, via [smoothZoomSpeed]) instead of
 *    snapping - both zooming in/out AND scroll-adjusting the zoom level while held ease smoothly, computed
 *    every frame in [applyFov] using real elapsed time, not tick/frame count.
 *  - Camera turning gets exponential smoothing *with inertia* (coasts a little after the mouse stops moving,
 *    decaying out) - rather than reimplementing that, this temporarily forces vanilla's own
 *    [net.minecraft.client.Options.smoothCamera] on for exactly as long as a zoom/fade is in progress
 *    (restoring whatever it was set to before, once fully unzoomed) - [net.minecraft.util.SmoothDouble],
 *    vanilla's backing implementation for that option, is itself an exponential-moving-average filter that
 *    naturally keeps emitting shrinking deltas for a few frames after input stops, which is exactly the
 *    "coast down" being asked for, well-tested and free instead of a custom reimplementation.
 */
@AlwaysActive
@WipModule
object Zoom : Module(
    name = "Zoom",
    key = null,
    description = "Hold this module's keybind (set via its chip in the module list) to reduce FOV; scroll while held to zoom further in/out.",
    category = Category.RENDER,
    toggled = true
) {
    private val zoomKey by KeybindSetting("Keybind", GLFW.GLFW_KEY_UNKNOWN, desc = "Hold to zoom in, release to zoom back out.")
    private val startFov by NumberSetting("Start FOV", 20.0, 0.1, 90.0, 0.5, desc = "FOV as soon as the key is pressed, before any scrolling.")
    private val minFov by NumberSetting("Min FOV", 0.5, 0.1, 90.0, 0.1, desc = "The lowest FOV scrolling further in can reach.")
    private val scrollStep by NumberSetting("Scroll Step", 1.0, 0.1, 20.0, 0.1, desc = "How much FOV changes per scroll notch.")
    private val sensitivityScale by NumberSetting(
        "Sensitivity Scaling", 50.0, 0.0, 100.0, 5.0, unit = "%",
        desc = "How much mouse sensitivity is reduced the further you're currently zoomed in - 100% scales it fully with the zoom ratio (zoomed to a third of the FOV = a third of the sensitivity), 0% leaves sensitivity untouched regardless of zoom."
    )
    private val smoothMode by BooleanSetting(
        "Smooth Mode", false,
        desc = "Fades FOV in/out exponentially instead of snapping instantly, and smooths camera turning (with a brief coast when you stop moving the mouse) while zoomed, via Minecraft's own Smooth Camera option."
    )
    private val smoothZoomSpeed by NumberSetting(
        "Smooth Zoom Speed", 8.0, 1.0, 30.0, 0.5,
        desc = "How quickly the FOV fade converges to its target - higher is snappier, lower is more gradual."
    ).withDependency { smoothMode }

    /** Non-null while the key is held: the FOV Zoom is trying to reach/hold, adjustable by scroll. Null once released. */
    private var targetZoomFov: Float? = null

    /** The actual FOV Zoom is currently outputting - equals [targetZoomFov] exactly outside [smoothMode]. Null means "not overriding at all right now". */
    private var displayedFov: Float? = null

    private var lastFrameTimeNanos = 0L
    private var heldKey = false

    private var forcedSmoothCamera = false
    private var savedSmoothCameraSetting: Boolean? = null

    override fun onDisable() {
        super.onDisable()
        resetState()
    }

    init {
        on<TickEvent.End> {
            if (!enabled) {
                resetState()
                return@on
            }

            val held = zoomKey.isDown()
            if (held && !heldKey) {
                targetZoomFov = startFov.toFloat()
                if (!smoothMode) displayedFov = startFov.toFloat()
            } else if (!held && heldKey) {
                targetZoomFov = null
                if (!smoothMode) displayedFov = null
            }
            heldKey = held

            val shouldForceSmooth = smoothMode && (targetZoomFov != null || displayedFov != null)
            if (shouldForceSmooth && !forcedSmoothCamera) {
                savedSmoothCameraSetting = mc.options.smoothCamera
                mc.options.smoothCamera = true
                forcedSmoothCamera = true
            } else if (!shouldForceSmooth && forcedSmoothCamera) {
                savedSmoothCameraSetting?.let { mc.options.smoothCamera = it }
                forcedSmoothCamera = false
                savedSmoothCameraSetting = null
            }
        }
    }

    private fun resetState() {
        targetZoomFov = null
        displayedFov = null
        heldKey = false
        lastFrameTimeNanos = 0L
        if (forcedSmoothCamera) {
            savedSmoothCameraSetting?.let { mc.options.smoothCamera = it }
            forcedSmoothCamera = false
            savedSmoothCameraSetting = null
        }
    }

    /**
     * Called from [de.hxp.hxpaddons.mixin.mixins.CameraMixin] every frame with vanilla's own freshly
     * computed FOV for that frame - returns null when Zoom shouldn't override it at all (mirrors that value
     * straight through), or the FOV to actually use otherwise.
     */
    @JvmStatic
    fun applyFov(vanillaFov: Float): Float? {
        if (!enabled) return null
        val target = targetZoomFov
        if (target == null && displayedFov == null) return null

        if (!smoothMode) return displayedFov

        val now = System.nanoTime()
        val dt = if (lastFrameTimeNanos == 0L) 0.0 else ((now - lastFrameTimeNanos) / 1_000_000_000.0).coerceIn(0.0, 0.1)
        lastFrameTimeNanos = now

        val current = displayedFov ?: vanillaFov
        val chaseTarget = target ?: vanillaFov
        val factor = if (dt <= 0.0) 0f else (1f - exp(-smoothZoomSpeed.toFloat() * dt.toFloat()))
        val next = current + (chaseTarget - current) * factor

        if (target == null && abs(next - vanillaFov) < 0.05f) {
            displayedFov = null
            return null
        }
        displayedFov = next
        return next
    }

    /** Read by [de.hxp.hxpaddons.mixin.mixins.MouseHandlerMixin]'s `turnPlayer` injection every time the mouse turns - 1.0 (no change) whenever not currently overriding the FOV at all. */
    @JvmStatic
    fun sensitivityMultiplier(): Double {
        if (!enabled) return 1.0
        val current = displayedFov ?: return 1.0
        val ratio = (current / startFov.toFloat()).toDouble().coerceIn(0.0, 1.0)
        val scale = (sensitivityScale / 100.0).coerceIn(0.0, 1.0)
        return 1.0 - scale * (1.0 - ratio)
    }

    /**
     * Called from [de.hxp.hxpaddons.mixin.mixins.MouseHandlerMixin]'s `onScroll` injection - returns true if
     * the scroll was consumed (zoom level adjusted), telling the mixin to cancel vanilla's own scroll
     * handling for this event; false lets it through unchanged (not currently zoomed, or a screen is open).
     */
    @JvmStatic
    fun onScroll(deltaY: Double): Boolean {
        val target = targetZoomFov ?: return false
        if (mc.screen != null) return false
        val step = (sign(deltaY) * scrollStep).toFloat()
        val newTarget = (target - step).coerceIn(minFov.toFloat(), startFov.toFloat())
        targetZoomFov = newTarget
        if (!smoothMode) displayedFov = newTarget
        return true
    }
}
