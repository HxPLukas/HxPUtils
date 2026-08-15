package de.hxp.hxpaddons.features.impl.mining

import de.hxp.hxpaddons.HxPMod.scope
import de.hxp.hxpaddons.events.ChunkLoadEvent
import de.hxp.hxpaddons.events.RenderEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.render.drawFilledBox
import de.hxp.hxpaddons.utils.render.drawText
import de.hxp.hxpaddons.utils.skyblock.Island
import de.hxp.hxpaddons.utils.skyblock.LocationUtils
import de.hxp.hxpaddons.utils.skyblock.mining.CrystalHollowsStructure
import de.hxp.hxpaddons.utils.skyblock.mining.CrystalHollowsStructureScanner
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * from quoi (GPL-3.0)
 * original: https://github.com/pigeonlover1998/quoi/blob/26.1.x/src/main/kotlin/quoi/module/impl/mining/CrystalHollowsScanner.kt
 * (structure-scanning half only - the original's cobblestone route/path scanner wasn't ported)
 */
object CrystalHollowsStructureFinder : Module(
    name = "Crystal Hollows Structure Finder",
    description = "Scans loaded chunks in Crystal Hollows for landmark structures (Mines of Divan, grottos, mob camps, Golden Dragon, ...) and marks them once found.",
    category = Category.custom("Mining")
) {
    private val scannedChunks = ConcurrentHashMap.newKeySet<Long>()
    private val found = ConcurrentHashMap<CrystalHollowsStructure, MutableList<BlockPos>>()

    /** Read-only view of everything found so far this world, for [CrystalHollowsMap] to plot. */
    val foundStructures: Map<CrystalHollowsStructure, List<BlockPos>> get() = found

    init {
        on<ChunkLoadEvent> {
            if (!LocationUtils.isCurrentArea(Island.CrystalHollows)) return@on
            if (!scannedChunks.add(chunk.pos.pack())) return@on

            // Scanning a chunk is up to 16x16x150 block-state lookups - done off the render thread
            // (same as quoi's own Dispatchers.IO scan) so it can't tank FPS while flying around and
            // streaming in new chunks.
            val loadedChunk = chunk
            scope.launch {
                CrystalHollowsStructureScanner.scanChunk(
                    chunk = loadedChunk,
                    structures = CrystalHollowsStructure.entries,
                    alreadyFound = ::isAlreadyFound,
                    onFound = { structure, pos ->
                        found.computeIfAbsent(structure) { mutableListOf() }.add(pos)
                        modMessage("Found ${structure.displayName} at ${pos.x}, ${pos.y}, ${pos.z}")
                    }
                )
            }
        }

        on<RenderEvent.Extract> {
            found.forEach { (structure, positions) ->
                positions.forEach { pos ->
                    val dist = mc.player?.blockPosition()?.distManhattan(pos) ?: 0
                    drawFilledBox(AABB(pos), structure.colour, depth = false)
                    drawText(structure.displayName, pos.center, max(1f, dist * 0.03f), false)
                }
            }
        }

        on<WorldEvent.Load> {
            scannedChunks.clear()
            found.clear()
        }
    }

    private fun isAlreadyFound(structure: CrystalHollowsStructure, pos: BlockPos): Boolean {
        val existing = found[structure] ?: return false
        return if (structure.canBeMultiple) existing.any { it.isWithinChunks(pos, 4) }
        else existing.isNotEmpty()
    }

    private fun BlockPos.isWithinChunks(other: BlockPos, chunks: Int): Boolean {
        val dx = (x shr 4) - (other.x shr 4)
        val dz = (z shr 4) - (other.z shr 4)
        return abs(dx) <= chunks && abs(dz) <= chunks
    }
}
