package de.hxp.hxpaddons.features.impl.dungeon

import de.hxp.hxpaddons.clickgui.settings.impl.ActionSetting
import de.hxp.hxpaddons.clickgui.settings.impl.BooleanSetting
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.HUDSetting
import de.hxp.hxpaddons.clickgui.settings.impl.HudElement
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.clickgui.settings.impl.SelectorSetting
import de.hxp.hxpaddons.clickgui.settings.WipModule
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.features.impl.dungeon.map.DungeonMapConfigScreen
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.Color.Companion.darker
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.network.DiscordLogger
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.render.hollowFill
import de.hxp.hxpaddons.utils.render.playerHeadIcon
import de.hxp.hxpaddons.utils.render.text
import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonUtils
import de.hxp.hxpaddons.utils.skyblock.dungeon.MapColorScanner
import de.hxp.hxpaddons.utils.skyblock.dungeon.Vec2i
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.Door
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.DoorType
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.Room
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomComponent
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomData
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomShape
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomType
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.player.PlayerSkin
import org.joml.Matrix3x2f
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A position+facing+appearance to draw as a head marker - decoupled from a real [net.minecraft.world.entity.Entity] so the same rendering path can be driven by either live teammates or synthetic preview data. */
data class MapActor(val x: Double, val z: Double, val yaw: Float, val skin: PlayerSkin?, val name: String?, val color: Color)

/**
 * Everything [DungeonMap.renderMap] needs to draw one frame of the map - built from live [DungeonUtils]
 * state for the real HUD, or from fabricated data for [DungeonMapConfigScreen]'s live preview.
 *
 * @param enteredRoomNames names of rooms actually walked into - anything else in [rooms] is drawn as
 * "unopened" (see [DungeonMap]'s Unopened Room / Unopened Room Darkness settings), since a room whose
 * tiles are merely known from the proactive nearby-scan isn't necessarily cleared yet.
 * @param mimicRoomNames names of rooms known to contain the mimic - drawn as a translucent overlay tint
 * on top of the room's real color rather than replacing it (mimic isn't a room type of its own).
 * @param clearedRoomNames names of rooms currently reading as cleared on the real dungeon map item (see
 * [de.hxp.hxpaddons.utils.skyblock.dungeon.MapColorScanner]) - drawn in the Cleared text/checkmark color.
 * Everything else shows Uncleared, regardless of whether it's ever been entered or how many secrets have
 * been found in it - this is real detection, not a guess from our own tracked state.
 * @param fullySecretedRoomNames names of rooms whose checkmark icon (all known secrets found) is
 * currently showing on the real dungeon map item - also real detection, drives whether the room's own
 * checkmark/secrets readout counts as "done" (see [DungeonMap]'s Checkmark/Room Secrets settings).
 * @param showEntranceIcon whether the Entrance room's tile draws a static head marker in place of its
 * name/checkmark. Only [DungeonMapConfigScreen]'s preview sets this - the live map already shows the
 * player's real position via [self]/[teammates] as they move, so a second, static head glued to the
 * Entrance tile there would just be a stale duplicate once someone leaves the room.
 */
class MapRenderData(
    val rooms: Collection<Room>,
    val doors: Collection<Door>,
    val self: MapActor?,
    val teammates: Collection<MapActor> = emptyList(),
    val enteredRoomNames: Set<String> = emptySet(),
    val mimicRoomNames: Set<String> = emptySet(),
    val clearedRoomNames: Set<String> = emptySet(),
    val fullySecretedRoomNames: Set<String> = emptySet(),
    val showEntranceIcon: Boolean = false,
)

// Not private: MapRenderData's render path (in DungeonMap.kt) exposes this type across files, so it
// can't be more restrictive than that.
enum class RoomVisualState { UNCLEARED, CLEARED, COMPLETED }

@WipModule
object DungeonMap : Module(
    name = "Dungeon Map",
    description = "Shows a live map of the dungeon: rooms, doors, teammates, and secrets progress.",
    category = Category.DUNGEON
) {
    // All settings below are hidden from the normal ClickGUI panel (.hide()) - only the "Open Map
    // Config" button shows there. They're still registered (so they save/load normally) and read
    // directly by name from DungeonMapConfigScreen, which is the only place they're actually editable.

    // ── General ──────────────────────────────────────────────────────────────────────────────
    private val hideInBoss by BooleanSetting("Hide In Boss", true, desc = "Hides the map while in the dungeon boss fight.").hide()
    private val showPlayerNames by SelectorSetting("Show Player Names", "When Holding Leap", listOf("Always", "When Holding Leap", "Never"), desc = "When to draw teammate name labels next to their head markers.").hide()
    // Testing toggle for MapColorScanner.useExactCenterCheckmark - lets the two candidate cleared/secreted
    // checkmark pixel positions be A/B tested live in-game (see that flag's own doc) instead of guessed at.
    private val useNoammCheckmarkPixel by BooleanSetting("Use Noamm Checkmark Position", false, desc = "Samples the cleared/secreted checkmark at the exact block center (NoammAddons' convention) instead of our own offset point - for testing which one is actually correct.").hide()

    // ── Rooms ────────────────────────────────────────────────────────────────────────────────
    private val roomNames by SelectorSetting("Room Names", "All", listOf("All", "Trap/Puzzles", "None"), desc = "Which rooms get their name drawn on their tile.").hide()
    private val roomSecrets by SelectorSetting("Room Secrets", "Off", listOf("Off", "On", "Replace Checkmark"), desc = "Whether to show a room's secrets found/total, and whether that replaces its checkmark.").hide()
    private val roomNameLocation by NumberSetting("Room Name Location", default = 0f, min = -1f, max = 1f, increment = 0.05f, desc = "Vertical position of the room name within its tile: -1 top, 0 middle, 1 bottom.").hide()
    private val unclearedTextColor by ColorSetting("Uncleared Text Color", Color("847A7AFF"), false, desc = "Room name/checkmark color for rooms you haven't entered yet.").hide()
    private val clearedTextColor by ColorSetting("Cleared Text Color", Color("CEC8C8FF"), false, desc = "Room name/checkmark color for rooms currently reading as cleared on the map.").hide()
    private val completedTextColor by ColorSetting("Completed Text Color", Color(0, 200, 0), false, desc = "Room name/checkmark color for rooms currently reading as fully secreted on the map.").hide()
    private val unopenedTextColorChoice by SelectorSetting("Unopened Room Text Color", "Cleared", listOf("Uncleared", "Cleared"), desc = "Which of the two colors above an unopened room's name/checkmark uses - always shown either way.").hide()
    private val checkmarkStyle by SelectorSetting("Checkmark", "Off", listOf("Below Name", "Behind Name", "Off"), desc = "Where the room's checkmark (reflecting its uncleared/cleared/completed state) is drawn relative to its name, or Off to hide it.").hide()
    private val checkmarkLocation by NumberSetting("Checkmark Location", default = 0f, min = -1f, max = 1f, increment = 0.05f, desc = "Vertical position of the room checkmark within its tile: -1 top, 0 middle, 1 bottom.").hide()
    private val checkmarkScale by NumberSetting("Checkmark Size", default = 1.95f, min = 0.5f, max = 2f, increment = 0.05f, desc = "Extra scale applied to the standalone room checkmark, on top of Map Text Scale.").hide()

    // ── Visual ───────────────────────────────────────────────────────────────────────────────
    private val mapTextScale by NumberSetting("Map Text Scale", default = 0.95f, min = 0.5f, max = 2f, increment = 0.05f, desc = "Extra scale applied to room name text.").hide()
    private val minTextSize by NumberSetting("Min Text Size", default = 0.5f, min = 0.1f, max = 1f, increment = 0.05f, desc = "Smallest a room name is allowed to shrink to fit its tile - past this, it spills out past the room's edge instead of shrinking further.").hide()
    private val playerHeadScale by NumberSetting("Player Head Scale", default = 1f, min = 0.5f, max = 2f, increment = 0.05f, desc = "Scale of player head markers.").hide()
    private val playerNameScale by NumberSetting("Player Name Scale", default = 1f, min = 0.5f, max = 2f, increment = 0.05f, desc = "Scale of player names, relative to head size.").hide()
    private val unopenedRoomDarkness by NumberSetting("Unopened Room Darkness", default = 59, min = 1, max = 100, desc = "How much darker unopened (not yet entered) rooms are drawn.", unit = "%").hide()
    private val unopenedDoorDarkness by NumberSetting("Unopened Door Darkness", default = 40, min = 1, max = 100, desc = "How much darker doors leading to an unopened (not yet entered) room are drawn. Ignored if Match Room Darkness is enabled.", unit = "%").hide()
    private val matchRoomDoorDarkness by BooleanSetting("Match Room Darkness", true, desc = "Uses the Unopened Room Darkness scale for doors instead of Unopened Door Darkness.").hide()
    private val doorWidth by NumberSetting("Door Width", default = 0.4f, min = 0.1f, max = 1f, increment = 0.05f, desc = "Width of the door indicator along the wall, relative to a room's half-size.").hide()
    private val roomGap by NumberSetting("Room Distance", default = GAP_SIZE, min = 0f, max = 30f, increment = 1f, desc = "Distance between adjacent rooms on the map.").hide()
    private val entranceHeadBorder by SelectorSetting("Entrance Head Border", "Set Color", listOf("Class", "Set Color", "None"), desc = "Border around the player head shown in the Entrance room: your dungeon class color, a fixed color, or no border.").hide()
    private val entranceHeadBorderColor by ColorSetting("Entrance Head Border Color", Colors.BLACK, false, desc = "Border color for the Entrance room's player head when Entrance Head Border is set to Set Color.").hide()

    // ── Colors: Doors ────────────────────────────────────────────────────────────────────────
    private val bloodDoorColor by ColorSetting("Blood Door", Color(178, 0, 0), false, desc = "Color of blood doors.").hide()
    private val entranceDoorColor by ColorSetting("Entrance Door", Color(0, 200, 0), false, desc = "Color of the entrance door.").hide()
    private val normalDoorColor by ColorSetting("Normal Door", Color("794600FF"), false, desc = "Color of normal doors.").hide()
    private val openedWitherDoorColor by ColorSetting("Opened Wither Door", Color("794600FF"), false, desc = "Color of an opened wither door.").hide()
    private val unopenedWitherDoorColor by ColorSetting("Unopened Wither Door", Colors.BLACK, false, desc = "Color of an unopened wither door.").hide()

    // ── Colors: Rooms ────────────────────────────────────────────────────────────────────────
    private val bloodRoomColor by ColorSetting("Blood Room", Color(178, 0, 0), false, desc = "Color of blood rooms.").hide()
    private val entranceRoomColor by ColorSetting("Entrance Room", Color(0, 200, 0), false, desc = "Color of the entrance room.").hide()
    private val fairyRoomColor by ColorSetting("Fairy Room", Color(227, 155, 226), false, desc = "Color of fairy rooms.").hide()
    private val minibossRoomColor by ColorSetting("Miniboss Room", Color(255, 200, 0), false, desc = "Color of miniboss (champion) rooms.").hide()
    private val normalRoomColor by ColorSetting("Normal Room", Color(121, 70, 0), false, desc = "Color of normal rooms.").hide()
    private val mimicRoomColor by ColorSetting("Mimic Room", Color("DE3148FF"), true, desc = "Translucent overlay tinted on top of a room known to contain the mimic (detection not wired up yet).").hide()
    private val puzzleRoomColor by ColorSetting("Puzzle Room", Color(123, 0, 123), false, desc = "Color of puzzle rooms.").hide()
    private val rareRoomColor by ColorSetting("Rare Room", Color("9E9857FF"), false, desc = "Color of rare rooms.").hide()
    private val trapRoomColor by ColorSetting("Trap Room", Color("FC9D47FF"), false, desc = "Color of trap rooms.").hide()
    private val unopenedRoomColor by ColorSetting("Unopened Room", Color("4B4747FF"), false, desc = "Fallback color for a room of unrecognized type.").hide()

    private val openConfig by ActionSetting("Open Map Config", desc = "Opens a dedicated settings screen for the dungeon map.") { mc.setScreen(DungeonMapConfigScreen) }

    private const val GRID_STEP = 32
    private const val CELL_SIZE = 22f
    // Only used as the Room Distance setting's default - the gap between rooms is otherwise driven
    // entirely by that setting (see roomGap/pitch in renderMap), not this constant.
    private const val GAP_SIZE = 5f
    private const val PADDING = 6f
    private const val EXAMPLE_SIZE = 160
    private const val BASE_MARKER_SIZE = 10f

    // The map never shrinks below this many room cells wide/tall, even with only one room known (e.g.
    // right at the dungeon start) - it only grows past this once the discovered area actually needs it.
    private const val MIN_GRID_WIDTH = 4
    private const val MIN_GRID_HEIGHT = 5

    // Indices into the "Room Names" SelectorSetting's option list above.
    private const val ROOM_NAMES_ALL = 0
    private const val ROOM_NAMES_TRAP_PUZZLE = 1
    private const val ROOM_NAMES_NONE = 2

    // Indices into the "Room Secrets" SelectorSetting's option list above.
    private const val ROOM_SECRETS_OFF = 0
    private const val ROOM_SECRETS_ON = 1
    private const val ROOM_SECRETS_REPLACE_CHECKMARK = 2

    // Indices into the "Unopened Room Text Color" SelectorSetting's option list above.
    private const val UNOPENED_TEXT_UNCLEARED = 0
    private const val UNOPENED_TEXT_CLEARED = 1

    // Indices into the "Checkmark" SelectorSetting's option list above.
    private const val CHECKMARK_BELOW_NAME = 0
    private const val CHECKMARK_BEHIND_NAME = 1
    private const val CHECKMARK_OFF = 2

    // Indices into the "Show Player Names" SelectorSetting's option list above.
    private const val NAMES_ALWAYS = 0
    private const val NAMES_WHEN_HOLDING_LEAP = 1
    private const val NAMES_NEVER = 2

    // Indices into the "Entrance Head Border" SelectorSetting's option list above.
    private const val ENTRANCE_BORDER_CLASS = 0
    private const val ENTRANCE_BORDER_SET_COLOR = 1
    private const val ENTRANCE_BORDER_NONE = 2

    private val roomColors: Map<RoomType, () -> Color> = mapOf(
        RoomType.NORMAL to { normalRoomColor },
        RoomType.PUZZLE to { puzzleRoomColor },
        RoomType.TRAP to { trapRoomColor },
        RoomType.BLOOD to { bloodRoomColor },
        RoomType.FAIRY to { fairyRoomColor },
        RoomType.RARE to { rareRoomColor },
        RoomType.CHAMPION to { minibossRoomColor },
        RoomType.ENTRANCE to { entranceRoomColor },
    )

    // Wither doors pick between the opened/unopened color via Door.opened - real per-door open/closed
    // detection for the live map isn't wired up yet (it's always true today), but the color logic itself
    // is ready for whenever that lands.
    private fun doorColor(door: Door): Color = when (door.type) {
        DoorType.NORMAL -> normalDoorColor
        DoorType.ENTRANCE -> entranceDoorColor
        DoorType.BLOOD -> bloodDoorColor
        DoorType.WITHER -> if (door.opened) openedWitherDoorColor else unopenedWitherDoorColor
    }

    private fun textColor(state: RoomVisualState): Color = when (state) {
        RoomVisualState.UNCLEARED -> unclearedTextColor
        RoomVisualState.CLEARED -> clearedTextColor
        RoomVisualState.COMPLETED -> completedTextColor
    }

    // Unopened rooms are always Uncleared (see visualState), but which color that actually renders as
    // is a separate, explicit choice - not tied to the Uncleared/Cleared state colors' own meaning.
    private fun textColorForRoom(state: RoomVisualState, entered: Boolean): Color =
        if (!entered) (if (unopenedTextColorChoice == UNOPENED_TEXT_CLEARED) clearedTextColor else unclearedTextColor)
        else textColor(state)

    // Real detection read off the dungeon map item's pixel data (see MapColorScanner) - not a guess
    // from our own tracked state, and not tied to whether the room is currently occupied. Fully secreted
    // implies cleared (Hypixel's own color reuse already guarantees this - see MapColorScanner), so it
    // takes priority.
    private fun visualState(cleared: Boolean, fullySecreted: Boolean): RoomVisualState = when {
        fullySecreted -> RoomVisualState.COMPLETED
        cleared -> RoomVisualState.CLEARED
        else -> RoomVisualState.UNCLEARED
    }

    private fun isHoldingLeap(): Boolean =
        mc.player?.mainHandItem?.hoverName?.string?.noControlCodes?.contains("Leap") == true

    private fun namesVisible(): Boolean = when (showPlayerNames) {
        NAMES_ALWAYS -> true
        NAMES_WHEN_HOLDING_LEAP -> isHoldingLeap()
        else -> false
    }

    private fun GuiGraphicsExtractor.drawScaledLine(label: String, centerX: Float, centerY: Float, scale: Float, color: Color) {
        val rawWidth = mc.font.width(label)
        if (rawWidth <= 0) return
        pose().pushMatrix()
        pose().translate(centerX, centerY)
        pose().scale(scale, scale)
        text(label, -rawWidth / 2, -mc.font.lineHeight / 2, color)
        pose().popMatrix()
    }

    // Multi-word names are split one word per line instead of shrinking a single long line to fit -
    // reads better on the small, roughly-square room tiles than a tiny sideways-squeezed sentence. All
    // lines of a name share one scale (based on its widest word) so the text doesn't jump in size.
    private fun GuiGraphicsExtractor.centeredText(label: String, centerX: Float, centerY: Float, maxWidth: Float, color: Color, scaleMultiplier: Float = 1f) {
        if (maxWidth <= 2f) return
        val words = label.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return

        val widestWordPx = words.maxOf { mc.font.width(it) }
        if (widestWordPx <= 0) return
        // Never shrinks past Min Text Size, even if that means the word no longer actually fits within
        // maxWidth - spilling out past the room's edge reads better than shrinking into illegibility.
        val scale = (maxWidth / widestWordPx).coerceIn(minTextSize, 1f) * mapTextScale * scaleMultiplier

        if (words.size == 1) {
            drawScaledLine(words[0], centerX, centerY, scale, color)
            return
        }

        val lineHeight = mc.font.lineHeight * scale
        var y = centerY - (lineHeight * words.size) / 2f + lineHeight / 2f
        for (word in words) {
            drawScaledLine(word, centerX, y, scale, color)
            y += lineHeight
        }
    }

    /**
     * Draws one full frame of the map (rooms, doors, teammates, self, per-room state) from [data]. Used
     * by both the live HUD (fed from [DungeonUtils]) and [DungeonMapConfigScreen]'s preview (fed from
     * fabricated example data), so tweaking a setting always looks identical in both places.
     */
    internal fun GuiGraphicsExtractor.renderMap(data: MapRenderData): Pair<Int, Int> {
        val rooms = data.rooms
        if (rooms.isEmpty()) return 0 to 0

        val anchorComponents = rooms.flatMap { it.roomComponents }
        if (anchorComponents.isEmpty()) return 0 to 0

        // All grid math is relative to an arbitrary anchor - components are always exact multiples of
        // GRID_STEP apart (see ScanUtils), so this division is always exact for room cells.
        val anchorX = anchorComponents.first().x
        val anchorZ = anchorComponents.first().z
        fun gx(worldX: Int) = (worldX - anchorX) / GRID_STEP
        fun gz(worldZ: Int) = (worldZ - anchorZ) / GRID_STEP
        fun gxf(worldX: Double) = ((worldX - anchorX) / GRID_STEP).toFloat()
        fun gzf(worldZ: Double) = ((worldZ - anchorZ) / GRID_STEP).toFloat()

        // Bound top-left: the map's own top-left corner is a small margin away from the top/left-most
        // known room, and the drawn area only ever covers that content's actual bounding box - it grows
        // away from that corner as more rooms are discovered instead of sitting in a big empty box.
        val allGX = anchorComponents.map { gx(it.x) }
        val allGZ = anchorComponents.map { gz(it.z) }
        val minGX = allGX.min(); val maxGX = allGX.max()
        val minGZ = allGZ.min(); val maxGZ = allGZ.max()

        // Distance between adjacent room centers - CELL_SIZE plus the user-configurable Room Distance,
        // rather than a fixed constant, so the whole grid (rooms, doors, markers) scales with that setting.
        val pitch = CELL_SIZE + roomGap

        val gridWidth = maxOf(maxGX - minGX + 1, MIN_GRID_WIDTH)
        val gridHeight = maxOf(maxGZ - minGZ + 1, MIN_GRID_HEIGHT)
        val canvasWidth = (gridWidth * pitch + PADDING * 2f)
        val canvasHeight = (gridHeight * pitch + PADDING * 2f)

        fun gridToScreen(gridX: Float, gridZ: Float): Pair<Float, Float> =
            (PADDING + (gridX - minGX + 0.5f) * pitch) to (PADDING + (gridZ - minGZ + 0.5f) * pitch)

        fun worldToScreen(x: Double, z: Double): Pair<Float, Float> = gridToScreen(gxf(x), gzf(z))

        // Keyed by room name rather than the Room instance itself - if the same physical room ever
        // gets scanned into more than one Room object (e.g. discovered piecemeal while walking by
        // before being fully entered), its tiles still need to recognize each other as connected.
        val roomNameAt = HashMap<Pair<Int, Int>, String>()
        for (room in rooms) for (c in room.roomComponents) roomNameAt[gx(c.x) to gz(c.z)] = room.data.name

        val half = CELL_SIZE / 2f
        // Half of the (user-configurable) inter-cell gap, plus a pixel of overlap so rounding never
        // leaves a seam.
        val halfGap = roomGap / 2f + 1f
        val darknessFactor = 1f - (unopenedRoomDarkness.toFloat() / 100f).coerceIn(0f, 1f)
        val doorDarknessFactor = 1f - ((if (matchRoomDoorDarkness) unopenedRoomDarkness else unopenedDoorDarkness).toFloat() / 100f).coerceIn(0f, 1f)
        val headSize = (BASE_MARKER_SIZE * playerHeadScale).toInt().coerceAtLeast(4)
        // "Class" falls back to the Entrance room's own color when there's no live self (e.g. the
        // config screen's preview, which has no real dungeon player to read a class from).
        val entranceHeadBorderColorResolved: Color? = when (entranceHeadBorder) {
            ENTRANCE_BORDER_CLASS -> data.self?.color ?: entranceRoomColor
            ENTRANCE_BORDER_SET_COLOR -> entranceHeadBorderColor
            else -> null
        }

        // Drawn before rooms (rather than after) so a door's slight overlap into its rooms' edges (see
        // halfGap's doc) sits behind the room fill instead of visibly poking over it.
        for (door in data.doors) {
            val gAx = gx(door.a.x); val gAz = gz(door.a.z)
            val gBx = gx(door.b.x); val gBz = gz(door.b.z)
            val roomA = roomNameAt[gAx to gAz] ?: continue
            val roomB = roomNameAt[gBx to gBz] ?: continue
            val (ax, az) = gridToScreen(gAx.toFloat(), gAz.toFloat())
            val (bx, bz) = gridToScreen(gBx.toFloat(), gBz.toFloat())
            val midX = (ax + bx) / 2f; val midZ = (az + bz) / 2f
            // A door reads as "opened" only once both rooms it connects have actually been entered -
            // one still-unopened side is enough to darken it, same as that room itself would be.
            val doorEntered = roomA in data.enteredRoomNames && roomB in data.enteredRoomNames
            val baseDoorColor = doorColor(door)
            val color = if (doorEntered) baseDoorColor else baseDoorColor.darker(doorDarknessFactor)
            // shortHalf spans the full room-to-room gap (matching the halfGap used by the room-shape
            // bridges above, overlap included) so the door bar's edges meet the rooms' edges with no
            // visible sliver of empty background between them.
            val longHalf = half * doorWidth
            if (gAx == gBx) {
                // Rooms connected along Z: door bar runs horizontally, across the room's X extent.
                fill((midX - longHalf).toInt(), (midZ - halfGap).toInt(), (midX + longHalf).toInt(), (midZ + halfGap).toInt(), color.rgba)
            } else {
                fill((midX - halfGap).toInt(), (midZ - longHalf).toInt(), (midX + halfGap).toInt(), (midZ + longHalf).toInt(), color.rgba)
            }
        }

        for (room in rooms) {
            val entered = room.data.name in data.enteredRoomNames
            // Unopened Normal rooms use the generic Unopened Room color (then darkened below) - a plain
            // room's contents genuinely aren't knowable from outside until entered, same as the real
            // Hypixel map only reveals what's inside once you walk in. Special room types (Fairy, Blood,
            // Puzzle, Trap, Rare, Champion, Entrance) are recognizable by their color from the door/map
            // alone even before entering, so they keep showing their own real color - just darkened,
            // same as before. Previously unopenedRoomColor was only reached as a fallback for an unmapped
            // RoomType, which never happens in practice, so the setting had no visible effect at all.
            val showRealColor = entered || room.data.type != RoomType.NORMAL
            val baseColor = if (showRealColor) (roomColors[room.data.type]?.invoke() ?: unopenedRoomColor) else unopenedRoomColor
            val color = if (entered) baseColor else baseColor.darker(darknessFactor)

            fun drawShape(fillColor: Int) {
                for (c in room.roomComponents) {
                    val gcx = gx(c.x); val gcz = gz(c.z)
                    val (screenX, screenZ) = gridToScreen(gcx.toFloat(), gcz.toFloat())
                    val left = (screenX - half).toInt(); val top = (screenZ - half).toInt()
                    val right = (screenX + half).toInt(); val bottom = (screenZ + half).toInt()
                    fill(left, top, right, bottom, fillColor)

                    // Bridges into the gap towards neighbors of the same room, so a multi-tile room
                    // renders as one seamless shape instead of a grid of separate squares. Each bridge is
                    // confined to this cell's own width/height (not extended past its corners), so an
                    // L-shaped room's inner (concave) corner - where the diagonal cell is deliberately NOT
                    // part of the room - doesn't get a phantom square poking into it the way combining two
                    // extensions into one rectangle would.
                    if (roomNameAt[gcx to (gcz - 1)] == room.data.name) fill(left, (top - halfGap).toInt(), right, top, fillColor)
                    if (roomNameAt[gcx to (gcz + 1)] == room.data.name) fill(left, bottom, right, (bottom + halfGap).toInt(), fillColor)
                    if (roomNameAt[(gcx - 1) to gcz] == room.data.name) fill((left - halfGap).toInt(), top, left, bottom, fillColor)
                    if (roomNameAt[(gcx + 1) to gcz] == room.data.name) fill(right, top, (right + halfGap).toInt(), bottom, fillColor)

                    // Corner patches: the two edge bridges above never reach the exact point where 4 cells
                    // meet (each is confined to its own cell), which leaves a small hole there even for a
                    // genuine 2x2 block. Only fill it when both edges AND the actual diagonal cell belong
                    // to the same room - otherwise (an L-shape's concave corner) it must stay empty.
                    if (roomNameAt[(gcx - 1) to gcz] == room.data.name && roomNameAt[gcx to (gcz - 1)] == room.data.name && roomNameAt[(gcx - 1) to (gcz - 1)] == room.data.name)
                        fill((left - halfGap).toInt(), (top - halfGap).toInt(), left, top, fillColor)
                    if (roomNameAt[(gcx + 1) to gcz] == room.data.name && roomNameAt[gcx to (gcz - 1)] == room.data.name && roomNameAt[(gcx + 1) to (gcz - 1)] == room.data.name)
                        fill(right, (top - halfGap).toInt(), (right + halfGap).toInt(), top, fillColor)
                    if (roomNameAt[(gcx - 1) to gcz] == room.data.name && roomNameAt[gcx to (gcz + 1)] == room.data.name && roomNameAt[(gcx - 1) to (gcz + 1)] == room.data.name)
                        fill((left - halfGap).toInt(), bottom, left, (bottom + halfGap).toInt(), fillColor)
                    if (roomNameAt[(gcx + 1) to gcz] == room.data.name && roomNameAt[gcx to (gcz + 1)] == room.data.name && roomNameAt[(gcx + 1) to (gcz + 1)] == room.data.name)
                        fill(right, bottom, (right + halfGap).toInt(), (bottom + halfGap).toInt(), fillColor)
                }
            }

            drawShape(color.rgba)
            // Mimic isn't its own room type - it's tinted on top of the room's real color as a
            // translucent overlay (see mimicRoomColor's alpha), rather than replacing it outright.
            if (room.data.name in data.mimicRoomNames) drawShape(mimicRoomColor.rgba)

            val roomGX = room.roomComponents.map { gx(it.x) }
            val roomGZ = room.roomComponents.map { gz(it.z) }
            val minRoomGX = roomGX.min(); val maxRoomGX = roomGX.max()
            val minRoomGZ = roomGZ.min(); val maxRoomGZ = roomGZ.max()
            val isRectangular = room.roomComponents.size == (maxRoomGX - minRoomGX + 1) * (maxRoomGZ - minRoomGZ + 1)

            // The bounding-box center is guaranteed to land on an actual cell only for rectangular rooms.
            // Non-rectangular (e.g. L-shaped) ones use their widest actual row instead, so labels never
            // land on the missing/concave cell that isn't really part of the room.
            val (baseLabelX, baseLabelY, labelMaxWidth) = if (isRectangular) {
                val (lx, ly) = gridToScreen((minRoomGX + maxRoomGX) / 2f, (minRoomGZ + maxRoomGZ) / 2f)
                Triple(lx, ly, (maxRoomGX - minRoomGX + 1) * CELL_SIZE)
            } else {
                val byRow = room.roomComponents.groupBy { gz(it.z) }
                val widestRow = byRow.values.maxBy { it.size }
                val rowMinX = widestRow.minOf { it.x }
                val rowMaxX = widestRow.maxOf { it.x }
                val rowZ = widestRow.first().z
                val (lx, ly) = worldToScreen((rowMinX + rowMaxX) / 2.0, rowZ.toDouble())
                Triple(lx, ly, (gx(rowMaxX) - gx(rowMinX) + 1) * CELL_SIZE)
            }

            // Only the config screen's preview shows a static head marker on the Entrance tile (see
            // showEntranceIcon's doc) - the live map already tracks the real player position separately.
            if (room.data.type == RoomType.ENTRANCE && data.showEntranceIcon) {
                val r = headSize / 2f
                val headX = (baseLabelX - r).toInt(); val headY = (baseLabelY - r).toInt()
                playerHeadIcon(data.self?.skin, headX, headY, headSize)
                entranceHeadBorderColorResolved?.let { hollowFill(headX - 1, headY - 1, headSize + 2, headSize + 2, 1, it) }
            }

            val fullySecreted = room.data.name in data.fullySecretedRoomNames
            val state = visualState(room.data.name in data.clearedRoomNames, fullySecreted)
            val stateColor = textColorForRoom(state, entered)

            // A room with no name (e.g. one Hypixel just doesn't label), or an Entrance/Blood room (never
            // labeled regardless of its actual name), shows neither a name nor a checkmark/secrets
            // readout - there's nothing meaningful to caption it with. Unlike naming, this always applies
            // regardless of whether the room is opened.
            val unlabeled = room.data.name.isEmpty() || room.data.type == RoomType.ENTRANCE || room.data.type == RoomType.BLOOD || room.data.type == RoomType.FAIRY

            val showThisName = !unlabeled && when (roomNames) {
                ROOM_NAMES_ALL -> true
                ROOM_NAMES_TRAP_PUZZLE -> room.data.type == RoomType.TRAP || room.data.type == RoomType.PUZZLE
                else -> false
            }
            // A room with no secrets at all has nothing to report - just its name, never a checkmark or
            // an empty "0/0" readout.
            val hasSecrets = room.data.secrets > 0
            // Checkmarks/secrets readouts only ever show on opened rooms - an unopened room's mobs
            // haven't even been seen yet, so there's no meaningful clear/secrets state to report.
            val showSecretsText = entered && !unlabeled && hasSecrets && (roomSecrets == ROOM_SECRETS_ON || roomSecrets == ROOM_SECRETS_REPLACE_CHECKMARK)
            // The standalone lone checkmark (Room Secrets = Off) is purely gated on the Checkmark
            // setting - shown for any entered room with secrets, regardless of completion. Its color
            // (stateColor) is what communicates real progress, turning green only once genuinely fully
            // secreted, exactly like the real Hypixel map. Only shown once the room actually reads as
            // Cleared or Completed (white/green) - an opened-but-still-Uncleared room with secrets left
            // to find doesn't get a checkmark at all, rather than one in the gray Uncleared color.
            val showCheckmark = entered && !unlabeled && roomSecrets != ROOM_SECRETS_REPLACE_CHECKMARK && checkmarkStyle != CHECKMARK_OFF && state != RoomVisualState.UNCLEARED
            // The "✔ " prefix used alongside the X/Y count (On/Replace Checkmark modes) is different -
            // there the numbers already show progress, so the ✔ there specifically flags "fully secreted".
            val showCheckmarkGlyph = checkmarkStyle != CHECKMARK_OFF && fullySecreted
            val pairCenterY = baseLabelY + roomNameLocation * half

            // In "Replace Checkmark" mode the name and the secrets readout are a tight pair centered
            // together (shifted up from where the name alone would sit), so both actually fit within the
            // room's tile instead of the second line spilling past its bottom edge. Otherwise the readout
            // just sits a fixed distance below the name, as before.
            val stackedPair = showThisName && (showCheckmark || showSecretsText) && roomSecrets == ROOM_SECRETS_REPLACE_CHECKMARK
            val pairGap = half * 0.6f
            val nameY = if (stackedPair) pairCenterY - pairGap else pairCenterY

            // "Behind Name" only ever applies to the standalone lone checkmark (Room Secrets = Off) - the
            // On/Replace modes always fold it into the secrets count line instead, which this style doesn't
            // touch. Drawn at the exact same spot as the name, before the name itself, so it forms a
            // background layer the name renders on top of rather than a separate line.
            val standaloneCheckmark = showCheckmark && !showSecretsText
            val checkmarkBehindName = standaloneCheckmark && checkmarkStyle == CHECKMARK_BEHIND_NAME && showThisName
            if (checkmarkBehindName) {
                centeredText("✔", baseLabelX, nameY, labelMaxWidth * 0.9f, stateColor, checkmarkScale)
            }

            if (showThisName) {
                centeredText(room.data.name, baseLabelX, nameY, labelMaxWidth * 0.9f, stateColor)
            }

            if (!checkmarkBehindName && (showCheckmark || showSecretsText)) {
                // Always drawn below the room name (never overlapping it) when a name is actually shown
                // here - checkmarkLocation only positions it independently when there's no name to anchor to.
                val markY = if (stackedPair) pairCenterY + pairGap
                    else if (showThisName) nameY + half
                    else baseLabelY + checkmarkLocation * half
                // "Replace Checkmark" mode still needs the real fullySecreted signal to show through -
                // otherwise a room the map itself reports as fully secreted could keep showing an
                // incomplete-looking count forever if our own local secrets tracking is even slightly off.
                val label = if (showSecretsText) (if (showCheckmarkGlyph) "✔ " else "") + "${room.secretsFound}/${room.data.secrets}"
                    else "✔"
                centeredText(label, baseLabelX, markY, labelMaxWidth * 0.9f, stateColor, if (showSecretsText) 1f else checkmarkScale)
            }
        }

        val showNames = namesVisible()
        for (teammate in data.teammates) {
            val (screenX, screenZ) = worldToScreen(teammate.x, teammate.z)
            val r = headSize / 2f
            val headX = (screenX - r).toInt(); val headY = (screenZ - r).toInt()
            playerHeadIcon(teammate.skin, headX, headY, headSize)
            hollowFill(headX - 1, headY - 1, headSize + 2, headSize + 2, 1, teammate.color)
            if (showNames && teammate.name != null) {
                pose().pushMatrix()
                pose().translate(screenX + r + 2, screenZ - r)
                pose().scale(playerNameScale, playerNameScale)
                text(teammate.name, 0, 0, teammate.color)
                pose().popMatrix()
            }
        }

        data.self?.let { self ->
            val (selfX, selfZ) = worldToScreen(self.x, self.z)
            val r = headSize / 2f
            // The marker's default orientation faces "up" (north/-Z) on screen; rotate the whole thing
            // (icon + border, not just the icon) to match where the player is actually looking.
            val yawRad = Math.toRadians(self.yaw.toDouble())
            val dirX = -sin(yawRad).toFloat()
            val dirZ = cos(yawRad).toFloat()
            val headRotation = atan2(dirX, -dirZ)

            pose().pushMatrix()
            pose().translate(selfX, selfZ)
            pose().mul(Matrix3x2f().identity().rotate(headRotation))
            val headX = (-r).toInt(); val headY = (-r).toInt()
            playerHeadIcon(self.skin, headX, headY, headSize)
            hollowFill(headX - 1, headY - 1, headSize + 2, headSize + 2, 1, self.color)
            pose().popMatrix()
        }

        return canvasWidth.toInt() to canvasHeight.toInt()
    }

    // Built directly from HudElement/HUDSetting instead of the Module.HUD(...) shorthand, since that
    // shorthand always defaults a toggleable HUD's initial `enabled` to false - this one should default
    // to visible (and at this position/scale) out of the box.
    val hud = +HUDSetting(
        name = "Dungeon Map",
        // Labeled HUD@ so the existing return@HUD statements throughout this block keep working
        // unchanged, same as when this render lambda was passed to the Module.HUD(...) shorthand.
        hud = HudElement(x = 0, y = 1, scale = 2.2f, enabled = true) HUD@{ example ->
        if (example) {
            text("§7Dungeon Map", 4, 4)
            return@HUD EXAMPLE_SIZE to EXAMPLE_SIZE
        }
        try {
        MapColorScanner.useExactCenterCheckmark = useNoammCheckmarkPixel
        if (!DungeonUtils.inDungeons) return@HUD 0 to 0
        if (hideInBoss && DungeonUtils.inBoss) return@HUD 0 to 0

        val player = mc.player ?: return@HUD 0 to 0
        val trackedRooms = DungeonUtils.passedRooms + listOfNotNull(DungeonUtils.currentRoom)
        val trackedNames = trackedRooms.mapTo(mutableSetOf()) { it.data.name }
        val nearbyExtra = DungeonUtils.nearbyRooms.filter { it.data.name !in trackedNames }
        val rooms = trackedRooms + nearbyExtra
        if (rooms.isEmpty()) return@HUD 0 to 0

        val self = MapActor(player.x, player.z, player.yRot, DungeonUtils.currentDungeonPlayer.playerSkin, null, DungeonUtils.currentDungeonPlayer.clazz.color)

        // A teammate's entity goes null the moment Minecraft unloads them client-side (too far away, e.g.
        // off clearing another room) - previously that just dropped their marker from the map entirely.
        // Fall back to the map's own "other player holding this map" decoration (see
        // MapColorScanner.findPlayerDecorations) instead: prefer a name match if Hypixel actually sets one
        // (unverified), otherwise the closest unclaimed, unnamed decoration to that teammate's last known
        // position - tracked in mapPos/yaw specifically for this, updated below whenever the entity IS
        // available. Own marker excluded by position so a teammate's marker can never latch onto ourself.
        val decorations = MapColorScanner.findPlayerDecorations().toMutableList()
        decorations.removeAll { kotlin.math.abs(it.pos.x - player.x) < 5 && kotlin.math.abs(it.pos.z - player.z) < 5 }

        val teammates = DungeonUtils.dungeonTeammatesNoSelf.mapNotNull { teammate ->
            if (teammate.isDead) return@mapNotNull null

            val entity = teammate.entity
            if (entity != null) {
                teammate.mapPos = Vec2i(entity.x.toInt(), entity.z.toInt())
                teammate.yaw = entity.yRot
                return@mapNotNull MapActor(entity.x, entity.z, entity.yRot, teammate.playerSkin, teammate.name, teammate.clazz.color)
            }

            val chosenIndex = decorations.indexOfFirst { it.name == teammate.name }.takeIf { it >= 0 }
                ?: decorations.indices
                    .filter { decorations[it].name == null }
                    .minByOrNull { i ->
                        val dx = decorations[i].pos.x - teammate.mapPos.x
                        val dz = decorations[i].pos.z - teammate.mapPos.z
                        dx * dx + dz * dz
                    }
                ?: return@mapNotNull null
            val marker = decorations.removeAt(chosenIndex)
            teammate.mapPos = marker.pos.let { Vec2i(it.x, it.z) }
            teammate.yaw = marker.yaw
            MapActor(marker.pos.x.toDouble(), marker.pos.z.toDouble(), marker.yaw, teammate.playerSkin, teammate.name, teammate.clazz.color)
        }

        // Any room grid cell(s) the map's own pixels show as revealed - i.e. a teammate physically entered
        // them - but that our own proximity-based scanning hasn't reached yet get placed here, at the
        // exact world position(s) and with the real type the map's own pixel data says they're at (merged
        // into one multi-cell room where the map shows them as connected - see
        // [MapColorScanner.findUnknownRooms]), so it shows up (and, once cleared/secreted, checkmarked)
        // exactly like the real Hypixel map - without this, room discovery and clear/completed detection
        // both only ever reflected the local player's own exploration. Treated as already "entered" (drawn
        // at full brightness, real type color) the moment it's discovered at all, same as the real map
        // reveals it the instant anyone steps in.
        val knownCells = rooms.flatMapTo(mutableSetOf()) { room -> room.roomComponents.map { it.vec2 } }
        val discoveredRooms = MapColorScanner.findUnknownRooms(knownCells)
        val placeholders = discoveredRooms.mapIndexed { index, discovered ->
            Room(
                data = RoomData("Room #$index", discovered.type, emptyList(), 0, 0, 0, RoomShape.UNKNOWN),
                roomComponents = discovered.positions.mapTo(mutableSetOf()) { RoomComponent(it.x, it.z) }
            ) to discovered
        }
        val allRooms = rooms + placeholders.map { it.first }

        val clearedRoomNames = rooms.mapNotNullTo(mutableSetOf()) { room ->
            room.data.name.takeIf { MapColorScanner.isCleared(it) }
        }
        placeholders.forEach { (room, discovered) -> if (discovered.cleared) clearedRoomNames.add(room.data.name) }

        val fullySecretedRoomNames = rooms.mapNotNullTo(mutableSetOf()) { room ->
            room.data.name.takeIf { MapColorScanner.isFullySecreted(it) }
        }
        placeholders.forEach { (room, discovered) -> if (discovered.fullySecreted) fullySecretedRoomNames.add(room.data.name) }

        // Weaker than cleared/secreted, but still real: someone has physically opened/entered this room
        // even if they're not done clearing it yet (see MapColorScanner.isOpened's doc).
        val openedRoomNames = rooms.mapNotNullTo(mutableSetOf()) { room ->
            room.data.name.takeIf { MapColorScanner.isOpened(it) }
        }

        // Cleared, fully secreted, or merely opened - any of these is undeniable proof someone on the
        // team has been in there, regardless of whether that was the local player. Previously "entered"
        // (which gates brightness AND the checkmark/name display below) only ever looked at the local
        // player's own trackedRooms, so a room a teammate opened or cleared stayed dark and checkmark-less
        // on our map forever unless we personally walked in too, even though its real state was already
        // being tracked correctly.
        val enteredNames = trackedNames + placeholders.map { it.first.data.name } + clearedRoomNames + fullySecretedRoomNames + openedRoomNames

        renderMap(
            MapRenderData(
                rooms = allRooms,
                doors = DungeonUtils.doors,
                self = self,
                teammates = teammates,
                enteredRoomNames = enteredNames,
                clearedRoomNames = clearedRoomNames,
                fullySecretedRoomNames = fullySecretedRoomNames,
            )
        )
        } catch (e: Throwable) {
            // A crash mid-render (e.g. from one of the newer pixel-scanning additions) would otherwise
            // skip the pose().popMatrix() that HudElement.draw() expects to run after this lambda returns,
            // leaving the transform stack unbalanced for every HUD drawn afterward that frame - catching
            // it here keeps that balanced and, via the webhook, actually tells us what broke instead of
            // just silently losing pieces of the map (e.g. the self/teammate head markers, drawn last).
            DiscordLogger.log("DungeonMap render crashed: ${e.stackTraceToString().take(1800)}")
            0 to 0
        }
        },
        toggleable = true,
        description = "Live dungeon map: rooms, doors, teammates and secrets.",
        module = this
    )
}
