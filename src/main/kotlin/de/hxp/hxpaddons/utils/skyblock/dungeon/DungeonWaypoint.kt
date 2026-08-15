package de.hxp.hxpaddons.utils.skyblock.dungeon

import de.hxp.hxpaddons.utils.Color
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB

data class DungeonWaypoint(
    val blockPos: BlockPos,
    val color: Color,
    val aabb: AABB = AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
    val filled: Boolean = false,
    val depth: Boolean = true,
    var isClicked: Boolean = false,
)
