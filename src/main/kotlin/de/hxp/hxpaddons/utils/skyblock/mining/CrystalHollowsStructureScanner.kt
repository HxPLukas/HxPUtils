package de.hxp.hxpaddons.utils.skyblock.mining

import net.minecraft.core.BlockPos
import net.minecraft.world.level.chunk.LevelChunk

/**
 * Pattern-matches [CrystalHollowsStructure] block columns against a freshly loaded chunk.
 *
 * from quoi (GPL-3.0), adapted from the chunk-scanning half of
 * https://github.com/pigeonlover1998/quoi/blob/26.1.x/src/main/kotlin/quoi/module/impl/mining/CrystalHollowsScanner.kt
 * - split out standalone so both [de.hxp.hxpaddons.features.impl.mining.CrystalHollowsStructureFinder] and
 * [de.hxp.hxpaddons.features.impl.mining.GoldenDragonFinder] can reuse it without duplicating the matcher.
 */
object CrystalHollowsStructureScanner {

    /**
     * Scans every column in [chunk] for each of [structures], skipping columns [alreadyFound] rejects
     * (e.g. because that structure - or one too close to it, for [CrystalHollowsStructure.canBeMultiple]
     * ones - was already recorded) and invoking [onFound] with the offset-corrected world position once
     * a column matches.
     */
    fun scanChunk(
        chunk: LevelChunk,
        structures: List<CrystalHollowsStructure>,
        fromY: Int = 30,
        toY: Int = 180,
        alreadyFound: (CrystalHollowsStructure, BlockPos) -> Boolean,
        onFound: (CrystalHollowsStructure, BlockPos) -> Unit
    ) {
        for (x in 0..15) {
            for (z in 0..15) {
                for (y in fromY..toY) {
                    val pos = BlockPos(chunk.pos.minBlockX + x, y, chunk.pos.minBlockZ + z)

                    for (structure in structures) {
                        if (!structure.quarter.test(pos)) continue
                        if (alreadyFound(structure, pos)) continue

                        if (columnMatches(chunk, structure, x, y, z)) {
                            val realPos = pos.offset(structure.xOffset, structure.yOffset, structure.zOffset)
                            onFound(structure, realPos)
                        }
                    }
                }
            }
        }
    }

    private fun columnMatches(chunk: LevelChunk, structure: CrystalHollowsStructure, x: Int, startY: Int, z: Int): Boolean {
        if (startY + structure.blocks.size >= 180) return false

        structure.blocks.forEachIndexed { i, block ->
            if (block == null) return@forEachIndexed
            val pos = BlockPos(chunk.pos.minBlockX + x, startY + i, chunk.pos.minBlockZ + z)
            if (chunk.getBlockState(pos).block != block) return false
        }
        return true
    }
}
