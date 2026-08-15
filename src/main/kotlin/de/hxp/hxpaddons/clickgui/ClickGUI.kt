package de.hxp.hxpaddons.clickgui

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.ModuleButton
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.ModuleManager
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.Color.Companion.withAlpha
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.ui.HoverHandler
import de.hxp.hxpaddons.utils.ui.TextInputHandler
import de.hxp.hxpaddons.utils.ui.isAreaHovered
import de.hxp.hxpaddons.utils.ui.animations.EaseOutAnimation
import de.hxp.hxpaddons.utils.ui.rendering.NVGPIPRenderer
import de.hxp.hxpaddons.utils.ui.rendering.NVGRenderer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import de.hxp.hxpaddons.utils.ui.mouseX as odinMouseX
import de.hxp.hxpaddons.utils.ui.mouseY as odinMouseY

/**
 * Single centered window, three columns: categories (top-aligned, icon-badge + text) on the left,
 * the selected category's modules (name + short description + on/off switch, nothing inline-expanding
 * here anymore) in the middle, and the right-clicked module's settings in a dedicated column on the
 * right. Same window-chrome family as [FilterScreen] and
 * [de.hxp.hxpaddons.features.impl.dungeon.map.DungeonMapConfigScreen] (rounded, dimmed background,
 * drop-shadowed).
 */
object ClickGUI : Screen(Component.literal("Click GUI")) {

    val gray38 = Color(38, 38, 38)
    val gray26 = Color(26, 26, 26)
    private val windowBg = Color(17, 13, 24)

    private const val BASE_W = 880f
    private const val BASE_H = 520f
    private const val BASE_TITLE_H = 48f
    private const val BASE_SIDEBAR_W = 170f
    private const val BASE_MODLIST_W = 300f
    private const val BASE_CAT_ROW_H = 40f
    private const val BASE_PAD = 10f
    private const val BASE_FS_TITLE = 16f
    private const val BASE_FS_TAB = 15f
    private const val BASE_SEARCH_W = 200f
    private const val BASE_SEARCH_H = 26f

    /** Whether the settings column currently has anything to show - drives which of the two text scales applies. */
    private val settingsOpen get() = selectedButton != null

    private val scale get() = ClickGUIModule.windowScale / 100f

    /** Public so [ModuleButton] can size its own text the same way, without duplicating the open/closed pick. */
    val textScale get() = (if (settingsOpen) ClickGUIModule.textScale else ClickGUIModule.textScaleClosed) / 100f

    // Width stays constant (always the full sidebar+modlist+settings width) whether or not settings are
    // open - only the settings column's own content appears/disappears, the window itself doesn't resize.
    private val W get() = BASE_W * scale
    private val H get() = BASE_H * scale
    private val TITLE_H get() = BASE_TITLE_H * scale
    private val SIDEBAR_W get() = BASE_SIDEBAR_W * scale
    // With no settings column open, the module list fills all the space that column would otherwise
    // leave empty - it only narrows back down to make room once a module is selected.
    private val MODLIST_W get() = if (settingsOpen) BASE_MODLIST_W * scale else W - SIDEBAR_W
    private val CAT_ROW_H get() = BASE_CAT_ROW_H * scale
    private val PAD get() = BASE_PAD * scale
    private val FS_TITLE get() = BASE_FS_TITLE * scale * textScale
    // Category sidebar text and the search box stay tied to the window scale only - textScale
    // (module list/settings text) deliberately doesn't reach either.
    private val FS_TAB get() = BASE_FS_TAB * scale
    private val FS_SEARCH get() = BASE_FS_TAB * scale - 2f
    private val SEARCH_W get() = BASE_SEARCH_W * scale
    private val SEARCH_H get() = BASE_SEARCH_H * scale

    private var openAnim = EaseOutAnimation(500)
    private var selectedCategory: Category = Category.categories.values.first()
    private var selectedButton: ModuleButton? = null

    private var scrollOffset = 0f
    private var listContentHeight = 0f
    private var settingsScrollOffset = 0f
    private var settingsContentHeight = 0f
    // Cached from the last render pass - mouseScrolled needs the real scrollable-list height (column
    // height minus the module name/description header above it), not the whole column height, or the
    // clamp below comes out too tight and scrolling stops working.
    private var settingsMaxScroll = 0f

    private var searchQuery = ""
    private val searchInput = TextInputHandler(
        textProvider = { searchQuery },
        textSetter = { searchQuery = it }
    )

    private val moduleButtonsByCategory: Map<Category, List<ModuleButton>> =
        Category.categories.values.associateWith { category ->
            ModuleManager.modulesByCategory[category]
                ?.sortedBy { it.name }
                ?.map { ModuleButton(it) } ?: listOf()
        }

    // While actively searching, ignores the passed category entirely and searches across every category's
    // modules instead (on request - "dass er überall sucht und nicht nur in der kategorie") - with no query,
    // falls back to the normal "just this category" browsing behavior.
    private fun visibleButtons(category: Category): List<ModuleButton> {
        val source = if (searchQuery.isBlank()) moduleButtonsByCategory[category].orEmpty() else moduleButtonsByCategory.values.flatten()
        return source.filter {
            (ModuleManager.showWipModules || !it.module.isWip) && it.module.name.contains(searchQuery, true)
        }
    }

    private fun selectedSettings() =
        selectedButton?.representableSettings?.filter { it.isVisible }.orEmpty()

    private fun windowOrigin(): Pair<Float, Float> {
        val guiScale = ClickGUIModule.getStandardGuiScale()
        val sw = mc.window.screenWidth / guiScale
        val sh = mc.window.screenHeight / guiScale
        return (sw - W) / 2f to (sh - H) / 2f
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val guiScale = ClickGUIModule.getStandardGuiScale()
        val smx = odinMouseX / guiScale
        val smy = odinMouseY / guiScale

        NVGPIPRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight()) {
            NVGRenderer.scale(guiScale, guiScale)

            val sw = mc.window.screenWidth / guiScale
            val sh = mc.window.screenHeight / guiScale
            val cx = (sw - W) / 2f
            val cy = (sh - H) / 2f

            // Dim overlay - drawn before the open animation transform so it always covers the full
            // screen instantly, instead of scaling in from the window's center along with it.
            NVGRenderer.rect(0f, 0f, sw, sh, Color(0, 0, 0, 0.55f).rgba, 0f)

            if (openAnim.isAnimating()) {
                val t = openAnim.get(0f, 1f)
                val centerX = context.guiWidth().toFloat()
                val centerY = context.guiHeight().toFloat()
                NVGRenderer.translate(centerX, centerY)
                NVGRenderer.scale(t, t)
                NVGRenderer.translate(-centerX, -centerY)
            }

            val radius = ClickGUIModule.cornerRadius
            val bgAlpha = ClickGUIModule.backgroundOpacity / 100f
            val accent = ClickGUIModule.clickGUIColor

            NVGRenderer.dropShadow(cx, cy, W, H, 24f, 4f, radius)
            NVGRenderer.rect(cx, cy, W, H, windowBg.withAlpha(bgAlpha).rgba, radius)
            NVGRenderer.hollowRect(cx, cy, W, H, 1.5f, accent.withAlpha(0.35f).rgba, radius)

            // ── Title bar ──────────────────────────────────────────
            NVGRenderer.circle(cx + PAD + 4f, cy + TITLE_H / 2f, 4f, accent.rgba)
            NVGRenderer.text("HxPUtils", cx + PAD + 16f, cy + TITLE_H / 2f - FS_TITLE / 2f, FS_TITLE, Colors.WHITE.rgba, NVGRenderer.defaultFont)

            val closeSize = FS_TITLE * 1.2f
            val closeX = cx + W - PAD - closeSize
            val closeY = cy + TITLE_H / 2f - closeSize / 2f
            val closeHovered = isAreaHovered(closeX, closeY, closeSize, closeSize, true)
            val closeGlyphW = NVGRenderer.textWidth("×", FS_TITLE, NVGRenderer.defaultFont)
            NVGRenderer.text(
                "×", closeX + closeSize / 2f - closeGlyphW / 2f, cy + TITLE_H / 2f - FS_TITLE / 2f, FS_TITLE,
                if (closeHovered) Colors.MINECRAFT_RED.rgba else Colors.WHITE.rgba, NVGRenderer.defaultFont
            )

            // No separate fill - blends into the window background, the accent outline alone marks it
            // as its own field.
            val searchX = closeX - PAD - SEARCH_W
            val searchY = cy + (TITLE_H - SEARCH_H) / 2f
            NVGRenderer.hollowRect(searchX, searchY, SEARCH_W, SEARCH_H, 1.5f, accent.withAlpha(0.5f).rgba, 6f)
            val searchTextY = searchY + (SEARCH_H - FS_SEARCH) / 2f
            if (searchQuery.isEmpty())
                NVGRenderer.text("Suche...", searchX + 8f, searchTextY, FS_SEARCH, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
            searchInput.x = searchX + 6f
            searchInput.y = searchTextY - 2f
            searchInput.width = SEARCH_W - 12f
            searchInput.height = FS_SEARCH + 2f
            searchInput.draw(smx, smy)

            NVGRenderer.line(cx, cy + TITLE_H, cx + W, cy + TITLE_H, 1f, gray38.rgba)

            // ── Sidebar (categories, flush to the top) ────────────────
            val sidebarY = cy + TITLE_H
            val tabsStartY = sidebarY + PAD

            Category.categories.values.forEachIndexed { i, category ->
                val ty = tabsStartY + i * CAT_ROW_H
                val selected = category == selectedCategory
                val hovered = !selected && isAreaHovered(cx, ty, SIDEBAR_W, CAT_ROW_H, true)

                if (selected) {
                    NVGRenderer.rect(cx, ty, SIDEBAR_W, CAT_ROW_H, accent.withAlpha(0.14f).rgba)
                    NVGRenderer.rect(cx, ty, 3f, CAT_ROW_H, accent.rgba)
                } else if (hovered) {
                    NVGRenderer.rect(cx, ty, SIDEBAR_W, CAT_ROW_H, gray38.withAlpha(0.5f).rgba)
                }

                NVGRenderer.text(
                    category.name, cx + 16f, ty + CAT_ROW_H / 2f - FS_TAB / 2f, FS_TAB,
                    if (selected) Colors.WHITE.rgba else Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont
                )
            }

            NVGRenderer.line(cx + SIDEBAR_W, sidebarY, cx + SIDEBAR_W, cy + H, 1f, gray38.rgba)

            // ── Module list (middle) ──────────────────────────────────
            val listX = cx + SIDEBAR_W
            val listY = sidebarY
            val listW = MODLIST_W
            val listH = H - TITLE_H

            val maxScroll = (listContentHeight - listH).coerceAtLeast(0f)
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

            NVGRenderer.pushScissor(listX, listY, listW, listH)
            val rowWidth = listW - PAD * 2f
            var drawY = listY - scrollOffset + PAD

            val buttons = visibleButtons(selectedCategory)
            if (buttons.isEmpty()) {
                val msg = if (searchQuery.isEmpty()) "Keine Module in dieser Kategorie" else "Keine Treffer für \"$searchQuery\""
                val emptyFs = 14f * textScale
                val msgW = NVGRenderer.textWidth(msg, emptyFs, NVGRenderer.defaultFont)
                NVGRenderer.text(msg, listX + (listW - msgW) / 2f, listY + listH / 2f - emptyFs / 2f, emptyFs, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
            } else {
                for (button in buttons) {
                    button.width = rowWidth
                    button.selected = button === selectedButton
                    drawY += button.draw(listX + PAD, drawY) + 2f
                }
            }
            listContentHeight = drawY - (listY - scrollOffset + PAD) + PAD
            NVGRenderer.popScissor()

            // ── Settings column (right) ───────────────────────────────
            val settingsX = cx + SIDEBAR_W + MODLIST_W
            val settingsY = sidebarY
            val settingsW = W - SIDEBAR_W - MODLIST_W
            val settingsH = H - TITLE_H

            val module = selectedButton?.module
            // Only shown while a module's settings are actually open, rather than permanently marking
            // off a column that's empty most of the time.
            if (module != null) NVGRenderer.line(settingsX, sidebarY, settingsX, cy + H, 1f, gray38.rgba)

            NVGRenderer.pushScissor(settingsX, settingsY, settingsW, settingsH)
            if (module != null) {
                val headerX = settingsX + PAD
                val headerFs = 17f * textScale
                val descFs = 12f * textScale
                val descGap = 22f * textScale
                NVGRenderer.text(module.name, headerX, settingsY + PAD, headerFs, accent.rgba, NVGRenderer.defaultFont)
                val descArea = NVGRenderer.wrappedTextBounds(module.description, settingsW - PAD * 2f, descFs, NVGRenderer.defaultFont)
                NVGRenderer.drawWrappedString(
                    module.description, headerX, settingsY + PAD + descGap, settingsW - PAD * 2f, descFs,
                    Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont
                )
                val listTop = settingsY + PAD + descGap + (descArea[3] - descArea[1]) + PAD

                val settingsList = selectedSettings()
                settingsMaxScroll = (settingsContentHeight - (settingsY + settingsH - listTop)).coerceAtLeast(0f)
                settingsScrollOffset = settingsScrollOffset.coerceIn(0f, settingsMaxScroll)

                val rowW = settingsW - PAD * 2f
                var sy = listTop - settingsScrollOffset
                for (setting in settingsList) {
                    setting.width = rowW
                    sy += setting.render(headerX, sy, smx, smy) + 4f
                }
                settingsContentHeight = sy - (listTop - settingsScrollOffset)
            }
            NVGRenderer.popScissor()

            desc.render()
        }
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val (cx, cy) = windowOrigin()
        val listX = cx + SIDEBAR_W
        val listY = cy + TITLE_H
        val listH = H - TITLE_H

        if (isAreaHovered(listX, listY, MODLIST_W, listH, true)) {
            val maxScroll = (listContentHeight - listH).coerceAtLeast(0f)
            scrollOffset = (scrollOffset - verticalAmount.toFloat() * 24f).coerceIn(0f, maxScroll)
            return true
        }

        val settingsX = listX + MODLIST_W
        val settingsW = W - SIDEBAR_W - MODLIST_W
        if (selectedButton != null && isAreaHovered(settingsX, listY, settingsW, listH, true)) {
            settingsScrollOffset = (settingsScrollOffset - verticalAmount.toFloat() * 24f).coerceIn(0f, settingsMaxScroll)
            return true
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val guiScale = ClickGUIModule.getStandardGuiScale()
        val smx = odinMouseX / guiScale
        val smy = odinMouseY / guiScale
        val (cx, cy) = windowOrigin()

        if (searchInput.mouseClicked(smx, smy, mouseButtonEvent)) return true

        if (mouseButtonEvent.button() == 0) {
            val closeSize = FS_TITLE * 1.2f
            val closeX = cx + W - PAD - closeSize
            val closeY = cy + TITLE_H / 2f - closeSize / 2f
            if (isAreaHovered(closeX, closeY, closeSize, closeSize, true)) {
                onClose(); return true
            }

            val tabsStartY = cy + TITLE_H + PAD
            Category.categories.values.forEachIndexed { i, category ->
                val ty = tabsStartY + i * CAT_ROW_H
                if (isAreaHovered(cx, ty, SIDEBAR_W, CAT_ROW_H, true)) {
                    if (selectedCategory != category) {
                        selectedCategory = category
                        scrollOffset = 0f
                        selectedButton = null
                        settingsScrollOffset = 0f
                    }
                    return true
                }
            }
        }

        for (button in visibleButtons(selectedCategory).asReversed()) {
            if (button.mouseClicked(mouseButtonEvent)) {
                if (mouseButtonEvent.button() == 1) {
                    // Right-clicking the already-open module again closes it instead of doing nothing.
                    selectedButton = if (selectedButton === button) null else button
                    settingsScrollOffset = 0f
                }
                return true
            }
        }

        for (setting in selectedSettings()) {
            if (setting.mouseClicked(smx, smy, mouseButtonEvent)) return true
        }
        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        searchInput.mouseReleased()
        for (setting in selectedSettings()) setting.mouseReleased(mouseButtonEvent)
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (searchInput.keyTyped(characterEvent)) return true
        for (setting in selectedSettings()) {
            if (setting.keyTyped(characterEvent)) return true
        }
        return super.charTyped(characterEvent)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (searchInput.keyPressed(keyEvent)) return true
        // Checked before the Escape-closes-the-GUI fallback below, so Escape cancels an in-progress
        // keybind/setting capture instead of closing the whole window out from under it.
        for (button in visibleButtons(selectedCategory)) {
            if (button.keyPressed(keyEvent)) return true
        }
        for (setting in selectedSettings()) {
            if (setting.keyPressed(keyEvent)) return true
        }
        if (keyEvent.key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true }
        return super.keyPressed(keyEvent)
    }

    override fun init() {
        openAnim.start()
        super.init()
    }

    override fun onClose() {
        for (buttons in moduleButtonsByCategory.values) {
            for (button in buttons) {
                for (setting in button.representableSettings) {
                    if (setting is ColorSetting) setting.section = null
                    setting.listening = false
                }
            }
        }

        ModuleManager.saveConfigurations()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    private var desc = Description("", 0f, 0f, HoverHandler(150))

    /** Sets the description without creating a new data class which isn't optimal */
    fun setDescription(text: String, x: Float, y: Float, hoverHandler: HoverHandler) {
        desc.text = text
        desc.x = x
        desc.y = y
        desc.hoverHandler = hoverHandler
    }

    data class Description(var text: String, var x: Float, var y: Float, var hoverHandler: HoverHandler) {

        fun render() {
            if (text.isEmpty() || hoverHandler.percent() < 100) return
            val area = NVGRenderer.wrappedTextBounds(text, 300f, 16f, NVGRenderer.defaultFont)
            NVGRenderer.rect(x, y, area[2] - area[0] + 16f, area[3] - area[1] + 16f, gray38.rgba, 5f)
            NVGRenderer.hollowRect(
                x,
                y,
                area[2] - area[0] + 16f,
                area[3] - area[1] + 16f,
                1.5f,
                ClickGUIModule.clickGUIColor.rgba,
                5f
            )
            NVGRenderer.drawWrappedString(text, x + 8f, y + 8f, 300f, 16f, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        }
    }

    val movementImage = NVGRenderer.createImage("/assets/hxpaddons/MovementIcon.svg")
    val hueImage = NVGRenderer.createImage("/assets/hxpaddons/HueGradient.png")
    val chevronImage = NVGRenderer.createImage("/assets/hxpaddons/chevron.svg")
}
