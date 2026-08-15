package de.hxp.hxpaddons.features.impl.dungeon

import de.hxp.hxpaddons.clickgui.settings.Setting.Companion.withDependency
import de.hxp.hxpaddons.clickgui.settings.impl.*
import de.hxp.hxpaddons.events.GuiEvent
import de.hxp.hxpaddons.events.TerminalEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.mixin.accessors.AbstractContainerScreenAccessor
import de.hxp.hxpaddons.utils.Color.Companion.darker
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.skyblock.dungeon.terminals.TerminalTypes
import de.hxp.hxpaddons.utils.skyblock.dungeon.terminals.TerminalUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Ported from odtheking/Odin (BSD-3-Clause, github.com/odtheking/Odin), overlay-on-vanilla-GUI
 * render modes only ("Odin"/"Normal") -- the Custom GUI render mode and its settings were left out.
 */
object TerminalSolver : Module(
    name = "Terminal Solver",
    description = "Renders solution for terminals in floor 7.",
    category = Category.DUNGEON
) {
    private val renderType by SelectorSetting("Render type", "Odin", arrayListOf("Odin", "Normal"), desc = "How the terminal solver should render.")
    private val normalTermSize by NumberSetting("Normal Term Size", 3, 1, 5, 1, desc = "The GUI scale increase for the terminal GUI.")

    private val solverSettings by DropdownSetting("Solver Functionality")
    private val cancelToolTip by BooleanSetting("Stop Tooltips", true, desc = "Stops rendering tooltips in terminals.").withDependency { solverSettings }
    private val middleClickGUI by BooleanSetting("Middle Click GUI", true, desc = "Replaces right click with middle click in terminals.").withDependency { solverSettings }
    private val blockIncorrectClicks by BooleanSetting("Block Incorrect Clicks", true, desc = "Blocks incorrect clicks in terminals.").withDependency { solverSettings }
    private val cancelMelodySolver by BooleanSetting("Stop Melody Solver", false, desc = "Stops rendering the melody solver.").withDependency { solverSettings }
    val showNumbers by BooleanSetting("Show Numbers", true, desc = "Shows numbers in the order terminal.").withDependency { solverSettings }
    val firstClickProt by NumberSetting("First Click Protection", 500, 350, 800, 10, unit = "ms", desc = "The amount of time after opening a terminal where clicks are blocked to prevent bans (recommended value is 500 minus your ping).").withDependency { solverSettings }
    val hideClicked by BooleanSetting("Hide Clicked", false, desc = "Visually hides your first click before a gui updates instantly to improve perceived response time. Does not affect actual click time.").withDependency { solverSettings }
    val terminalReloadThreshold by NumberSetting("Resolve timeout", 600, 300, 1000, 10, unit = "ms", desc = "The amount of time before the terminal reloads after a click wasn't registered while using hide clicked.").withDependency { hideClicked && solverSettings }
    private val debug by BooleanSetting("Debug", false, desc = "Shows debug terminals.").withDependency { solverSettings }

    private val showColors by DropdownSetting("Color Settings")
    val backgroundColor by ColorSetting("Background", Colors.gray26, true, desc = "Background color of the terminal solver.").withDependency { showColors }

    val panesColor by ColorSetting("Panes", Colors.MINECRAFT_GREEN, true, desc = "Color of the panes terminal solver.").withDependency { showColors }

    val rubixColor1 by ColorSetting("Rubix 1", Colors.MINECRAFT_GREEN, true, desc = "Color of the rubix terminal solver for 1 click.").withDependency { showColors }
    val rubixColor2 by ColorSetting("Rubix 2", Colors.MINECRAFT_GREEN.darker(0.5f), true, desc = "Color of the rubix terminal solver for 2 click.").withDependency { showColors }
    val oppositeRubixColor1 by ColorSetting("Rubix -1", Colors.MINECRAFT_DARK_RED, true, desc = "Color of the rubix terminal solver for -1 click.").withDependency { showColors }
    val oppositeRubixColor2 by ColorSetting("Rubix -2", Colors.MINECRAFT_DARK_RED.darker(0.5f), true, desc = "Color of the rubix terminal solver for -2 click.").withDependency { showColors }

    val orderColor by ColorSetting("Order 1", Colors.MINECRAFT_GREEN, true, desc = "Color of the order terminal solver for 1st item.").withDependency { showColors }
    val orderColor2 by ColorSetting("Order 2", Colors.MINECRAFT_GREEN.darker(0.5f), true, desc = "Color of the order terminal solver for 2nd item.").withDependency { showColors }
    val orderColor3 by ColorSetting("Order 3", Colors.MINECRAFT_GREEN.darker(0.5f).darker(0.5f), true, desc = "Color of the order terminal solver for 3rd item.").withDependency { showColors }

    val startsWithColor by ColorSetting("Starts With", Colors.MINECRAFT_GREEN, true, desc = "Color of the starts with terminal solver.").withDependency { showColors }

    val selectColor by ColorSetting("Select", Colors.MINECRAFT_GREEN, true, desc = "Color of the select terminal solver.").withDependency { showColors }

    val melodyColumColor by ColorSetting("Melody Column", Colors.MINECRAFT_DARK_PURPLE, true, desc = "Color of the colum indicator for melody.").withDependency { showColors && !cancelMelodySolver }
    val melodyPointerColor by ColorSetting("Melody Pointer", Colors.MINECRAFT_GREEN, true, desc = "Color of the location for pressing for melody.").withDependency { showColors && !cancelMelodySolver }

    @JvmStatic val termSize get() = if (enabled && TerminalUtils.currentTerm != null) normalTermSize else 1
    private val renderMelody get() = !(cancelMelodySolver && TerminalUtils.currentTerm?.type == TerminalTypes.MELODY)

    init {
        on<GuiEvent.SlotClick> {
            val term = TerminalUtils.currentTerm ?: return@on

            if (
                System.currentTimeMillis() - term.timeOpened < firstClickProt ||
                (blockIncorrectClicks && !term.canClick(slotId, button))
            ) return@on cancel()

            if (middleClickGUI) {
                term.click(slotId, if (button == 0) GLFW.GLFW_MOUSE_BUTTON_3 else button, hideClicked && !term.isClicked)
                return@on cancel()
            }

            if (hideClicked && !term.isClicked) term.simulateClick(slotId, button)
        }

        on<GuiEvent.Render> {
            if (TerminalUtils.currentTerm == null || !renderMelody || renderType != 0) return@on

            val screen = (screen as? AbstractContainerScreen<*>) as? AbstractContainerScreenAccessor ?: return@on
            guiGraphics.fill(screen.x + 7, screen.y + 16, screen.x + screen.width - 7, screen.y + screen.height - 96, backgroundColor.rgba)
        }

        on<GuiEvent.RenderSlot> {
            if (!renderMelody) return@on
            val currentTerm = TerminalUtils.currentTerm ?: return@on

            if (slot.index <= currentTerm.type.windowSize - 1) {
                currentTerm.getSlotRendering(slot.index)?.let { (color, text) ->
                    guiGraphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color.rgba)
                    text?.let { guiGraphics.centeredText(screen.font, it, slot.x + 8, slot.y + 4, Colors.WHITE.rgba) }
                    cancel()
                }
                if (renderType == 0) cancel()
            }
        }

        on<GuiEvent.DrawTooltip> {
            if (cancelToolTip && TerminalUtils.currentTerm != null) cancel()
            this.guiGraphics.renderDebug()
        }

        on<TerminalEvent.Open> {
            mc.execute { mc.resizeGui() }
        }

        on<TerminalEvent.Close> {
            mc.execute { mc.resizeGui() }
        }
    }

    fun GuiGraphicsExtractor.renderDebug() {
        if (debug) TerminalUtils.currentTerm?.let { term ->
            val menu = (mc.screen as? AbstractContainerScreen<*>)?.menu ?: return@let
            val debugInfo = listOf(
                "§7Type: §f${term.type.name}",
                "§7Window Name: §f${mc.screen?.title?.string}",
                "§7Container ID: §f${menu.containerId}",
                "§7Time Open: §f${System.currentTimeMillis() - term.timeOpened}ms",
                "§7Is Clicked: §f${term.isClicked}",
                "§7Window Count: §f${term.windowCount}",
                "§7Solution: §f${term.solution.joinToString(", ")}",
            )

            pose().pushMatrix()
            val sf = mc.window.guiScale
            pose().scale(1f / sf, 1f / sf)
            pose().scale(3f)

            textWithWordWrap(mc.font, Component.literal(menu.items.filter { !it.isEmpty }.map { stack -> stack.hoverName.string }.toString()), 400, 0, 300, Colors.WHITE.rgba)

            debugInfo.forEachIndexed { index, line ->
                textWithWordWrap(mc.font, Component.literal(line), 5, 20 + (index * 10), 300, Colors.WHITE.rgba)
            }

            menu.items.forEachIndexed { index, stack ->
                item(stack, 5 + (index % 9) * 18, 250 + (index / 9) * 18)
                itemDecorations(mc.font, stack, 5 + (index % 9) * 18, 250 + (index / 9) * 18)
            }
            pose().popMatrix()
        }
    }
}
