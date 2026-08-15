package de.hxp.hxpaddons.utils

import de.hxp.hxpaddons.HxPMod.mc
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sign

/**
 * Ported from NoammAddons' `EtherwarpHelper` (utils/items/EtherwarpHelper.kt): a voxel-traversal ray march
 * that finds the block your Aspect of the Void/End's Etherwarp would land you on, used by [de.hxp.hxpaddons.features.impl.skyblock.NoRotate]
 * to predict the teleport destination before the server confirms it.
 */
object EtherwarpHelper {
    private const val EYE_HEIGHT = 1.62
    private const val SNEAK_OFFSET = 0.35

    data class EtherPos(val succeeded: Boolean, val pos: BlockPos?) {
        val vec = pos?.let(::Vec3)

        companion object {
            val NONE = EtherPos(false, null)
        }
    }

    fun getEtherPos(pos: Vec3, lookVec: Vec3, distance: Double): EtherPos {
        val player = mc.player ?: return EtherPos.NONE
        val startPos = pos.addVec(y = EYE_HEIGHT - if (player.isCrouching) SNEAK_OFFSET else 0.0)
        val endPos = startPos.add(lookVec.scale(distance))
        return traverseVoxels(startPos, endPos)
    }

    private fun traverseVoxels(start: Vec3, end: Vec3): EtherPos {
        var x = floor(start.x).toInt()
        var y = floor(start.y).toInt()
        var z = floor(start.z).toInt()

        val endX = floor(end.x).toInt()
        val endY = floor(end.y).toInt()
        val endZ = floor(end.z).toInt()

        val dirX = end.x - start.x
        val dirY = end.y - start.y
        val dirZ = end.z - start.z

        val stepX = sign(dirX).toInt()
        val stepY = sign(dirY).toInt()
        val stepZ = sign(dirZ).toInt()

        val invDirX = if (dirX != 0.0) 1.0 / dirX else Double.MAX_VALUE
        val invDirY = if (dirY != 0.0) 1.0 / dirY else Double.MAX_VALUE
        val invDirZ = if (dirZ != 0.0) 1.0 / dirZ else Double.MAX_VALUE

        val tDeltaX = abs(invDirX * stepX)
        val tDeltaY = abs(invDirY * stepY)
        val tDeltaZ = abs(invDirZ * stepZ)

        var tMaxX = abs((x + max(stepX, 0) - start.x) * invDirX)
        var tMaxY = abs((y + max(stepY, 0) - start.y) * invDirY)
        var tMaxZ = abs((z + max(stepZ, 0) - start.z) * invDirZ)

        val currentPos = BlockPos.MutableBlockPos()

        repeat(1000) {
            currentPos.set(x, y, z)

            val chunk = mc.level?.getChunk(
                SectionPos.blockToSectionCoord(x),
                SectionPos.blockToSectionCoord(z)
            ) ?: return EtherPos.NONE

            val state = chunk.getBlockState(currentPos)

            if (isValidEtherwarpBlock(currentPos, chunk)) return EtherPos(true, currentPos.immutable())
            if (!isPassable(currentPos, chunk)) return EtherPos(false, currentPos.immutable())
            if (x == endX && y == endY && z == endZ) return if (state.isAir) EtherPos.NONE else EtherPos(false, currentPos.immutable())

            when {
                tMaxX <= tMaxY && tMaxX <= tMaxZ -> {
                    tMaxX += tDeltaX
                    x += stepX
                }

                tMaxY <= tMaxZ -> {
                    tMaxY += tDeltaY
                    y += stepY
                }

                else -> {
                    tMaxZ += tDeltaZ
                    z += stepZ
                }
            }
        }

        return EtherPos.NONE
    }

    private fun isValidEtherwarpBlock(pos: BlockPos, chunk: LevelChunk): Boolean {
        val level = mc.level ?: return false
        if (isPassable(pos, chunk)) return false

        val state = chunk.getBlockState(pos)
        val collisionTop = state.getCollisionShape(level, pos, CollisionContext.empty()).max(Direction.Axis.Y)
        val clearanceBaseY = pos.y + max(1, ceil(collisionTop).toInt())

        val feetPos = BlockPos(pos.x, clearanceBaseY, pos.z)
        if (!isPassable(feetPos, chunk) || isBlocksFeet(feetPos, chunk)) return false

        val headPos = BlockPos(pos.x, clearanceBaseY + 1, pos.z)
        return !(!isPassable(headPos, chunk) || isBlocksFeet(headPos, chunk))
    }

    private fun isBlocksFeet(pos: BlockPos, chunk: LevelChunk): Boolean {
        return when (chunk.getBlockState(pos).block) {
            is SkullBlock, is WallSkullBlock -> true
            is FlowerPotBlock -> true
            is LadderBlock -> true
            is VineBlock -> true
            else -> false
        }
    }

    private fun isPassable(pos: BlockPos, chunk: LevelChunk): Boolean {
        val level = mc.level ?: return true
        val state = chunk.getBlockState(pos)
        return when (state.block) {
            is FlowerPotBlock -> true
            is LadderBlock -> true
            is SignBlock -> false
            else -> state.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty
        }
    }
}
