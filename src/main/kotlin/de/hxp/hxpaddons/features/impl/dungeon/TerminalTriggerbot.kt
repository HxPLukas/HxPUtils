package de.hxp.hxpaddons.features.impl.dungeon

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.events.TerminalEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.mixin.accessors.AbstractContainerScreenAccessor
import de.hxp.hxpaddons.utils.ServerUtils
import de.hxp.hxpaddons.utils.devMessage
import de.hxp.hxpaddons.utils.render.text
import de.hxp.hxpaddons.utils.simulateMiddleClick
import de.hxp.hxpaddons.utils.simulateRightClick
import de.hxp.hxpaddons.utils.skyblock.dungeon.terminals.TerminalTypes
import de.hxp.hxpaddons.utils.skyblock.dungeon.terminals.TerminalUtils
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.lwjgl.glfw.GLFW

/**
 * Middle clicks a terminal slot the moment your cursor hovers over one the solver has marked as
 * correct, using a real OS-level mouse click ([simulateMiddleClick]) instead of a crafted click packet.
 * On the Rubix terminal, slots showing a negative correction (-1/-2) are right clicked instead,
 * matching [de.hxp.hxpaddons.utils.skyblock.dungeon.terminals.terminalhandler.RubixHandler]'s button semantics.
 */
object TerminalTriggerbot : Module(
    name = "Terminal Triggerbot",
    description = "Middle clicks a terminal slot as soon as your cursor hovers over one the solver knows is correct.",
    category = Category.DUNGEON
) {
    val firstClickProt by NumberSetting("First Click Protection", default = 500, min = 300, max = 600, increment = 10, desc = "The amount of time after opening a terminal where clicks are blocked to prevent bans.", unit = "ms")
    val clickCooldown by NumberSetting("Click Cooldown", default = 50, min = 0, max = 100, increment = 5, desc = "Extra delay on top of your average ping between two clicks.", unit = "ms")

    private var lastClickTime = 0L
    private var pingAtOpen = 0

    val hud = +HUD(
        name = "Terminal Triggerbot",
        desc = "Shows whether the triggerbot is ready to click again. Only visible while a terminal is open.",
        toggleable = true
    ) { example ->
        val ready = when {
            example -> false
            TerminalUtils.currentTerm == null -> return@HUD 0 to 0
            else -> System.currentTimeMillis() - lastClickTime >= pingAtOpen + clickCooldown
        }

        val line = if (ready) "§aready" else "§cunready"

        text(line, 0, 0)
        mc.font.width(line) to mc.font.lineHeight
    }

    override fun onEnable() {
        super.onEnable()
        lastClickTime = 0L
    }

    init {
        on<TerminalEvent.Open> {
            val instantPing = ServerUtils.currentPing
            val averagePing = ServerUtils.averagePing
            pingAtOpen = if (instantPing - averagePing > 50) averagePing else instantPing
        }

        on<TickEvent.End> {
            if (!enabled) return@on
            val term = TerminalUtils.currentTerm ?: return@on
            if (System.currentTimeMillis() - term.timeOpened < firstClickProt) return@on

            val screen = (mc.screen as? AbstractContainerScreen<*>) as? AbstractContainerScreenAccessor ?: return@on

            val index = screen.hoveredSlot?.index ?: return@on
            if (index >= term.type.windowSize) return@on

            val rightClick = term.type == TerminalTypes.RUBIX && term.canClick(index, GLFW.GLFW_MOUSE_BUTTON_2)
            if (!rightClick && !term.canClick(index, GLFW.GLFW_MOUSE_BUTTON_3)) return@on

            val now = System.currentTimeMillis()
            val effectiveCooldown = pingAtOpen + clickCooldown
            if (now - lastClickTime < effectiveCooldown) return@on

            lastClickTime = now
            devMessage("§bTerminal Triggerbot: ${if (rightClick) "right" else "middle"} clicking slot $index.")
            if (rightClick) simulateRightClick() else simulateMiddleClick()
        }
    }
}
