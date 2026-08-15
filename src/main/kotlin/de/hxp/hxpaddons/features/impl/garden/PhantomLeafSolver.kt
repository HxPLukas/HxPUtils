package de.hxp.hxpaddons.features.impl.garden

import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import kotlinx.coroutines.launch
import de.hxp.hxpaddons.events.ChatPacketEvent
import de.hxp.hxpaddons.events.RenderEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.events.core.onReceive
import de.hxp.hxpaddons.clickgui.settings.impl.BooleanSetting
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.render.drawCustomBeacon
import de.hxp.hxpaddons.utils.render.drawWireFrameBox
import de.hxp.hxpaddons.utils.render.text
import de.hxp.hxpaddons.utils.skyblock.Island
import de.hxp.hxpaddons.utils.skyblock.LocationUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import kotlin.math.abs
import kotlin.math.sqrt

object PhantomLeafSolver : Module(
    name = "Phantom Leaf Solver",
    description = "Triangulates the exact Phantomleaf position in the Garden.",
    category = Category.GARDEN
) {
    val earlyWaypoints by BooleanSetting("Early Waypoints", false, desc = "Show all candidate waypoints from the start instead of only below 4.")
    val foundColor by ColorSetting("Found Color", Color(0, 255, 0), desc = "Beacon color when the Phantomleaf is found (1 candidate).")
    val candidateColor by ColorSetting("Candidate Color", Color(255, 165, 0), desc = "Beacon color for candidate waypoints.")
    val bestGuessColor by ColorSetting("Best Guess Color", Color(255, 255, 0), desc = "Beacon color for the best matching candidate when ≤4 remain.")
    val scanColor by ColorSetting("Scan Color", Color(0, 150, 255), desc = "Beacon color for suggested scan positions.")

    private data class Measurement(val blockX: Int, val blockZ: Int, val distance: Float)

    private val measurements = mutableListOf<Measurement>()
    private val collectedSounds = mutableListOf<Pair<Float, Float>>() // pitch to volume
    private var candidates = listOf<BlockPos>()

    @Volatile private var bestNextBlocks: List<BlockPos> = emptyList()
    @Volatile private var ticks = 0
    @Volatile private var readyPosition: Pair<Int, Int>? = null
    @Volatile private var solverActive = false

    val isActive get() = solverActive
    val currentCandidates get() = candidates
    val currentBestNextBlocks get() = bestNextBlocks
    val measurementCount get() = measurements.size
    val readyPos get() = readyPosition

    fun startSolverSim(waypointY: Int = WAYPOINT_Y) {
        waypointYOverride = if (waypointY != WAYPOINT_Y) waypointY else null
        softReset()
        solverActive = true
    }

    fun injectMeasurement(blockX: Int, blockZ: Int, distance: Float) {
        skipBlockValidation = true
        try {
            val m = Measurement(blockX, blockZ, distance)
            measurements.add(m)
            lastDistance = distance
            val newCandidates = if (measurements.size == 1) buildRing(m, TOLERANCE) else filterByMeasurement(m)
            if (newCandidates.isEmpty()) {
                measurements.removeLast()
                if (measurements.isEmpty()) softReset()
            } else {
                candidates = newCandidates
                val excl = pendingBlockExclusion
                if (excl != null && m.blockX == excl.first && m.blockZ == excl.second && distance > 0.5f) {
                    candidates = candidates.filter { it.x != excl.first || it.z != excl.second }
                    pendingBlockExclusion = null
                }
                if (newCandidates.size > 1) {
                    val measSnapshot = measurements.toList()
                    bestNextBlocks = emptyList()
                    HxPMod.scope.launch { bestNextBlocks = calculateBestNextBlock(newCandidates, measSnapshot) }
                } else {
                    bestNextBlocks = emptyList()
                }
            }
        } finally {
            skipBlockValidation = false
        }
    }

    private var skipBlockValidation = false

    private var stillTicks = 0
    private var blockTicks = 0
    private var lastBlockPos = BlockPos(0, 0, 0)
    private var pendingBlockExclusion: Pair<Int, Int>? = null
    private var lastX = 0.0
    private var lastZ = 0.0
    private var lastDistance = -1f
    private var wasInGarden = false

    private var soundPos: Pair<Int, Int>? = null
    private var sound1Ticks = 0

    private val WAYPOINT_Y = 74
    private var waypointYOverride: Int? = null
    private val effectiveWaypointY get() = waypointYOverride ?: WAYPOINT_Y
    private val TOLERANCE = 0.5f

    private val SQRT2 = sqrt(2f)

    private fun toleranceFor(dist: Float) =
        if (abs(dist - 1.0f) < 0.15f || abs(dist - SQRT2) < 0.15f) 0f else TOLERANCE

    private fun snapDistance(dist: Float) = when {
        abs(dist - 1.0f) < 0.15f -> 1.0f
        abs(dist - SQRT2) < 0.15f -> SQRT2
        else -> dist
    }
    private val ORANGE_DIM = Color(255, 165, 0, 0.47f)

    val hud = +HUD(
        name = "Phantom Leaf Solver",
        desc = "Shows Phantomleaf distance and candidates.",
        toggleable = true
    ) { example ->
        if (!example && (!LocationUtils.isCurrentArea(Island.Garden) || !solverActive)) return@HUD 0 to 0
        if (example) {
            text("§6Phantomleaf: §e5.0 blocks", 0, 0)
            text("§6Candidates: §f4 §7(2 measurements)", 0, mc.font.lineHeight + 2)
            text("§aReady", 0, (mc.font.lineHeight + 2) * 2)
            return@HUD mc.font.width("§6Candidates: §f4 §7(2 measurements)") to (mc.font.lineHeight + 2) * 3
        }

        val lines = mutableListOf<String>()
        if (lastDistance >= 0f) lines.add("§6Phantomleaf: §e${"%.1f".format(lastDistance)} blocks")
        if (candidates.isNotEmpty()) lines.add("§6Candidates: §f${candidates.size} §7(${measurements.size} measurements)")
        val best = bestNextBlocks
        if (best.isNotEmpty()) lines.add("§bBest scan: §f${best.size} position${if (best.size > 1) "s" else ""}")
        lines.add(if (readyPosition != null) "§aReady" else "§cStand still")

        if (lines.isEmpty()) return@HUD 0 to 0
        val spacing = mc.font.lineHeight + 2
        var y = 0; var maxW = 0
        for (line in lines) {
            text(line, 0, y)
            maxW = maxOf(maxW, mc.font.width(line))
            y += spacing
        }
        maxW to y
    }

    init {
        on<TickEvent.End> {
            if (!enabled) return@on
            if (!LocationUtils.isCurrentArea(Island.Garden)) {
                if (wasInGarden) reset()
                wasInGarden = false
                return@on
            }
            wasInGarden = true
            val player = mc.player ?: return@on
            val px = player.x
            val pz = player.z
            val blockPos = player.blockPosition()

            val isStill = abs(px - lastX) < 0.01 && abs(pz - lastZ) < 0.01
            lastX = px; lastZ = pz

            if (blockPos == lastBlockPos) {
                blockTicks++
                if (blockTicks == 10) pendingBlockExclusion = Pair(blockPos.x, blockPos.z)
            } else {
                blockTicks = 0
                lastBlockPos = blockPos
                pendingBlockExclusion = null
            }

            if (isStill) {
                if (++stillTicks >= 4) readyPosition = Pair(blockPos.x, blockPos.z)
            } else {
                stillTicks = 0
                readyPosition = null
            }

            if (ticks > 0) {
                if (--ticks == 0) {
                    collectedSounds.clear()
                    soundPos = null
                    sound1Ticks = 0
                }
            }
            if (collectedSounds.size == 1 && sound1Ticks > 0 && --sound1Ticks == 0) {
                collectedSounds.clear()
                soundPos = null
            }
        }

        on<ChatPacketEvent> {
            if (!enabled || !LocationUtils.isCurrentArea(Island.Garden)) return@on
            when {
                value.contains("[CROP] Phantomleaf: Poof! Try and find me!") -> {
                    reset()
                    solverActive = true
                }
                value.contains("[CROP] Phantomleaf: You found me!") ||
                value.contains("[CROP] Phantomleaf: That's not me! Better luck next time!") -> {
                    solverActive = false
                    reset()
                }
            }
        }

        onReceive<ClientboundSoundPacket> {
            if (!enabled || !solverActive || ticks <= 0 || !LocationUtils.isCurrentArea(Island.Garden)) return@onReceive
            if (sound.value() == SoundEvents.NOTE_BLOCK_BASEDRUM.value() && collectedSounds.size < 2) {
                if (collectedSounds.isEmpty()) {
                    val pos = readyPosition ?: return@onReceive
                    soundPos = pos
                    sound1Ticks = 5
                }
                collectedSounds.add(Pair(pitch, volume))
                if (collectedSounds.size == 2 || volume > 0.995f) {
                    soundPos?.let { processMeasurement(it) }
                }
            }
        }

        onReceive<ClientboundSetSubtitleTextPacket> {
            if (!enabled || !solverActive || !LocationUtils.isCurrentArea(Island.Garden)) return@onReceive
            val subtitle = text.string.noControlCodes.trim()
            if (subtitle.startsWith("(") && subtitle.contains("❤") && subtitle.endsWith(")")) {
                ticks = 40
            }
        }

        on<RenderEvent.Extract> {
            if (!enabled || !solverActive || !LocationUtils.isCurrentArea(Island.Garden)) return@on
            val current = candidates
            if (current.isEmpty()) return@on

            if (current.size == 1) {
                drawCustomBeacon("§aPhantomleaf", current[0].cactusAdjusted(), foundColor, distance = true)
            } else {
                if (current.size <= 4 || earlyWaypoints) {
                    val bestGuesses = if (current.size <= 4 && measurements.isNotEmpty()) {
                        val residuals = current.map { pos ->
                            measurements.sumOf { m ->
                                val dx = (m.blockX - pos.x).toDouble()
                                val dz = (m.blockZ - pos.z).toDouble()
                                abs(sqrt(dx * dx + dz * dz) - m.distance).toDouble()
                            }
                        }
                        val minResidual = residuals.min()
                        current.filterIndexed { i, _ -> residuals[i] <= minResidual + 0.01 }.toSet()
                    } else emptySet()
                    for (pos in current) {
                        if (pos in bestGuesses) {
                            drawCustomBeacon("§ePhantomleaf", pos.cactusAdjusted(), bestGuessColor, distance = true)
                        } else {
                            drawCustomBeacon("§6Phantomleaf", pos.cactusAdjusted(), candidateColor, distance = true)
                        }
                    }
                }
                if (current.size > 4) {
                    val best = bestNextBlocks
                    if (best.isNotEmpty()) {
                        val player = mc.player
                        if (player != null) {
                            val px = player.x; val pz = player.z
                            val dists = best.map { b ->
                                val dx = b.x + 0.5 - px; val dz = b.z + 0.5 - pz
                                sqrt(dx * dx + dz * dz)
                            }
                            val minDist = dists.min()
                            best.forEachIndexed { i, b ->
                                if (dists[i] <= minDist + 0.01) drawCustomBeacon("§bScan here", b, scanColor, distance = true)
                            }
                        }
                    }
                }
            }
        }

        on<WorldEvent.Load> { reset() }
    }

    private val VALID_GROUND = setOf(Blocks.FARMLAND, Blocks.MYCELIUM, Blocks.SOUL_SAND, Blocks.END_STONE, Blocks.SAND)

    private fun isValidPos(pos: BlockPos): Boolean {
        if (skipBlockValidation) return true
        val below = mc.level?.getBlockState(BlockPos(pos.x, pos.y - 1, pos.z))?.block ?: return true
        return below in VALID_GROUND
    }

    private fun buildRing(m: Measurement, tol: Float = toleranceFor(m.distance)): List<BlockPos> {
        val radius = m.distance.toInt() + 2
        val result = mutableListOf<BlockPos>()
        for (bx in (m.blockX - radius)..(m.blockX + radius)) {
            for (bz in (m.blockZ - radius)..(m.blockZ + radius)) {
                val dx = (m.blockX - bx).toFloat()
                val dz = (m.blockZ - bz).toFloat()
                val pos = BlockPos(bx, effectiveWaypointY, bz)
                if (abs(sqrt(dx * dx + dz * dz) - m.distance) <= tol && isValidPos(pos)) {
                    result.add(pos)
                }
            }
        }
        return result
    }

    private fun filterByMeasurement(m: Measurement): List<BlockPos> {
        val tol = toleranceFor(m.distance)
        val effectiveDist = if (tol == 0f) snapDistance(m.distance) else m.distance
        return candidates.filter { pos ->
            val dx = (m.blockX - pos.x).toFloat()
            val dz = (m.blockZ - pos.z).toFloat()
            abs(sqrt(dx * dx + dz * dz) - effectiveDist) <= tol && isValidPos(pos)
        }
    }

    private fun estimateLeafCenter(meas: List<Measurement>, cands: List<BlockPos>): Pair<Double, Double> {
        if (meas.size >= 2) {
            val m1 = meas[0]; val m2 = meas[1]
            val dx = (m2.blockX - m1.blockX).toDouble()
            val dz = (m2.blockZ - m1.blockZ).toDouble()
            val d = sqrt(dx * dx + dz * dz)
            val d1 = m1.distance.toDouble(); val d2 = m2.distance.toDouble()
            val a = (d1 * d1 - d2 * d2 + d * d) / (2 * d)
            val hSq = d1 * d1 - a * a
            if (hSq >= 0) {
                val h = sqrt(hSq)
                val mx = m1.blockX + a * dx / d; val mz = m1.blockZ + a * dz / d
                val p1x = mx + h * dz / d; val p1z = mz - h * dx / d
                val p2x = mx - h * dz / d; val p2z = mz + h * dx / d
                val cx = cands.map { it.x }.average(); val cz = cands.map { it.z }.average()
                val dist1 = (p1x - cx) * (p1x - cx) + (p1z - cz) * (p1z - cz)
                val dist2 = (p2x - cx) * (p2x - cx) + (p2z - cz) * (p2z - cz)
                return if (dist1 < dist2) Pair(p1x, p1z) else Pair(p2x, p2z)
            }
        }
        return Pair(cands.map { it.x }.average(), cands.map { it.z }.average())
    }

    private fun calculateBestNextBlock(cands: List<BlockPos>, meas: List<Measurement>): List<BlockPos> {
        if (cands.isEmpty()) return emptyList()

        val (lx, lz) = estimateLeafCenter(meas, cands)
        val ringRadius = cands.map { c ->
            val dx = lx - c.x; val dz = lz - c.z
            sqrt(dx * dx + dz * dz)
        }.average()

        val searchRadius = ringRadius.toInt().coerceAtMost(25)
        val lxi = lx.toInt(); val lzi = lz.toInt()

        var bestWorstCase = Int.MAX_VALUE
        val bestPositions = mutableListOf<BlockPos>()

        for (bx in (lxi - searchRadius)..(lxi + searchRadius)) {
            for (bz in (lzi - searchRadius)..(lzi + searchRadius)) {
                val ddx = bx - lx; val ddz = bz - lz
                if (sqrt(ddx * ddx + ddz * ddz) >= ringRadius) continue

                val groups = HashMap<Int, Int>()
                for (c in cands) {
                    val dx = (bx - c.x).toFloat()
                    val dz = (bz - c.z).toFloat()
                    val bucket = (sqrt(dx * dx + dz * dz) / TOLERANCE).toInt()
                    groups[bucket] = (groups[bucket] ?: 0) + 1
                }
                val worstCase = groups.values.maxOrNull() ?: 0
                when {
                    worstCase < bestWorstCase -> {
                        bestWorstCase = worstCase
                        bestPositions.clear()
                        bestPositions.add(BlockPos(bx, effectiveWaypointY, bz))
                    }
                    worstCase == bestWorstCase -> bestPositions.add(BlockPos(bx, effectiveWaypointY, bz))
                }
            }
        }
        return bestPositions
    }

    private fun processMeasurement(pos: Pair<Int, Int>) {
        val volume = collectedSounds.minByOrNull { it.first }?.second ?: collectedSounds.first().second
        val dist = 30f * (1f - volume)
        ticks = 0
        collectedSounds.clear()
        soundPos = null
        sound1Ticks = 0
        val m = Measurement(pos.first, pos.second, dist)
        measurements.add(m)
        lastDistance = dist
        val newCandidates = if (measurements.size == 1) buildRing(m, TOLERANCE) else filterByMeasurement(m)
        if (newCandidates.isEmpty()) {
            measurements.removeLast()
            if (measurements.isEmpty()) softReset()
        } else {
            candidates = newCandidates
            val excl = pendingBlockExclusion
            if (excl != null && m.blockX == excl.first && m.blockZ == excl.second && dist > 0.5f) {
                candidates = candidates.filter { it.x != excl.first || it.z != excl.second }
                pendingBlockExclusion = null
            }
            if (newCandidates.size > 1) {
                val measSnapshot = measurements.toList()
                bestNextBlocks = emptyList()
                HxPMod.scope.launch { bestNextBlocks = calculateBestNextBlock(newCandidates, measSnapshot) }
            } else {
                bestNextBlocks = emptyList()
            }
        }
    }

    private fun BlockPos.cactusAdjusted(): BlockPos =
        if (mc.level?.getBlockState(this)?.block == Blocks.CACTUS) BlockPos(x, y + 3, z) else this

    fun softReset() {
        measurements.clear()
        collectedSounds.clear()
        candidates = emptyList()
        bestNextBlocks = emptyList()
        lastDistance = -1f
        ticks = 0
        soundPos = null
        sound1Ticks = 0
        stillTicks = 0
        blockTicks = 0
        lastBlockPos = BlockPos(0, 0, 0)
        readyPosition = null
        pendingBlockExclusion = null
    }

    fun reset() {
        softReset()
        solverActive = false
        waypointYOverride = null
        lastX = 0.0; lastZ = 0.0
        wasInGarden = false
    }
}
