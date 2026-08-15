package de.hxp.hxpaddons.features.impl.dungeon.map

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.RenderableSetting
import de.hxp.hxpaddons.features.ModuleManager
import de.hxp.hxpaddons.features.impl.dungeon.DungeonMap
import de.hxp.hxpaddons.features.impl.dungeon.MapRenderData
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.Color.Companion.brighter
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.render.hollowFill
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.Door
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.DoorType
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.Room
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomComponent
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomData
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomShape
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomType
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

/**
 * Dedicated settings screen for [DungeonMap]: a rounded window (same chrome as
 * [de.hxp.hxpaddons.clickgui.FilterScreen]) with the 4 setting categories as tabs down the left side and
 * the selected category's settings listed on the right. All of [DungeonMap]'s settings are `.hide()`-d
 * from the normal ClickGUI panel - this screen (opened via its "Open Map Config" button) is the only
 * place they're edited. The window itself is anchored to the right edge of the screen and the live
 * preview to the left, so the two never overlap regardless of screen size.
 */
object DungeonMapConfigScreen : Screen(Component.literal("Dungeon Map Settings")) {
    private const val BASE_H = 460f
    private const val TITLE_H = 42f
    private const val TAB_W = 140f
    private const val PAD = 10f
    private const val ROW_GAP = 2f
    private const val FS_TITLE = 20f
    private const val FS_TAB = 16f
    private const val FS_SECTION = 15f
    private const val SECTION_HEADER_H = 26f
    private const val RIGHT_MARGIN = 40f

    private val s get() = ClickGUIModule.getStandardGuiScale()
    // Roughly half the screen, so the window reads as a real panel rather than a narrow strip - each
    // row's own width is stretched to match (see rowWidth in extractRenderState) so labels/controls
    // reach the row's true left/right edges instead of being squeezed into a fixed narrow block.
    private val W get() = (mc.window.screenWidth / s) * 0.48f
    private val H get() = BASE_H

    private var scrollOffset = 0f
    private var selectedCategory = "General"

    private val categories = listOf("General", "Rooms", "Visual", "Colors")

    /** One sub-section within a category's settings list - [label] draws a small header above it, or none if null. */
    private data class SettingGroup(val label: String?, val settingNames: List<String>)

    // Looked up by exact setting name from DungeonMap.settings - rendered here regardless of each
    // setting's own .hide()/dependency state, since this screen (not the normal ClickGUI panel) is
    // their only real home.
    private val categoryGroups: Map<String, List<SettingGroup>> = mapOf(
        "General" to listOf(
            SettingGroup(null, listOf("Hide In Boss", "Show Player Names"))
        ),
        "Rooms" to listOf(
            SettingGroup(null, listOf(
                "Room Names", "Room Secrets", "Room Name Location",
                "Uncleared Text Color", "Cleared Text Color", "Completed Text Color", "Unopened Room Text Color",
                "Checkmark", "Checkmark Location", "Checkmark Size"
            ))
        ),
        "Visual" to listOf(
            SettingGroup(null, listOf(
                "Map Text Scale", "Min Text Size", "Player Head Scale", "Player Name Scale",
                "Unopened Room Darkness", "Unopened Door Darkness", "Match Room Darkness", "Door Width", "Room Distance",
                "Entrance Head Border", "Entrance Head Border Color"
            ))
        ),
        "Colors" to listOf(
            SettingGroup("Doors", listOf("Blood Door", "Entrance Door", "Normal Door", "Opened Wither Door", "Unopened Wither Door")),
            SettingGroup("Rooms", listOf("Blood Room", "Entrance Room", "Fairy Room", "Miniboss Room", "Normal Room", "Mimic Room", "Puzzle Room", "Rare Room", "Trap Room", "Unopened Room")),
        ),
    )

    private fun groupsFor(category: String): List<SettingGroup> = categoryGroups[category].orEmpty()

    private fun settingsFor(category: String): List<RenderableSetting<*>> =
        groupsFor(category).flatMap { it.settingNames }.mapNotNull { DungeonMap.settings[it] as? RenderableSetting<*> }

    private fun totalHeight(category: String): Float {
        var h = 0f
        for (group in groupsFor(category)) {
            if (group.label != null) h += SECTION_HEADER_H
            for (name in group.settingNames) {
                val setting = DungeonMap.settings[name] as? RenderableSetting<*> ?: continue
                h += setting.getHeight() + ROW_GAP
            }
        }
        return h
    }

    override fun init() {
        scrollOffset = 0f
        super.init()
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val guiScale = s
        val smx = odinMouseX / guiScale
        val smy = odinMouseY / guiScale

        NVGPIPRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight()) {
            NVGRenderer.scale(guiScale, guiScale)
            val sw = mc.window.screenWidth / guiScale
            val sh = mc.window.screenHeight / guiScale
            val cx = sw - W - RIGHT_MARGIN
            val cy = (sh - H) / 2f

            NVGRenderer.rect(0f, 0f, sw, sh, Color(0, 0, 0, 0.6f).rgba, 0f)
            NVGRenderer.dropShadow(cx, cy, W, H, 20f, 5f, 8f)
            NVGRenderer.rect(cx, cy, W, H, Colors.gray26.rgba, 8f)

            // ── Title bar ──────────────────────────────────────────
            NVGRenderer.drawHalfRoundedRect(cx, cy, W, TITLE_H, ClickGUIModule.clickGUIColor.rgba, 8f, true)
            val titleTw = NVGRenderer.textWidth("Dungeon Map", FS_TITLE, NVGRenderer.defaultFont)
            NVGRenderer.text("Dungeon Map", cx + W / 2f - titleTw / 2f, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE, Colors.WHITE.rgba, NVGRenderer.defaultFont)

            val closeHovered = isAreaHovered(cx + W - PAD * 2 - FS_TITLE, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE * 1.2f, FS_TITLE, true)
            NVGRenderer.text("×", cx + W - PAD - FS_TITLE, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE, if (closeHovered) Colors.MINECRAFT_RED.rgba else Colors.WHITE.rgba, NVGRenderer.defaultFont)

            // ── Category tabs (left) ─────────────────────────────────
            val tabsX = cx
            val tabsY = cy + TITLE_H
            val tabH = (H - TITLE_H) / categories.size
            NVGRenderer.rect(tabsX, tabsY, TAB_W, H - TITLE_H, Colors.gray38.rgba)

            categories.forEachIndexed { i, category ->
                val ty = tabsY + i * tabH
                val selected = category == selectedCategory
                val hovered = isAreaHovered(tabsX, ty, TAB_W, tabH, true)
                if (selected) NVGRenderer.rect(tabsX, ty, TAB_W, tabH, ClickGUIModule.clickGUIColor.rgba)
                else if (hovered) NVGRenderer.rect(tabsX, ty, TAB_W, tabH, Colors.gray38.brighter(1.2f).rgba)

                val tw = NVGRenderer.textWidth(category, FS_TAB, NVGRenderer.defaultFont)
                NVGRenderer.text(category, tabsX + TAB_W / 2f - tw / 2f, ty + tabH / 2f - FS_TAB / 2f, FS_TAB, Colors.WHITE.rgba, NVGRenderer.defaultFont)
            }

            // ── Settings list (right) ────────────────────────────────
            val listX = tabsX + TAB_W
            val listY = tabsY
            val listW = W - TAB_W
            val listH = H - TITLE_H

            val maxScroll = (totalHeight(selectedCategory) - listH).coerceAtLeast(0f)
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

            NVGRenderer.pushScissor(listX, listY, listW, listH)
            // Each row is stretched to the full column width (instead of the normal ClickGUI panel's
            // fixed Panel.WIDTH) so its label sits at the true left edge and its control (color swatch,
            // slider, etc. - each draws its own control at its own right edge) reaches the true right
            // edge, rather than both being squeezed into a narrow block with dead space beside it.
            val rowWidth = listW - PAD * 2f
            var drawY = listY - scrollOffset + PAD
            for (group in groupsFor(selectedCategory)) {
                if (group.label != null) {
                    NVGRenderer.text(group.label, listX + PAD, drawY + SECTION_HEADER_H / 2f - FS_SECTION / 2f, FS_SECTION, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)
                    drawY += SECTION_HEADER_H
                }
                for (name in group.settingNames) {
                    val setting = DungeonMap.settings[name] as? RenderableSetting<*> ?: continue
                    setting.width = rowWidth
                    val height = setting.render(listX + PAD, drawY, smx, smy)
                    drawY += height + ROW_GAP
                }
            }
            NVGRenderer.popScissor()
        }

        drawPreview(context)
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    /**
     * Runs the exact same render path the live HUD uses, fed with a fabricated example dungeon (built
     * from the "Map Vorstellung" reference layout) instead of real dungeon state. Deliberately kept in
     * plain [GuiGraphicsExtractor] pixel space rather than the settings panel's NanoVG virtual-coordinate
     * space - the two regions only need to sit side by side, not share an exact pixel-aligned border.
     */
    private fun drawPreview(context: GuiGraphicsExtractor) {
        val screenW = context.guiWidth()
        val screenH = context.guiHeight()
        val boxW = (screenW * 0.34f).toInt().coerceIn(240, 480)
        val boxH = (screenH * 0.65f).toInt().coerceIn(260, 460)
        val boxX = (screenW * 0.05f).toInt()
        val boxY = (screenH - boxH) / 2

        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, Colors.gray26.rgba)
        context.hollowFill(boxX, boxY, boxW, boxH, 1, Colors.gray38)

        context.pose().pushMatrix()
        context.pose().translate((boxX + 14).toFloat(), (boxY + 14).toFloat())
        with(DungeonMap) { context.renderMap(previewData) }
        context.pose().popMatrix()
    }

    private fun windowOrigin(): Pair<Float, Float> {
        val sw = mc.window.screenWidth / s
        val sh = mc.window.screenHeight / s
        return (sw - W - RIGHT_MARGIN) to (sh - H) / 2f
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val (cx, cy) = windowOrigin()
        val listX = cx + TAB_W
        val listY = cy + TITLE_H
        val listW = W - TAB_W
        val listH = H - TITLE_H
        if (isAreaHovered(listX, listY, listW, listH, true)) {
            val maxScroll = (totalHeight(selectedCategory) - listH).coerceAtLeast(0f)
            scrollOffset = (scrollOffset - verticalAmount.toFloat() * 20f).coerceIn(0f, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val guiScale = s
        val smx = odinMouseX / guiScale
        val smy = odinMouseY / guiScale
        val (cx, cy) = windowOrigin()

        if (mouseButtonEvent.button() == 0 && isAreaHovered(cx + W - PAD * 2 - FS_TITLE, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE * 1.2f, FS_TITLE, true)) {
            onClose(); return true
        }

        val tabsY = cy + TITLE_H
        val tabH = (H - TITLE_H) / categories.size
        if (mouseButtonEvent.button() == 0) {
            categories.forEachIndexed { i, category ->
                val ty = tabsY + i * tabH
                if (isAreaHovered(cx, ty, TAB_W, tabH, true)) {
                    if (selectedCategory != category) {
                        selectedCategory = category
                        scrollOffset = 0f
                    }
                    return true
                }
            }
        }

        for (setting in settingsFor(selectedCategory)) {
            if (setting.mouseClicked(smx, smy, mouseButtonEvent)) return true
        }
        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        for (setting in settingsFor(selectedCategory)) setting.mouseReleased(mouseButtonEvent)
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        for (setting in settingsFor(selectedCategory)) if (setting.keyTyped(characterEvent)) return true
        return super.charTyped(characterEvent)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (keyEvent.key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true }
        for (setting in settingsFor(selectedCategory)) if (setting.keyPressed(keyEvent)) return true
        return super.keyPressed(keyEvent)
    }

    override fun onClose() {
        ModuleManager.saveConfigurations()
        super.onClose()
    }

    override fun isPauseScreen() = false

    // Reconstructed by hand from the "Map Vorstellung" reference screenshot plus the corrections given
    // afterward (exact connections, shapes, Default being a miniboss room, Market's L-shape, etc.), on a
    // 6-column grid. No moving player markers here - this preview is layout/color-only - except for a
    // static head icon on the Entrance tile (showEntranceIcon), purely to preview that setting group.
    private val previewData: MapRenderData by lazy { buildPreviewData() }

    private fun buildPreviewData(): MapRenderData {
        val step = 32
        fun comp(gx: Int, gz: Int) = RoomComponent(gx * step, gz * step)

        fun room(name: String, type: RoomType, cells: List<Pair<Int, Int>>, secrets: Int = 0): Room =
            Room(
                data = RoomData(name, type, emptyList(), 0, secrets, 0, RoomShape.UNKNOWN),
                roomComponents = cells.mapTo(mutableSetOf()) { (gx, gz) -> comp(gx, gz) }
            )

        // Row 0
        val mage = room("Mage", RoomType.NORMAL, listOf(0 to 0, 1 to 0)) // 2 long on the x-axis
        val sloth = room("Sloth", RoomType.NORMAL, listOf(2 to 0), secrets = 1).apply { secretsFound = 1 } // completed
        val diagonal = room("Diagonal", RoomType.NORMAL, listOf(3 to 0, 4 to 0, 5 to 0))

        // Row 1
        val lockedAway = room("Locked Away", RoomType.NORMAL, listOf(0 to 1))
        val sarcophagus = room("Sarcophagus", RoomType.NORMAL, listOf(1 to 1))
        val beams = room("Beams", RoomType.NORMAL, listOf(2 to 1)) // right of Sarcophagus
        val rails = room("Rails", RoomType.NORMAL, listOf(3 to 1, 4 to 1, 3 to 2, 4 to 2)) // true 2x2
        val green = room("", RoomType.ENTRANCE, listOf(5 to 1)) // above Default (yellow) - unlabeled

        // Row 2
        val threeWeirdos = room("Three Weirdos", RoomType.PUZZLE, listOf(0 to 2), secrets = 1)
        val trap = room("Trap", RoomType.TRAP, listOf(1 to 2), secrets = 1)
        val scaffolding = room("Scaffolding", RoomType.NORMAL, listOf(2 to 2)) // between Beams and Trap
        val default = room("Default", RoomType.CHAMPION, listOf(5 to 2)) // yellow (miniboss) room

        // Row 3-4
        val mossy = room("Mossy", RoomType.NORMAL, listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3))
        val market = room("Market", RoomType.NORMAL, listOf(4 to 3, 5 to 3, 5 to 4)) // L-shaped: 2 on top, 1 bottom-right
        val pit = room("Pit", RoomType.NORMAL, listOf(0 to 4, 1 to 4, 2 to 4, 3 to 4))
        val fairy = room("Fairy", RoomType.FAIRY, listOf(4 to 4)) // one row up from before, below Market's left part

        // Row 5 (bottom) - Trinity fills the previously-empty bottom-left corner
        val trinity = room("Trinity", RoomType.RARE, listOf(0 to 5))
        val blood = room("", RoomType.BLOOD, listOf(1 to 5)) // unlabeled
        val grassRuin = room("Grass Ruin", RoomType.NORMAL, listOf(2 to 5, 3 to 5)) // 1x2
        val longHall = room("Long Hall", RoomType.NORMAL, listOf(4 to 5)) // right of Grass Ruin
        val ticTacToe = room("Tic Tac Toe", RoomType.PUZZLE, listOf(5 to 5), secrets = 1) // bottom-right, below Market's L

        val rooms = listOf(
            mage, sloth, diagonal, lockedAway, sarcophagus, beams, rails, green,
            threeWeirdos, trap, scaffolding, default,
            mossy, market, pit, fairy,
            trinity, blood, grassRuin, longHall, ticTacToe
        )
        val enteredRoomNames = setOf(sloth.data.name, diagonal.data.name, rails.data.name, market.data.name)
        // Demo of the mimic overlay - tinted on top of Mossy's normal color, not replacing it.
        val mimicRoomNames = setOf(mossy.data.name)
        // Rails demos the real-detection Cleared color - the other entered rooms above demo the
        // Uncleared color (entered, but the map doesn't read them as cleared).
        val clearedRoomNames = setOf(rails.data.name)
        // Sloth demos the checkmark/secrets readout showing as "done" - its secretsFound == secrets above.
        val fullySecretedRoomNames = setOf(sloth.data.name)

        fun door(ax: Int, az: Int, bx: Int, bz: Int, type: DoorType = DoorType.NORMAL, opened: Boolean = true) =
            Door(comp(ax, az).blockPos, comp(ax, az), comp(bx, bz), type, opened = opened)

        // Deliberately no door between: Mossy-Market, Trap-ThreeWeirdos, Rails-Scaffolding,
        // Pit-GrassRuin, Trinity-Blood - they're plain walls despite being adjacent.
        val doors = listOf(
            door(1, 0, 2, 0),                                        // Mage -> Sloth
            door(2, 0, 2, 1),                                        // Sloth -> Beams
            door(2, 0, 3, 0),                                        // Sloth -> Diagonal
            door(0, 1, 1, 1),                                        // Locked Away -> Sarcophagus
            door(1, 1, 2, 1),                                        // Sarcophagus -> Beams
            door(2, 1, 2, 2),                                        // Beams -> Scaffolding
            door(2, 2, 1, 2),                                        // Scaffolding -> Trap
            door(0, 2, 0, 1),                                        // Three Weirdos -> Locked Away
            door(4, 0, 4, 1),                                        // Diagonal -> Rails (its middle part)
            door(3, 2, 3, 3),                                        // Rails -> Mossy
            door(4, 1, 5, 1, DoorType.ENTRANCE),                     // Green -> Rails
            door(4, 2, 4, 3, DoorType.WITHER, opened = true),        // Rails -> Market (opened wither door)
            door(4, 2, 5, 2),                                        // Rails -> Default (yellow)
            door(0, 3, 0, 4),                                        // Mossy -> Pit
            door(5, 4, 5, 5),                                        // Market -> Tic Tac Toe
            door(4, 3, 4, 4),                                        // Market (left part) -> Fairy
            door(4, 4, 4, 5, DoorType.WITHER, opened = false),       // Fairy -> Long Hall (unopened wither door)
            door(0, 4, 0, 5),                                        // Pit -> Trinity
            door(1, 5, 2, 5, DoorType.BLOOD),                        // Blood -> Grass Ruin
            door(3, 5, 4, 5, DoorType.WITHER, opened = false),       // Grass Ruin -> Long Hall (unopened wither door)
        )

        return MapRenderData(
            rooms = rooms,
            doors = doors,
            self = null,
            teammates = emptyList(),
            enteredRoomNames = enteredRoomNames,
            mimicRoomNames = mimicRoomNames,
            clearedRoomNames = clearedRoomNames,
            fullySecretedRoomNames = fullySecretedRoomNames,
            showEntranceIcon = true,
        )
    }
}
