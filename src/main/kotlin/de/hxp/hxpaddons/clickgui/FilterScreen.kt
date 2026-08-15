package de.hxp.hxpaddons.clickgui

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.features.ModuleManager
import de.hxp.hxpaddons.features.impl.general.ChatFilter
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.Color.Companion.brighter
import de.hxp.hxpaddons.utils.Color.Companion.withAlpha
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.ui.TextInputHandler
import de.hxp.hxpaddons.utils.ui.isAreaHovered
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

object FilterScreen : Screen(Component.literal("Filter Manager")) {

    // Same glass window background as ClickGUI - keeps this screen reading as part of the same UI
    // instead of a visually separate popup.
    private val windowBg = Color(17, 13, 24)

    // Base dimensions at scale 1.0 — multiplied by filterScreenScale at runtime
    private const val BASE_W        = 640f
    private const val BASE_H        = 540f
    private const val BASE_TITLE_H  = 42f
    private const val BASE_SEARCH_H = 48f
    private const val BASE_BOTTOM_H = 64f
    private const val BASE_ROW_H    = 36f
    private const val BASE_PAD      = 10f
    private const val BASE_FS       = 15f
    private const val BASE_FS_TITLE = 20f
    private const val BASE_BTN_ADD  = 80f
    private const val BASE_BTN_PRE  = 130f

    private val s get() = ChatFilter.filterScreenScale

    private val W        get() = BASE_W        * s
    private val H        get() = BASE_H        * s
    private val TITLE_H  get() = BASE_TITLE_H  * s
    private val SEARCH_H get() = BASE_SEARCH_H * s
    private val BOTTOM_H get() = BASE_BOTTOM_H * s
    private val LIST_H   get() = H - TITLE_H - SEARCH_H - BOTTOM_H
    private val ROW_H    get() = BASE_ROW_H    * s
    private val PAD      get() = BASE_PAD      * s
    private val FS       get() = BASE_FS       * s
    private val FS_TITLE get() = BASE_FS_TITLE * s
    private val BTN_ADD  get() = BASE_BTN_ADD  * s
    private val BTN_PRE  get() = BASE_BTN_PRE  * s

    private var scrollOffset = 0f

    private var searchQuery = ""
    private val searchInput = TextInputHandler(
        textProvider = { searchQuery },
        textSetter = { searchQuery = it }
    )

    private var addQuery = ""
    private val addInput = TextInputHandler(
        textProvider = { addQuery },
        textSetter = { addQuery = it }
    )

    private val displayedFilters: List<Pair<Int, String>>
        get() = if (searchQuery.isBlank()) ChatFilter.filters.mapIndexed { i, s -> i to s }
                else ChatFilter.filters.mapIndexed { i, s -> i to s }
                    .filter { (_, s) -> s.contains(searchQuery, ignoreCase = true) }

    override fun init() {
        scrollOffset = 0f
        searchQuery = ""
        addQuery = ""
        super.init()
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

            val displayed = displayedFilters
            val maxScroll = (displayed.size * ROW_H - LIST_H).coerceAtLeast(0f)
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

            val radius = ClickGUIModule.cornerRadius
            val bgAlpha = ClickGUIModule.backgroundOpacity / 100f
            val accent = ClickGUIModule.clickGUIColor

            // Dim overlay
            NVGRenderer.rect(0f, 0f, sw, sh, Color(0, 0, 0, 0.55f).rgba, 0f)

            // Window
            NVGRenderer.dropShadow(cx, cy, W, H, 24f, 4f, radius)
            NVGRenderer.rect(cx, cy, W, H, windowBg.withAlpha(bgAlpha).rgba, radius)
            NVGRenderer.hollowRect(cx, cy, W, H, 1.5f, accent.withAlpha(0.35f).rgba, radius)

            // ── Title bar ──────────────────────────────────────────
            NVGRenderer.circle(cx + PAD + 4f, cy + TITLE_H / 2f, 4f, accent.rgba)
            NVGRenderer.text("Chat Filter", cx + PAD + 16f, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE, Colors.WHITE.rgba, NVGRenderer.defaultFont)

            val closeHovered = isAreaHovered(cx + W - PAD * 2 - FS_TITLE, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE * 1.2f, FS_TITLE, true)
            NVGRenderer.text("×", cx + W - PAD - FS_TITLE, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE, if (closeHovered) Colors.MINECRAFT_RED.rgba else Colors.WHITE.rgba, NVGRenderer.defaultFont)

            NVGRenderer.line(cx, cy + TITLE_H, cx + W, cy + TITLE_H, 1f, Colors.gray38.rgba)

            // ── Search bar ─────────────────────────────────────────
            // No separate fill, same as ClickGUI's own search box - the accent outline alone marks it
            // as its own field instead of boxing it in against the glass window background.
            val searchY = cy + TITLE_H
            val srX = cx + PAD;  val srY = searchY + PAD
            val srW = W - PAD * 2;  val srH = SEARCH_H - PAD * 2
            NVGRenderer.hollowRect(srX, srY, srW, srH, 1.5f, accent.withAlpha(0.5f).rgba, 6f)

            val sInputY = srY + (srH - FS) / 2f
            if (searchQuery.isEmpty()) NVGRenderer.text("Search filters...", srX + PAD, sInputY, FS, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
            searchInput.x = srX + PAD - 2f;  searchInput.y = sInputY - 2f
            searchInput.width = srW - PAD * 2;  searchInput.height = FS + 2f
            searchInput.draw(smx, smy)

            // ── Filter list ────────────────────────────────────────
            val listStartY = cy + TITLE_H + SEARCH_H
            NVGRenderer.pushScissor(cx, listStartY, W, LIST_H)

            if (displayed.isEmpty()) {
                val msg = if (ChatFilter.filters.isEmpty()) "No active filters" else "No matches for \"$searchQuery\""
                val msgW = NVGRenderer.textWidth(msg, FS, NVGRenderer.defaultFont)
                NVGRenderer.text(msg, cx + (W - msgW) / 2f, listStartY + (LIST_H - FS) / 2f, FS, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
            } else {
                displayed.forEachIndexed { rowIdx, (_, pattern) ->
                    val ry = listStartY + rowIdx * ROW_H - scrollOffset
                    if (ry + ROW_H < listStartY || ry > listStartY + LIST_H) return@forEachIndexed

                    val riY = ry + 2f;  val riH = ROW_H - 4f
                    val rowHovered = isAreaHovered(cx + PAD, riY, W - PAD * 2, riH, true)
                            && smy >= listStartY && smy <= listStartY + LIST_H

                    NVGRenderer.rect(cx + PAD, riY, W - PAD * 2, riH, Colors.controlBg.rgba, 5f)
                    if (rowHovered) NVGRenderer.hollowRect(cx + PAD, riY, W - PAD * 2, riH, 1.5f, accent.withAlpha(0.45f).rgba, 5f)

                    val xBtnW = FS * 1.4f
                    val xBtnX = cx + W - PAD - xBtnW
                    val tvCenter = riY + (riH - FS) / 2f

                    NVGRenderer.text("×", xBtnX + (xBtnW - NVGRenderer.textWidth("×", FS, NVGRenderer.defaultFont)) / 2f,
                        tvCenter, FS, if (rowHovered) Colors.MINECRAFT_RED.rgba else Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)

                    val display = truncate(pattern, W - PAD * 3 - xBtnW - PAD)
                    NVGRenderer.text(display, cx + PAD * 2, tvCenter, FS, Colors.WHITE.rgba, NVGRenderer.defaultFont)
                }
            }
            NVGRenderer.popScissor()

            // ── Bottom bar ─────────────────────────────────────────
            // No separate panel fill - blends into the glass window background like the rest, only a
            // thin divider line marks it off from the list above.
            val bottomY = cy + H - BOTTOM_H
            NVGRenderer.line(cx, bottomY, cx + W, bottomY, 1f, Colors.gray38.rgba)

            val inputW = W - PAD * 4 - BTN_ADD - BTN_PRE
            val inputX = cx + PAD
            val addBtnX = inputX + inputW + PAD
            val preBtnX = addBtnX + BTN_ADD + PAD
            val barH = BOTTOM_H - PAD * 2;  val barY = bottomY + PAD

            // Add input
            NVGRenderer.rect(inputX, barY, inputW, barH, Colors.controlBg.rgba, 5f)
            NVGRenderer.hollowRect(inputX, barY, inputW, barH, 1.5f, accent.withAlpha(0.45f).rgba, 5f)
            val addInputY = barY + (barH - FS) / 2f
            if (addQuery.isEmpty()) NVGRenderer.text("Add pattern...", inputX + PAD, addInputY, FS, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
            addInput.x = inputX + PAD - 2f;  addInput.y = addInputY - 2f
            addInput.width = inputW - PAD * 2;  addInput.height = FS + 2f
            addInput.draw(smx, smy)

            // Add button - kept as a solid accent fill (unlike the passive inputs/rows above) since it's
            // the screen's one primary action, same as how ClickGUI itself still uses a solid accent fill
            // for its selected-category marker rather than everything being glass.
            val addHovered = isAreaHovered(addBtnX, barY, BTN_ADD, barH, true)
            NVGRenderer.rect(addBtnX, barY, BTN_ADD, barH, if (addHovered) accent.brighter(1.15f).rgba else accent.rgba, 5f)
            val addTw = NVGRenderer.textWidth("Add", FS, NVGRenderer.defaultFont)
            NVGRenderer.text("Add", addBtnX + (BTN_ADD - addTw) / 2f, barY + (barH - FS) / 2f, FS, Colors.WHITE.rgba, NVGRenderer.defaultFont)

            // Load Presets button
            val preHovered = isAreaHovered(preBtnX, barY, BTN_PRE, barH, true)
            NVGRenderer.rect(preBtnX, barY, BTN_PRE, barH, Colors.controlBg.rgba, 5f)
            if (preHovered) NVGRenderer.hollowRect(preBtnX, barY, BTN_PRE, barH, 1.5f, accent.withAlpha(0.45f).rgba, 5f)
            val preTw = NVGRenderer.textWidth("Load Presets", FS, NVGRenderer.defaultFont)
            NVGRenderer.text("Load Presets", preBtnX + (BTN_PRE - preTw) / 2f, barY + (barH - FS) / 2f, FS, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        }

        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val guiScale = ClickGUIModule.getStandardGuiScale()
        val cx = (mc.window.screenWidth / guiScale - W) / 2f
        val cy = (mc.window.screenHeight / guiScale - H) / 2f
        if (isAreaHovered(cx, cy + TITLE_H + SEARCH_H, W, LIST_H, true)) {
            val maxScroll = (displayedFilters.size * ROW_H - LIST_H).coerceAtLeast(0f)
            scrollOffset = (scrollOffset - verticalAmount.toFloat() * ROW_H).coerceIn(0f, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val guiScale = ClickGUIModule.getStandardGuiScale()
        val smx = odinMouseX / guiScale
        val smy = odinMouseY / guiScale
        val cx = (mc.window.screenWidth / guiScale - W) / 2f
        val cy = (mc.window.screenHeight / guiScale - H) / 2f

        // Close
        if (mouseButtonEvent.button() == 0 && isAreaHovered(cx + W - PAD * 2 - FS_TITLE, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE * 1.2f, FS_TITLE, true)) {
            onClose(); return true
        }

        if (searchInput.mouseClicked(smx, smy, mouseButtonEvent)) return true
        if (addInput.mouseClicked(smx, smy, mouseButtonEvent)) return true

        val inputW = W - PAD * 4 - BTN_ADD - BTN_PRE
        val addBtnX = cx + PAD + inputW + PAD
        val preBtnX = addBtnX + BTN_ADD + PAD
        val barH = BOTTOM_H - PAD * 2;  val barY = cy + H - BOTTOM_H + PAD

        if (mouseButtonEvent.button() == 0 && isAreaHovered(addBtnX, barY, BTN_ADD, barH, true)) {
            addFilter(); return true
        }
        if (mouseButtonEvent.button() == 0 && isAreaHovered(preBtnX, barY, BTN_PRE, barH, true)) {
            ChatFilter.NOAMM_PRESETS.forEach { p -> if (!ChatFilter.filters.contains(p)) ChatFilter.filters.add(p) }
            ModuleManager.saveConfigurations(); return true
        }

        val listStartY = cy + TITLE_H + SEARCH_H
        if (mouseButtonEvent.button() == 0 && smy >= listStartY && smy <= listStartY + LIST_H) {
            displayedFilters.forEachIndexed { rowIdx, (origIdx, _) ->
                val ry = listStartY + rowIdx * ROW_H - scrollOffset
                if (isAreaHovered(cx + PAD, ry + 2f, W - PAD * 2, ROW_H - 4f, true)) {
                    ChatFilter.filters.removeAt(origIdx)
                    ModuleManager.saveConfigurations()
                    scrollOffset = scrollOffset.coerceIn(0f, (ChatFilter.filters.size * ROW_H - LIST_H).coerceAtLeast(0f))
                    return true
                }
            }
        }

        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        searchInput.mouseReleased(); addInput.mouseReleased()
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (searchInput.keyTyped(characterEvent)) return true
        if (addInput.keyTyped(characterEvent)) return true
        return super.charTyped(characterEvent)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (keyEvent.key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true }
        if (keyEvent.key == GLFW.GLFW_KEY_ENTER && addQuery.isNotBlank()) {
            val consumed = addInput.keyPressed(keyEvent)
            if (consumed) { addFilter(); return true }
        }
        if (addInput.keyPressed(keyEvent)) return true
        if (searchInput.keyPressed(keyEvent)) return true
        return super.keyPressed(keyEvent)
    }

    override fun onClose() {
        ModuleManager.saveConfigurations()
        super.onClose()
    }

    override fun isPauseScreen() = false

    private fun addFilter() {
        val p = addQuery.trim()
        if (p.isNotEmpty() && !ChatFilter.filters.contains(p)) {
            ChatFilter.filters.add(p)
            ModuleManager.saveConfigurations()
            scrollOffset = (ChatFilter.filters.size * ROW_H - LIST_H).coerceAtLeast(0f)
        }
        addQuery = ""
    }

    private fun truncate(text: String, maxW: Float): String {
        if (NVGRenderer.textWidth(text, FS, NVGRenderer.defaultFont) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && NVGRenderer.textWidth("$t...", FS, NVGRenderer.defaultFont) > maxW) t = t.dropLast(1)
        return "$t..."
    }
}
