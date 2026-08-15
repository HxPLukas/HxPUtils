package de.hxp.hxpaddons.utils.render

import net.minecraft.world.entity.Entity

/**
 * Global registry read by [de.hxp.hxpaddons.mixin.mixins.EntityRendererMixin] to force Minecraft's
 * vanilla entity-outline render pass (the same one behind the "Glowing" effect/spectator highlight) to
 * draw a chosen entity with a chosen ARGB color, without touching the real Glowing effect or scoreboard
 * teams (which is what vanilla's own outline color is normally driven by).
 */
object EntityOutlineESP {
    private val colors = HashMap<Entity, Int>()

    fun set(entity: Entity, argb: Int) {
        colors[entity] = argb
    }

    fun clear() {
        colors.clear()
    }

    @JvmStatic
    fun colorFor(entity: Entity): Int = colors[entity] ?: 0
}
