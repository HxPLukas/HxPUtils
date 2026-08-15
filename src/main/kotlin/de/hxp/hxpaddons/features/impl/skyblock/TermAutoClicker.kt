package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.itemId
import de.hxp.hxpaddons.utils.simulateLeftClick

/**
 * Ported from NoammAddons' "Term AC" (features/impl/general/TermAutoClicker.kt): while holding right
 * click with a Terminator bow in hand, simulates left-click spam at a configurable rate - same timing
 * as the original, which does NOT click at a perfectly rigid interval. Each click schedules the next one
 * at the target CPS's base delay plus a random +/-30ms offset, rather than a fixed cadence.
 */
object TermAutoClicker : Module(
    name = "Term AC",
    description = "Automatically left-click spams while holding right click with a Terminator bow.",
    category = Category.SKYBLOCK
) {
    private val cps by NumberSetting("Clicks Per Second", 5, 1, 15, 1, desc = "How many times per second the autoclicker should click.")

    private var nextLeftClick = 0L

    init {
        on<TickEvent.Start> {
            if (!enabled) return@on
            if (mc.screen != null) return@on
            val player = mc.player?.takeUnless { it.isUsingItem } ?: return@on
            val now = System.currentTimeMillis()

            if (!mc.options.keyUse.isDown) return@on
            if (player.mainHandItem.itemId != "TERMINATOR") return@on
            if (now < nextLeftClick) return@on

            nextLeftClick = getNextClick(now)
            simulateLeftClick()
        }
    }

    private fun getNextClick(now: Long): Long {
        val delay = (1000.0 / cps).toLong()
        val randomOffset = (Math.random() * 60.0 - 30.0).toLong()
        return now + delay + randomOffset
    }
}
