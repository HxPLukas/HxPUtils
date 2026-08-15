package de.hxp.hxpaddons.utils.skyblock.dungeon

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.events.core.onReceive
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.utils.Vec2
import de.hxp.hxpaddons.utils.network.DiscordLogger
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomComponent
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomType
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapItemSavedData

/**
 * Reads real room progress off the actual Hypixel dungeon map item's pixel data, rather than guessing
 * from our own tracked state. Hypixel bakes room progress directly into the map you're holding: each
 * 32-block room occupies a square block of pixels in the map's 128x128 color grid, most of which is a
 * flat fill representing the room's type - but a room's actual cleared/secreted state lives in one
 * specific, separate pixel offset slightly off from the block's geometric center, not the fill itself
 * (a plain Normal room's fill color never changes whether it's cleared or not) - see [checkmarkPixel].
 * This includes the room the player is physically standing in right now: the color there updates for
 * real once its mobs actually die, there's no entry-triggered highlight to work around.
 *
 * The map's own scale/offset differs per floor, so it's calibrated once per dungeon by finding the
 * entrance room's pixel block - it's always drawn in a single fixed color, and by definition never moves
 * relative to the rest of the floor, making it a reliable reference point.
 */
object MapColorScanner {
    private const val COLOR_ENTRANCE_OR_GREEN = 30
    private const val COLOR_CLEARED = 34

    // Matches NoammAddons' own room-state table: the checkmark/center pixel reads as one of these two
    // values specifically while a room is still locked/unopened - every other value (including 34/30, but
    // also anything else once mobs have been engaged but not all killed yet) means someone has been in
    // there. This is what tells "opened but not yet cleared" apart from "nobody's been here at all" -
    // isRoomCleared/isFullySecreted alone only ever recognize the two *finished* states.
    private val UNOPENED_CHECKMARK_COLORS = setOf(85, 119)

    private const val WORLD_GRID_STEP = 32
    private const val WORLD_ORIGIN = -185 // matches ScanUtils.START - both describe the same floor grid
    private const val MAP_SIZE = 128
    private const val RESCAN_INTERVAL_TICKS = 10

    // Dungeon floors fit within an 11x11 room grid from WORLD_ORIGIN - the same span ScanUtils' own
    // room-center math implicitly covers, just made explicit here since this scan isn't anchored to the
    // player's position at all (unlike ScanUtils/NearbyRoomScanner). 11 is a safe upper bound for the
    // loop below - the real per-floor size (see floorRoomGrid) is only needed for calibration centering.
    private const val FLOOR_GRID_ROOMS = 11

    // Each floor's actual max room grid (width, height) - smaller floors don't fill the full 11x11 span
    // above, which matters for calibrate()'s centering below. Floor numbers match Floor.floorNumber (0 =
    // Entrance); master mode shares the same grid size as its equivalent normal floor.
    private val floorRoomGrid = mapOf(
        0 to (4 to 4), // Entrance
        1 to (4 to 5), // F1 / M1
        2 to (5 to 5), // F2 / M2
        3 to (5 to 5), // F3 / M3
        4 to (6 to 5), // F4 / M4
        5 to (6 to 6), // F5 / M5
        6 to (6 to 6), // F6 / M6
        7 to (6 to 6), // F7 / M7
    )

    private var calibrated = false
    private var roomPixelSize = 16
    private var originPixelX = 5
    private var originPixelZ = 5

    private var tickCounter = 0
    private var lastMapData: MapItemSavedData? = null
    private val clearedRoomNames = mutableSetOf<String>()
    private val fullySecretedRoomNames = mutableSetOf<String>()
    private val openedRoomNames = mutableSetOf<String>()
    private var lastDebugRoomName: String? = null
    private var lastDebugClearedState: Boolean? = null

    // Set the instant a map update packet arrives (see NoammAddons' own MainThreadPacketReceivedEvent
    // hook, which reacts to this same packet), so a teammate's progress reflects on our map within one
    // tick instead of waiting up to RESCAN_INTERVAL_TICKS for the next poll. Deliberately NOT read
    // directly inside the packet listener itself - PacketEvent.Receive fires before the packet is actually
    // applied to the client's MapItemSavedData (see ConnectionMixin - it injects before the packet's own
    // handle() call, and can even cancel it), so mc.level.getMapData(...) would still return the old data
    // at that point. Flagging here and letting tick() (which always runs after packet processing for that
    // tick) do the actual read shortly after is what actually gets fresh data safely.
    @Volatile
    private var forceRefresh = false

    init {
        on<TickEvent.End> { tick() }
        on<WorldEvent.Load> { reset() }
        onReceive<ClientboundMapItemDataPacket> { forceRefresh = true }
    }

    /** Whether the map's own pixel data currently reads this room as cleared (or fully green/secreted). */
    fun isCleared(roomName: String): Boolean = roomName in clearedRoomNames

    /** Whether the map's own checkmark icon (all of this room's secrets found) is currently showing. */
    fun isFullySecreted(roomName: String): Boolean = roomName in fullySecretedRoomNames

    /**
     * Whether the map's own pixel data currently reads this room as opened/discovered by anyone - i.e.
     * someone has physically entered it, even if they haven't finished clearing it yet. True for cleared
     * and fully-secreted rooms too (those imply this), so callers only need this one check to decide
     * whether a room should show as "opened" (bright) versus still-locked/unopened (dark).
     */
    fun isOpened(roomName: String): Boolean = roomName in openedRoomNames

    /** A live "other player holding this map" marker read straight off the map item - see [findPlayerDecorations]. */
    data class PlayerMarker(val name: String?, val pos: Vec2, val yaw: Float)

    /**
     * Vanilla itself draws a small arrow on a map for every player currently holding/viewing a copy of
     * that same map ID - Hypixel's dungeon map is shared by the whole team, so this includes teammates,
     * and crucially keeps updating from the map's own synced pixel data regardless of whether that
     * teammate's actual player *entity* is currently loaded on the local client (which stops happening the
     * moment they're far enough away - see DungeonListener's ClientboundRemoveEntitiesPacket handling).
     * This is what [DungeonMap] falls back to for a teammate's position once their entity goes null, so
     * their marker doesn't just disappear the moment they're out of render distance.
     *
     * [PlayerMarker.name] is populated only if Hypixel actually sets a name on the decoration (unverified -
     * vanilla doesn't set one by default for plain player markers) - callers should be ready to match
     * unnamed markers some other way (e.g. proximity to a last-known position) if this comes back null.
     */
    fun findPlayerDecorations(): List<PlayerMarker> {
        val mapData = lastMapData ?: return emptyList()
        if (!calibrated) return emptyList()

        val pitch = roomPixelSize + 4
        val result = mutableListOf<PlayerMarker>()
        for (decoration in mapData.getDecorations()) {
            val type = decoration.type().value()
            if (type !== MapDecorationTypes.PLAYER.value() && type !== MapDecorationTypes.PLAYER_OFF_MAP.value()) continue

            val pixelX = (decoration.x() + 128) shr 1
            val pixelZ = (decoration.y() + 128) shr 1
            val worldX = WORLD_ORIGIN + (pixelX - originPixelX) * (WORLD_GRID_STEP.toDouble() / pitch)
            val worldZ = WORLD_ORIGIN + (pixelZ - originPixelZ) * (WORLD_GRID_STEP.toDouble() / pitch)
            val yaw = decoration.rot() * 22.5f
            val name = decoration.name().orElse(null)?.string

            result.add(PlayerMarker(name, Vec2(worldX.toInt(), worldZ.toInt()), yaw))
        }
        return result
    }

    /** One or more room grid cells discovered purely from the map's own pixels - see [findUnknownRooms]. */
    data class DiscoveredRoom(val positions: List<Vec2>, val type: RoomType, val cleared: Boolean, val fullySecreted: Boolean)

    private data class DiscoveredCell(val pos: Vec2, val type: RoomType, val cleared: Boolean, val fullySecreted: Boolean)

    // Hypixel bakes each room's type into the same flat fill color [baseColorAt] samples - matching
    // NoammAddons' HotbarMapColorParser/RoomType.fromMapColor palette (same underlying Hypixel map
    // convention, verified against their working implementation). This is a *different* pixel from the
    // cleared/secreted checkmark [checkmarkPixel] samples, so a room can independently be e.g. "type=RARE
    // fill (34)" and "checkmark=CLEARED (34)" at the same time without colliding - they're never read
    // from the same coordinate.
    private val typeColors = mapOf(
        18 to RoomType.BLOOD,
        74 to RoomType.CHAMPION,
        30 to RoomType.ENTRANCE,
        82 to RoomType.FAIRY,
        63 to RoomType.NORMAL, 85 to RoomType.NORMAL,
        66 to RoomType.PUZZLE,
        34 to RoomType.RARE,
        62 to RoomType.TRAP,
    )

    // The gap [calibrate]'s pitch (roomPixelSize + GAP_WIDTH) always reserves between two orthogonally
    // adjacent room blocks. Sampling its center is how [findUnknownRooms] tells a real multi-cell room
    // (2x1, 2x2, L-shaped, ...) - an uninterrupted continuation of the same fill color straight through
    // that gap - apart from two separate same-type rooms that just happen to sit next to each other on
    // the grid, connected by an ordinary door: a real door (or a still-unrevealed gap) always renders a
    // visually distinct marker there instead of the plain fill color continuing.
    private const val GAP_WIDTH = 4

    private fun gapColor(mapData: MapItemSavedData, worldX: Int, worldZ: Int, horizontal: Boolean): Int? {
        val (blockStartX, blockStartZ) = blockStart(worldX, worldZ)
        val gapCenter = roomPixelSize + GAP_WIDTH / 2
        val pixelX = if (horizontal) blockStartX + gapCenter else blockStartX + roomPixelSize / 2
        val pixelZ = if (horizontal) blockStartZ + roomPixelSize / 2 else blockStartZ + gapCenter
        if (pixelX !in 0 until MAP_SIZE || pixelZ !in 0 until MAP_SIZE) return null
        return mapData.colors[pixelZ * MAP_SIZE + pixelX].toInt()
    }

    /**
     * Scans the whole floor grid (not just rooms we already know about) for every cell the map's own
     * pixels show as revealed - i.e. a teammate has physically entered it - skipping any world cell
     * already covered by [knownWorldCells], and merges orthogonally-adjacent same-type cells that read as
     * one continuous room (see [gapColor]) into a single multi-cell [DiscoveredRoom]. This lets a room
     * discovered/cleared by a teammate anywhere on the floor appear on our own map at the exact same
     * relative position and shape it has on the real Hypixel map, even before our own proximity-based
     * scanning (ScanUtils / NearbyRoomScanner) has ever reached anywhere near it - without this, both room
     * discovery and clear/secret detection only ever reflected the local player's own exploration, since
     * everything else here only ever looks at rooms already known from that proximity scan.
     */
    fun findUnknownRooms(knownWorldCells: Set<Vec2>): List<DiscoveredRoom> {
        val mapData = lastMapData ?: return emptyList()
        if (!calibrated) return emptyList()

        val cells = LinkedHashMap<Pair<Int, Int>, DiscoveredCell>()
        for (roomIndexX in 0 until FLOOR_GRID_ROOMS) {
            for (roomIndexZ in 0 until FLOOR_GRID_ROOMS) {
                if (roomIndexX == 0 && roomIndexZ == 0) continue // the entrance's own fixed grid slot
                val worldX = WORLD_ORIGIN + roomIndexX * WORLD_GRID_STEP
                val worldZ = WORLD_ORIGIN + roomIndexZ * WORLD_GRID_STEP
                val cell = Vec2(worldX, worldZ)
                if (cell in knownWorldCells) continue

                val base = baseColorAt(mapData, worldX, worldZ) ?: continue
                if (base == 0) continue // genuinely still unrevealed to anyone
                // typeColors doesn't yet cover every shade Hypixel actually renders (real dungeon runs
                // have shown raw values like 17/49/50/119 alongside the documented ones) - rather than
                // dropping an otherwise-real, revealed room entirely just because its exact fill shade
                // isn't recognized (which silently discarded its cleared/secreted state too), fall back to
                // a generic Normal-room color so it still shows up and still gets checkmarked correctly.
                val type = typeColors[base] ?: RoomType.NORMAL

                cells[roomIndexX to roomIndexZ] = DiscoveredCell(
                    cell, type,
                    cleared = isRoomCleared(mapData, worldX, worldZ, type),
                    fullySecreted = isFullySecreted(mapData, worldX, worldZ, type)
                )
            }
        }

        // Union-find over the discovered cells: only checks the east/south neighbor from each cell (the
        // reverse west/north edge is the same edge, just walked from the other side) so each gap pixel is
        // only ever sampled once.
        val parent = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        fun find(k: Pair<Int, Int>): Pair<Int, Int> {
            var root = k
            while (parent[root] != root) root = parent.getValue(root)
            return root
        }
        fun union(a: Pair<Int, Int>, b: Pair<Int, Int>) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        cells.keys.forEach { parent[it] = it }

        for ((key, current) in cells) {
            val (cx, cz) = key
            val east = cx + 1 to cz
            val south = cx to cz + 1
            cells[east]?.let { neighbor ->
                if (neighbor.type == current.type && typeColors[gapColor(mapData, current.pos.x, current.pos.z, horizontal = true)] == current.type) union(key, east)
            }
            cells[south]?.let { neighbor ->
                if (neighbor.type == current.type && typeColors[gapColor(mapData, current.pos.x, current.pos.z, horizontal = false)] == current.type) union(key, south)
            }
        }

        return cells.keys.groupBy { find(it) }.map { (_, keys) ->
            val groupCells = keys.map { cells.getValue(it) }
            DiscoveredRoom(
                positions = groupCells.map { it.pos },
                type = groupCells.first().type,
                cleared = groupCells.any { it.cleared },
                fullySecreted = groupCells.any { it.fullySecreted }
            )
        }
    }

    private var lastDebugUnknownSummary: String? = null
    private data class CellSnapshot(val type: RoomType?, val rawCheckmark: Int?)
    private val lastCellSnapshots = mutableMapOf<Vec2, CellSnapshot>()

    // Independent of [findUnknownRooms] (which is called every render frame from DungeonMap and would
    // spam the webhook at that rate) - runs the same whole-grid scan, logging two things:
    // 1. An aggregate summary, once per rescan and only when it changes - quick at-a-glance sanity check.
    // 2. A per-cell line, including distance from the local player, every time an individual unknown
    //    cell's raw type/checkmark value actually changes. This is what actually answers "does a distant
    //    teammate's clear show up in the raw bytes before the local player gets close, or only once
    //    they're near it" - a question the aggregate summary and [dumpDebugGrid] (which only ever looks at
    //    the local player's own currentRoom) can't answer on their own.
    private fun debugDumpUnknownRooms(mapData: MapItemSavedData, knownWorldCells: Set<Vec2>) {
        if (!calibrated) return
        if (!ClickGUIModule.mapDebugMessages) return
        val player = mc.player

        var revealedButUnknown = 0
        val classifiedByType = mutableMapOf<RoomType, Int>()
        val unclassifiedBaseColors = mutableSetOf<Int>()

        for (roomIndexX in 0 until FLOOR_GRID_ROOMS) {
            for (roomIndexZ in 0 until FLOOR_GRID_ROOMS) {
                if (roomIndexX == 0 && roomIndexZ == 0) continue
                val worldX = WORLD_ORIGIN + roomIndexX * WORLD_GRID_STEP
                val worldZ = WORLD_ORIGIN + roomIndexZ * WORLD_GRID_STEP
                val cell = Vec2(worldX, worldZ)
                if (cell in knownWorldCells) continue

                val base = baseColorAt(mapData, worldX, worldZ) ?: continue
                if (base == 0) {
                    lastCellSnapshots.remove(cell) // back to unrevealed - reset so a future reveal logs fresh
                    continue
                }

                revealedButUnknown++
                val type = typeColors[base]
                if (type != null) classifiedByType[type] = (classifiedByType[type] ?: 0) + 1
                else unclassifiedBaseColors.add(base and 0xFF)

                val rawCheckmark = rawCheckmarkColor(mapData, worldX, worldZ)
                val snapshot = CellSnapshot(type, rawCheckmark)
                if (lastCellSnapshots[cell] == snapshot) continue
                lastCellSnapshots[cell] = snapshot

                val dist = player?.let { p ->
                    val dx = p.x - worldX; val dz = p.z - worldZ
                    kotlin.math.sqrt(dx * dx + dz * dz)
                }
                DiscordLogger.log(
                    "MapColorScanner cell($worldX,$worldZ) changed: base=${base and 0xFF} type=${type ?: "UNCLASSIFIED"} " +
                        "rawCheckmark=${rawCheckmark?.let { it and 0xFF }} playerDist=${dist?.let { "%.1f".format(it) } ?: "?"}"
                )
            }
        }

        val summary = "MapColorScanner.findUnknownRooms debug: revealedButUnknown=$revealedButUnknown classifiedByType=$classifiedByType unclassifiedBaseColors=$unclassifiedBaseColors"
        if (summary != lastDebugUnknownSummary) {
            lastDebugUnknownSummary = summary
            DiscordLogger.log(summary)
        }
    }

    private fun tick() {
        if (!DungeonUtils.inDungeons) return
        val due = tickCounter++ % RESCAN_INTERVAL_TICKS == 0 || forceRefresh
        if (!due) return
        forceRefresh = false

        val mapData = findDungeonMapData() ?: return
        lastMapData = mapData
        if (!calibrated && !calibrate(mapData)) return

        // Deliberately no exclusion for DungeonUtils.currentRoom here - the color genuinely reflects
        // real clear progress even for the room you're standing in, it isn't an entry-triggered overlay.
        // The same physical room can show up as more than one Room object across these three sources
        // (e.g. a stale passedRooms entry from before it became current, with a smaller/different tile
        // set than the live currentRoom instance) - grouping by name and merging their tiles before
        // picking the anchor avoids sampling a wrong/incomplete room object's own idea of "top-left".
        val known = (DungeonUtils.passedRooms + DungeonUtils.nearbyRooms + listOfNotNull(DungeonUtils.currentRoom))
            .groupBy { it.data.name }

        // Debug aid (see Developer Message setting): reports, once per rescan, whether the map's pixels
        // show any cell as revealed that we don't already know about (a teammate discovering/clearing a
        // room we've never been near), and whether its fill color could actually be classified into a
        // RoomType via [typeColors] - since [findUnknownRooms] silently skips anything it can't classify,
        // this is the only way to tell "nothing revealed by anyone else yet" apart from "revealed, but our
        // color table doesn't recognize the fill" from actual dungeon runs, without guessing blind.
        val knownCells = known.values.asSequence().flatten().flatMap { it.roomComponents.asSequence() }.mapTo(mutableSetOf()) { it.vec2 }
        debugDumpUnknownRooms(mapData, knownCells)

        // Debug aid (see Developer Message setting): dumps the raw color grid around the current room's
        // anchor on entry, and again the moment our own read of it flips cleared/uncleared - so a
        // before-and-after of the same room's real pixels shows up, not just a single snapshot that might
        // happen to be taken before the room actually clears. Uses the exact same merged anchor as the
        // detection loop below, so the dump always matches what's actually being sampled.
        val currentName = DungeonUtils.currentRoom?.data?.name
        val currentAnchor = currentName?.let { name -> known[name]?.asSequence()?.flatMap { it.roomComponents.asSequence() }?.minWithOrNull(compareBy({ it.z }, { it.x })) }
        if (currentName != null && currentAnchor != null) {
            val currentType = DungeonUtils.currentRoom!!.data.type
            val currentCleared = isRoomCleared(mapData, currentAnchor.x, currentAnchor.z, currentType)
            val enteredNewRoom = currentName != lastDebugRoomName
            if (enteredNewRoom || currentCleared != lastDebugClearedState) {
                lastDebugRoomName = currentName
                lastDebugClearedState = currentCleared
                dumpDebugGrid(mapData, currentName, currentType, currentAnchor)
            }
        }

        for ((name, instances) in known) {
            val roomType = instances.first().data.type
            val anchor = instances.asSequence().flatMap { it.roomComponents.asSequence() }
                .minWithOrNull(compareBy({ it.z }, { it.x })) ?: continue

            if (isRoomCleared(mapData, anchor.x, anchor.z, roomType)) clearedRoomNames.add(name)
            else clearedRoomNames.remove(name)

            if (isFullySecreted(mapData, anchor.x, anchor.z, roomType)) fullySecretedRoomNames.add(name)
            else fullySecretedRoomNames.remove(name)

            if (isOpenedAt(mapData, anchor.x, anchor.z)) openedRoomNames.add(name)
            else openedRoomNames.remove(name)
        }
    }

    private fun findDungeonMapData(): MapItemSavedData? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        val inventory = player.inventory

        // Hypixel always keeps the dungeon map in hotbar slot 9 (index 8) - checked first since that's
        // the one confirmed (via other, working dungeon-map addons) to keep receiving live server updates
        // for the whole team's progress, not just our own. Falls back to a full scan for anything else
        // that happens to be a MapItem, same as before, in case that slot convention ever changes.
        inventory.getItem(8).takeIf { it.item is MapItem }?.let { MapItem.getSavedData(it, level) }?.let { return it }

        val candidates = (0 until inventory.containerSize).asSequence().map { inventory.getItem(it) } + sequenceOf(player.offhandItem)
        for (stack in candidates) {
            if (stack.item !is MapItem) continue
            val data = MapItem.getSavedData(stack, level) ?: continue
            return data
        }
        return null
    }

    // The entrance room is always drawn as one uninterrupted run of COLOR_ENTRANCE_OR_GREEN pixels,
    // exactly roomPixelSize long (16 or 18, depending on Hypixel's current map render settings) - finding
    // it tells us both that scale and where the floor's grid actually starts on this specific map.
    private fun calibrate(mapData: MapItemSavedData): Boolean {
        var runStart = 0
        var runLength = 0
        var bestStart = 0
        var bestLength = 0

        for (i in mapData.colors.indices) {
            if (mapData.colors[i].toInt() == COLOR_ENTRANCE_OR_GREEN) {
                if (runLength == 0) runStart = i
                runLength++
                if (runLength > bestLength) {
                    bestLength = runLength
                    bestStart = runStart
                }
            } else {
                runLength = 0
            }
        }

        if (bestLength != 16 && bestLength != 18) return false

        roomPixelSize = bestLength
        val pitch = roomPixelSize + 4
        var originX = (bestStart % MAP_SIZE) % pitch
        var originZ = (bestStart / MAP_SIZE) % pitch

        // Smaller floors' room grid doesn't fill the whole 128x128 canvas, and Hypixel centers it rather
        // than leaving it flush against the corner this run-length scan alone would derive - shift the
        // origin by one more full room+gap pitch whenever at least two pitches of canvas are left over
        // after the floor's own known max grid size, matching how much slack space is actually unused.
        val (roomsWide, roomsHigh) = floorRoomGrid[DungeonUtils.floor?.floorNumber] ?: (FLOOR_GRID_ROOMS to FLOOR_GRID_ROOMS)
        val mapWidth = pitch * (roomsWide - 1) + roomPixelSize
        val mapHeight = pitch * (roomsHigh - 1) + roomPixelSize
        if (MAP_SIZE - mapWidth >= pitch * 2) originX += pitch
        if (MAP_SIZE - mapHeight >= pitch * 2) originZ += pitch

        originPixelX = originX
        originPixelZ = originZ
        calibrated = true
        return true
    }

    // worldX/worldZ here are always a room's own center coordinate (an exact multiple of WORLD_GRID_STEP
    // away from WORLD_ORIGIN - see ScanUtils.getRoomCenter), never an arbitrary continuous position, so
    // the room index below is always exact.
    private fun blockStart(worldX: Int, worldZ: Int): Pair<Int, Int> {
        val roomIndexX = (worldX - WORLD_ORIGIN) / WORLD_GRID_STEP
        val roomIndexZ = (worldZ - WORLD_ORIGIN) / WORLD_GRID_STEP
        val pitch = roomPixelSize + 4
        return (originPixelX + roomIndexX * pitch) to (originPixelZ + roomIndexZ * pitch)
    }

    // Exposed as a DungeonMap setting ("Use Noamm Checkmark Position") so the two candidate checkmark
    // positions below can be A/B tested live in-game instead of guessed at - see [checkmarkPixel].
    @Volatile
    var useExactCenterCheckmark = false

    // The room's own cleared/secreted checkmark isn't drawn at the block's exact geometric center - it's
    // offset by (-1, -1+2) from it. Missing this by even a pixel means landing on the plain background
    // fill instead of the checkmark, which is what made detection unreliable (and also why scanning the
    // whole block for a match seemed necessary before - that just papered over the wrong single point by
    // widening the net, which then falsely matched other things, e.g. a live player-position marker
    // passing through the same block). NoammAddons' own HotbarMapColorParser samples the exact center
    // instead (no offset) - [useExactCenterCheckmark] switches to that for testing which is actually
    // correct on this Hypixel/MC version, without risking regressing the offset version that's already
    // confirmed working for the local player's own rooms.
    private fun checkmarkPixel(worldX: Int, worldZ: Int): Pair<Int, Int> {
        val (blockStartX, blockStartZ) = blockStart(worldX, worldZ)
        return if (useExactCenterCheckmark) (blockStartX + roomPixelSize / 2) to (blockStartZ + roomPixelSize / 2)
        else (blockStartX + roomPixelSize / 2 - 1) to (blockStartZ + roomPixelSize / 2 + 1)
    }

    private fun rawCheckmarkColor(mapData: MapItemSavedData, worldX: Int, worldZ: Int): Int? {
        val (pixelX, pixelZ) = checkmarkPixel(worldX, worldZ)
        if (pixelX !in 0 until MAP_SIZE || pixelZ !in 0 until MAP_SIZE) return null
        return mapData.colors[pixelZ * MAP_SIZE + pixelX].toInt()
    }

    // The room's own plain fill color, sampled at the block's top-left corner (no offset) - used only as
    // a reference to tell a real checkmark apart from the checkmark pixel just showing the background
    // through, matching Devonian's own gating (their roomCol/centerCol comparison).
    private fun baseColorAt(mapData: MapItemSavedData, worldX: Int, worldZ: Int): Int? {
        val (blockStartX, blockStartZ) = blockStart(worldX, worldZ)
        if (blockStartX !in 0 until MAP_SIZE || blockStartZ !in 0 until MAP_SIZE) return null
        return mapData.colors[blockStartZ * MAP_SIZE + blockStartX].toInt()
    }

    // A checkmark pixel only actually means something if it differs from the room's own plain base
    // color - otherwise nothing is drawn there and we're just reading the background fill through, which
    // shouldn't be interpreted as any particular checkmark state.
    private fun colorAt(mapData: MapItemSavedData, worldX: Int, worldZ: Int): Int? {
        val checkmark = rawCheckmarkColor(mapData, worldX, worldZ) ?: return null
        val base = baseColorAt(mapData, worldX, worldZ) ?: return null
        return if (checkmark == base) null else checkmark
    }

    // Prints the raw color bytes in and around the current room's own pixel block, one row per chat/log
    // line, with the exact checkmark pixel isRoomCleared/isFullySecreted actually sample bracketed - e.g.
    // "[ 34]" - so it's obvious at a glance whether that specific point is reading correctly.
    private fun dumpDebugGrid(mapData: MapItemSavedData, roomName: String, roomType: RoomType, anchor: RoomComponent) {
        if (!ClickGUIModule.mapDebugMessages) return
        val (blockStartX, blockStartZ) = blockStart(anchor.x, anchor.z)
        val (sampleX, sampleZ) = checkmarkPixel(anchor.x, anchor.z)
        val rawCheckmark = rawCheckmarkColor(mapData, anchor.x, anchor.z)
        val base = baseColorAt(mapData, anchor.x, anchor.z)

        val pad = 4
        val fromX = (blockStartX - pad).coerceAtLeast(0)
        val toX = (blockStartX + roomPixelSize + pad).coerceAtMost(MAP_SIZE - 1)
        val fromZ = (blockStartZ - pad).coerceAtLeast(0)
        val toZ = (blockStartZ + roomPixelSize + pad).coerceAtMost(MAP_SIZE - 1)

        val sb = StringBuilder()
        sb.append("MapColorScanner: room='$roomName' type=$roomType roomPixelSize=$roomPixelSize sample=($sampleX,$sampleZ) rawCheckmark=$rawCheckmark base=$base resolved=${colorAt(mapData, anchor.x, anchor.z)}\n")
        for (z in fromZ..toZ) {
            for (x in fromX..toX) {
                val c = mapData.colors[z * MAP_SIZE + x].toInt() and 0xFF
                sb.append(if (x == sampleX && z == sampleZ) "[%3d]".format(c) else " %3d ".format(c))
            }
            sb.append("\n")
        }
        // Straight to the Discord webhook, not devMessage()'s chat line - a multi-row grid dump is far
        // more readable there, and doesn't require the in-game "Developer Message" toggle at all (just
        // ClickGUIModule's debug webhook URL to be set).
        DiscordLogger.log(sb.toString())
    }

    // CLEARED is the plain "mobs dead" color; a non-entrance room reading the entrance's own color means
    // it's also fully secreted (Hypixel reuses one color for both) - either way, the room is done.
    private fun isRoomCleared(mapData: MapItemSavedData, worldX: Int, worldZ: Int, roomType: RoomType): Boolean {
        val color = colorAt(mapData, worldX, worldZ) ?: return false
        if (color == COLOR_CLEARED) return true
        return color == COLOR_ENTRANCE_OR_GREEN && roomType != RoomType.ENTRANCE
    }

    // The entrance's own block is legitimately solid COLOR_ENTRANCE_OR_GREEN, which would otherwise
    // always read as a false "fully secreted" - excluded the same way isRoomCleared excludes it.
    private fun isFullySecreted(mapData: MapItemSavedData, worldX: Int, worldZ: Int, roomType: RoomType): Boolean =
        roomType != RoomType.ENTRANCE && colorAt(mapData, worldX, worldZ) == COLOR_ENTRANCE_OR_GREEN

    // Reads the raw checkmark pixel directly rather than going through colorAt() - colorAt() nulls itself
    // out whenever the checkmark pixel happens to equal the room's own plain fill color, which is the
    // right call for isRoomCleared/isFullySecreted (a real checkmark is always visually distinct from the
    // fill), but wrong here: Hypixel draws an explicit, distinct "still locked" marker at that same pixel
    // (85 or 119) that can legitimately coincide with a Normal room's own fill color (85) by coincidence,
    // and colorAt() would incorrectly null that out as "no checkmark" rather than "unopened checkmark".
    private fun isOpenedAt(mapData: MapItemSavedData, worldX: Int, worldZ: Int): Boolean {
        val checkmark = rawCheckmarkColor(mapData, worldX, worldZ) ?: return false
        return checkmark !in UNOPENED_CHECKMARK_COLORS
    }

    private fun reset() {
        calibrated = false
        tickCounter = 0
        lastMapData = null
        clearedRoomNames.clear()
        fullySecretedRoomNames.clear()
        openedRoomNames.clear()
        lastDebugRoomName = null
        lastDebugClearedState = null
        lastDebugUnknownSummary = null
        lastCellSnapshots.clear()
        forceRefresh = false
    }
}
