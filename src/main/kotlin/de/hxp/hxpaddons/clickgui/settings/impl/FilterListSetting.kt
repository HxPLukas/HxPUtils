package de.hxp.hxpaddons.clickgui.settings.impl

import de.hxp.hxpaddons.clickgui.settings.RenderableSetting
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.Color.Companion.withAlpha
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.ui.TextInputHandler
import de.hxp.hxpaddons.utils.ui.isAreaHovered
import de.hxp.hxpaddons.utils.ui.rendering.NVGRenderer
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

class FilterListSetting(
    private val filters: MutableList<String>,
    private val onRemove: (Int) -> Unit,
) : RenderableSetting<Unit>("Filter List", "Click a filter to remove it") {

    override val default = Unit
    override var value = Unit

    private var searchQuery = ""
    private val searchInput = TextInputHandler(
        textProvider = { searchQuery },
        textSetter = { searchQuery = it }
    )

    private val displayedFilters: List<Pair<Int, String>>
        get() = if (searchQuery.isBlank()) filters.mapIndexed { i, s -> i to s }
                else filters.mapIndexed { i, s -> i to s }.filter { (_, s) -> s.contains(searchQuery, ignoreCase = true) }

    private companion object {
        const val ROW_H = 26f
        const val PAD = 5f
        const val SEARCH_H = 30f
        val RED = Color(255, 80, 80)
    }

    private fun isRowHovered(rowIdx: Int) =
        isAreaHovered(lastX + PAD, lastY + SEARCH_H + rowIdx * ROW_H, width - PAD * 2, ROW_H - 2f, true)

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)

        // Search box
        val searchRectH = SEARCH_H - PAD * 2
        NVGRenderer.rect(x + PAD, y + PAD, width - PAD * 2, searchRectH, Colors.controlBg.rgba, 4f)
        NVGRenderer.hollowRect(x + PAD, y + PAD, width - PAD * 2, searchRectH, 1.5f, ClickGUIModule.clickGUIColor.withAlpha(0.45f).rgba, 4f)

        if (searchQuery.isEmpty()) {
            NVGRenderer.text("Search...", x + PAD + 8f, y + PAD + 3f, 14f, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
        }
        searchInput.x = x + PAD + 6f
        searchInput.y = y + PAD + 2f
        searchInput.width = width - PAD * 2 - 12f
        searchInput.height = 16f
        searchInput.draw(mouseX, mouseY)

        // Filter rows
        val displayed = displayedFilters
        if (displayed.isEmpty()) {
            val msg = if (filters.isEmpty()) "No active filters" else "No matches"
            NVGRenderer.text(msg, x + PAD + 4f, y + SEARCH_H + 5f, 13f, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
            return getHeight()
        }

        displayed.forEachIndexed { rowIdx, (_, pattern) ->
            val ry = y + SEARCH_H + rowIdx * ROW_H
            val hovered = isRowHovered(rowIdx)

            NVGRenderer.rect(x + PAD, ry, width - PAD * 2, ROW_H - 2f, Colors.controlBg.rgba, 4f)
            if (hovered) NVGRenderer.hollowRect(x + PAD, ry, width - PAD * 2, ROW_H - 2f, 1.5f, ClickGUIModule.clickGUIColor.withAlpha(0.45f).rgba, 4f)

            val maxTextW = width - PAD * 2 - 22f
            NVGRenderer.text(truncate(pattern, maxTextW), x + PAD + 5f, ry + 5f, 13f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
            NVGRenderer.text("×", x + width - PAD - 15f, ry + 5f, 13f, if (hovered) RED.rgba else Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
        }

        return getHeight()
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        if (searchInput.mouseClicked(mouseX, mouseY, click)) return true
        if (click.button() != 0) return false
        val displayed = displayedFilters
        for ((rowIdx, pair) in displayed.withIndex()) {
            if (isRowHovered(rowIdx)) { onRemove(pair.first); return true }
        }
        return false
    }

    override fun mouseReleased(click: MouseButtonEvent) = searchInput.mouseReleased()

    override fun keyPressed(input: KeyEvent): Boolean = searchInput.keyPressed(input)

    override fun keyTyped(input: CharacterEvent): Boolean = searchInput.keyTyped(input)

    override fun getHeight(): Float {
        val rows = if (filters.isEmpty() || displayedFilters.isEmpty()) 1 else displayedFilters.size
        return SEARCH_H + rows * ROW_H + PAD
    }

    private fun truncate(text: String, maxW: Float): String {
        if (NVGRenderer.textWidth(text, 13f, NVGRenderer.defaultFont) <= maxW) return text
        var s = text
        while (s.isNotEmpty() && NVGRenderer.textWidth("$s...", 13f, NVGRenderer.defaultFont) > maxW) s = s.dropLast(1)
        return "$s..."
    }
}
