package de.hxp.hxpaddons.utils.skyblock.dungeon

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.utils.Vec2
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.Door
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.DoorType
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.Room
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomComponent
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import kotlin.math.abs

/**
 * Two grid-adjacent room cells (32 blocks apart, see [ScanUtils]) are only actually connected by a door
 * if the world agrees: the column exactly between them (16 blocks from each) is checked the same way
 * [ScanUtils] already determines room floor height, and a real doorway has a distinctly different roof
 * height there (73/74/81/82, one block short of a normal wall) versus a plain wall separating two rooms
 * that just happen to sit next to each other. Door type is likewise read off a real block at that
 * midpoint rather than guessed from the adjoining rooms' types, since Hypixel marks door variants with
 * a specific block at y=69 (coal for wither, infested chiseled stone bricks for entrance, red terracotta
 * for blood).
 */
object DoorScanUtils {
    private const val GRID_STEP = 32
    private const val RESCAN_INTERVAL_TICKS = 20
    private val DOORWAY_HEIGHTS = setOf(73, 74, 81, 82)
    private const val DOOR_TYPE_CHECK_Y = 69

    private val doorMap = mutableMapOf<Pair<RoomComponent, RoomComponent>, Door>()
    val doors: Set<Door> get() = doorMap.values.toSet()

    private var tickCounter = 0

    init {
        on<TickEvent.End> {
            if (tickCounter++ % RESCAN_INTERVAL_TICKS != 0) return@on
            scanAll()
        }

        on<WorldEvent.Load> {
            doorMap.clear()
            tickCounter = 0
        }
    }

    private fun scanAll() {
        val known = (DungeonUtils.passedRooms + listOfNotNull(DungeonUtils.currentRoom) + DungeonUtils.nearbyRooms)
            .distinctBy { it.data.name }
        for (i in known.indices) {
            for (j in i + 1 until known.size) {
                scanPair(known[i], known[j])
            }
        }
        refreshWitherDoors()
    }

    private fun scanPair(roomA: Room, roomB: Room) {
        for (a in roomA.roomComponents) {
            for (b in roomB.roomComponents) {
                if (!isAdjacent(a, b)) continue
                val key = normalizedKey(a, b)
                if (key in doorMap) continue
                if (!isRealDoorway(a, b)) continue

                val midX = (a.x + b.x) / 2
                val midZ = (a.z + b.z) / 2
                val type = doorTypeAt(midX, midZ)
                // Every door type except Wither is passable the instant it's discovered - a Wither door
                // starts locked behind a solid coal-block wall, only opened later once a teammate uses a
                // key on it, so it can't default to true like the rest (that skipped [refreshWitherDoors]
                // ever having a reason to run - it always read as already-opened from the moment it was
                // first seen).
                doorMap[key] = Door(BlockPos(midX, DOOR_TYPE_CHECK_Y, midZ), key.first, key.second, type, opened = type != DoorType.WITHER)
            }
        }
    }

    // Unlike a door's type, whether a Wither door is still locked can change mid-run (once a teammate
    // uses a key on it, the coal-block wall disappears) - already-known doors are normally never
    // revisited by scanPair (it skips anything already in doorMap), so this is the only thing that keeps
    // re-checking a Wither door's block after it's first discovered, until it actually reads as opened.
    private fun refreshWitherDoors() {
        val level = mc.level ?: return
        for (door in doorMap.values) {
            if (door.type != DoorType.WITHER || door.opened) continue
            if (level.getBlockState(door.pos).block != Blocks.COAL_BLOCK) door.opened = true
        }
    }

    private fun isAdjacent(a: RoomComponent, b: RoomComponent): Boolean {
        val dx = b.x - a.x
        val dz = b.z - a.z
        return (abs(dx) == GRID_STEP && dz == 0) || (abs(dz) == GRID_STEP && dx == 0)
    }

    private fun isRealDoorway(a: RoomComponent, b: RoomComponent): Boolean {
        val level = mc.level ?: return false
        val midX = (a.x + b.x) / 2
        val midZ = (a.z + b.z) / 2
        val chunk = level.getChunk(midX shr 4, midZ shr 4)
        return ScanUtils.getTopLayerOfRoom(Vec2(midX, midZ), chunk) in DOORWAY_HEIGHTS
    }

    private fun doorTypeAt(midX: Int, midZ: Int): DoorType {
        val block = mc.level?.getBlockState(BlockPos(midX, DOOR_TYPE_CHECK_Y, midZ))?.block ?: return DoorType.NORMAL
        return when (block) {
            Blocks.COAL_BLOCK -> DoorType.WITHER
            Blocks.INFESTED_CHISELED_STONE_BRICKS -> DoorType.ENTRANCE
            Blocks.RED_TERRACOTTA -> DoorType.BLOOD
            else -> DoorType.NORMAL
        }
    }

    private fun normalizedKey(a: RoomComponent, b: RoomComponent): Pair<RoomComponent, RoomComponent> =
        if (a.x != b.x) (if (a.x < b.x) a to b else b to a)
        else (if (a.z < b.z) a to b else b to a)
}
