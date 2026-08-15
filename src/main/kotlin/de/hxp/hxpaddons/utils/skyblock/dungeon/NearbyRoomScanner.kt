package de.hxp.hxpaddons.utils.skyblock.dungeon

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.utils.Vec2
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.Room
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomComponent
import net.minecraft.client.multiplayer.ClientLevel

/**
 * Passively scans a window of grid cells around the player every couple of seconds, so rooms are known
 * as soon as they're nearby - not only once actually entered. Unlike [ScanUtils]'s reactive scan (which
 * flood-fills a whole multi-tile room outward from wherever the player is standing), each cell here is
 * classified independently via its own core hash - so a multi-tile room doesn't need all of its cells in
 * range (or even a single specific "seed" cell) to be recognized; whichever one cell happens to be in
 * range and loaded is enough to start building it, and further cells get merged in as they come into
 * range too, in this or a later scan.
 */
object NearbyRoomScanner {
    // In grid cells (32 blocks each) - bounded in practice by how far chunks are actually loaded
    // around the player (client render distance / server view distance), not by this number itself.
    private const val RADIUS_CELLS = 5
    private const val GRID_STEP = 32
    private const val INTERVAL_TICKS = 40

    private var tickCounter = 0
    private val found = mutableMapOf<Vec2, Room>()

    val rooms: Collection<Room> get() = found.values.distinctBy { it.data.name }

    init {
        on<TickEvent.End> { tick() }
        on<WorldEvent.Load> { reset() }
    }

    private fun tick() {
        if (!DungeonUtils.inDungeons) {
            if (found.isNotEmpty()) found.clear()
            return
        }
        if (tickCounter++ % INTERVAL_TICKS != 0) return
        val level = mc.level ?: return
        val player = mc.player ?: return
        val center = ScanUtils.getRoomCenter(player.x.toInt(), player.z.toInt())

        for (dx in -RADIUS_CELLS..RADIUS_CELLS) {
            for (dz in -RADIUS_CELLS..RADIUS_CELLS) {
                val cell = Vec2(center.x + dx * GRID_STEP, center.z + dz * GRID_STEP)
                if (cell in found) continue
                try {
                    classifyCell(cell, level)
                } catch (_: Exception) {
                    // A single bad/unloaded cell shouldn't abort the rest of the scan window.
                }
            }
        }
    }

    private fun classifyCell(cell: Vec2, level: ClientLevel) {
        val chunk = level.getChunk(cell.x shr 4, cell.z shr 4)
        val roomHeight = ScanUtils.getTopLayerOfRoom(cell, chunk)
        val core = ScanUtils.getCoreAtHeight(cell, roomHeight, chunk)
        val roomData = ScanUtils.coreToRoomData[core] ?: return
        val component = RoomComponent(cell.x, cell.z, core)

        val existingRoom = found.values.find { it.data.name == roomData.name }
        val room = existingRoom?.also { it.roomComponents.add(component) }
            ?: Room(data = roomData, roomComponents = mutableSetOf(component))

        ScanUtils.updateRotation(room, roomHeight)
        found[cell] = room
    }

    private fun reset() = found.clear()
}
