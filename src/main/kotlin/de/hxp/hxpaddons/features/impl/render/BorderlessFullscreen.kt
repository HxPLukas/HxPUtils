package de.hxp.hxpaddons.features.impl.render

import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.mixin.accessors.WindowAccessor

object BorderlessFullscreen : Module(
    name = "Borderless Fullscreen",
    description = "Replaces Minecraft's exclusive fullscreen with a borderless window. Toggle with F11.",
) {
    // Minecraft's own window field isn't assigned yet when a saved-enabled module is toggled on during
    // early startup (ModuleConfig.load runs from inside Minecraft's constructor, before getWindow() is
    // set) - the null check below is genuinely needed despite the "useless elvis" warning, since Mojang's
    // own non-null annotation on getWindow() doesn't hold true at that specific point in the lifecycle.
    @Suppress("CAST_NEVER_SUCCEEDS", "USELESS_ELVIS")
    override fun onEnable() {
        super.onEnable()
        val window = mc.window ?: return
        if (window.isFullscreen) (window as WindowAccessor).invokeSetMode()
    }

    @Suppress("CAST_NEVER_SUCCEEDS", "USELESS_ELVIS")
    override fun onDisable() {
        super.onDisable()
        val window = mc.window ?: return
        if (window.isFullscreen) (window as WindowAccessor).invokeSetMode()
    }
}
