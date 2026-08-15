package de.hxp.hxpaddons.utils.skyblock.dungeon.tiles

import net.minecraft.core.BlockPos

data class Door(
    val pos: BlockPos,
    val a: RoomComponent,
    val b: RoomComponent,
    var type: DoorType = DoorType.NORMAL,
    var opened: Boolean = false,
)

enum class DoorType {
    NORMAL, ENTRANCE, BLOOD, WITHER
}
