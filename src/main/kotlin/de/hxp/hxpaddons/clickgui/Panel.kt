package de.hxp.hxpaddons.clickgui

/**
 * Shared row-sizing constants used throughout the Click GUI's
 * [de.hxp.hxpaddons.clickgui.settings.RenderableSetting] implementations and
 * [de.hxp.hxpaddons.clickgui.settings.ModuleButton] - kept as their own object (rather than folded into
 * [ClickGUI]) since a couple of unrelated screens (e.g.
 * [de.hxp.hxpaddons.features.impl.dungeon.map.DungeonMapConfigScreen]) reuse [HEIGHT] as their own base
 * row height too.
 */
object Panel {
    const val WIDTH = 240f
    const val HEIGHT = 32f
}
