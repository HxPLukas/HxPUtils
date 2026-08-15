package de.hxp.hxpaddons.features.impl.mining

import de.hxp.hxpaddons.HxPMod.scope
import de.hxp.hxpaddons.events.ChunkLoadEvent
import de.hxp.hxpaddons.events.RenderEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.alert
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
import kotlin.math.max

/**
 * Standalone, lightweight alternative to [CrystalHollowsStructureFinder] for people who only care
 * about the rare Golden Dragon mob spawn spot and don't want the other 30 landmark markers cluttering
 * their screen while mining. Reuses the same block-pattern matcher, just scoped to one structure, and
 * fires a title+sound alert the moment it's found instead of a plain chat message.
 */
object GoldenDragonFinder : Module(
    name = "Golden Dragon Finder",
    description = "Scans loaded chunks in Crystal Hollows for the Golden Dragon spawn point only, with a title/sound alert once found.",
    category = Category.custom("Mining")
) {
    private val scannedChunks = ConcurrentHashMap.newKeySet<Long>()
    @Volatile
    private var foundPos: BlockPos? = null

    init {
        on<ChunkLoadEvent> {
            if (foundPos != null) return@on
            if (!LocationUtils.isCurrentArea(Island.CrystalHollows)) return@on
            if (!scannedChunks.add(chunk.pos.pack())) return@on

            // Off the render thread - see CrystalHollowsStructureFinder for why.
            val loadedChunk = chunk
            scope.launch {
                CrystalHollowsStructureScanner.scanChunk(
                    chunk = loadedChunk,
                    structures = listOf(CrystalHollowsStructure.GOLDEN_DRAGON, CrystalHollowsStructure.GOLDEN_DRAGON_ALT),
                    alreadyFound = { _, _ -> foundPos != null },
                    onFound = { _, pos ->
                        foundPos = pos
                        mc.execute { alert("§6Golden Dragon found!") }
                    }
                )
            }
        }

        on<RenderEvent.Extract> {
            val pos = foundPos ?: return@on
            val dist = mc.player?.blockPosition()?.distManhattan(pos) ?: 0
            drawFilledBox(AABB(pos), CrystalHollowsStructure.GOLDEN_DRAGON.colour, depth = false)
            drawText(CrystalHollowsStructure.GOLDEN_DRAGON.displayName, pos.center, max(1f, dist * 0.03f), false)
        }

        on<WorldEvent.Load> {
            scannedChunks.clear()
            foundPos = null
        }
    }
}
