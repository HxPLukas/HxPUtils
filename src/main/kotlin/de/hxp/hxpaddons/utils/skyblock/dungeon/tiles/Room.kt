package de.hxp.hxpaddons.utils.skyblock.dungeon.tiles

import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonWaypoint
import de.hxp.hxpaddons.utils.Vec2
import net.minecraft.core.BlockPos

// Plain class, not a data class: Room is stored as an element in MutableSet<Room> (ScanUtils.passedRooms)
// while secretsFound/waypoints mutate over its lifetime. A data class's generated equals/hashCode use
// those mutable fields, which would corrupt the set's hash buckets the moment they change after insertion.
// Identity equality (the default for a plain class) is also what we actually want here: two Room instances
// are only ever "the same room" if they're the same physical instance, never by structural comparison.
class Room(
    var rotation: Rotations = Rotations.NONE,
    var data: RoomData,
    var clayPos: BlockPos = BlockPos(0, 0, 0),
    val roomComponents: MutableSet<RoomComponent>,
    var waypoints: MutableSet<DungeonWaypoint> = mutableSetOf(),
    var secretsFound: Int = 0,
)

data class RoomComponent(val x: Int, val z: Int, val core: Int = 0) {
    val vec2 = Vec2(x, z)
    val blockPos = BlockPos(x, 70, z)
}

data class RoomData(
    val name: String, val type: RoomType, val cores: List<Int>,
    val crypts: Int, val secrets: Int, val trappedChests: Int, val shape: RoomShape
)