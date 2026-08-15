package de.hxp.hxpaddons.features.impl.dungeon

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.WipModule
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.events.ChatPacketEvent
import de.hxp.hxpaddons.events.RoomEnterEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.JsonResourceLoader
import de.hxp.hxpaddons.utils.devMessage
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.simulateRightClick
import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonUtils
import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonUtils.getRealCoords
import de.hxp.hxpaddons.utils.skyblock.dungeon.Puzzle
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.floor

/**
 * Solves supported dungeon puzzles and clicks the correct answer as soon as the crosshair hovers it.
 * Board/riddle detection geometry verified against NoammAddons (CC0, github.com/Noamm9/NoammAddons), the only
 * solver reference actually targeting this Minecraft version; logic reimplemented independently here.
 */
@WipModule
object PuzzleTriggerbot : Module(
    name = "Puzzle Triggerbot",
    description = "Solves supported puzzles and clicks the correct answer as soon as you hover it.",
    category = Category.DUNGEON
) {
    val clickCooldown by NumberSetting("Click Cooldown", default = 300, min = 50, max = 2000, increment = 10, desc = "Minimum time between two clicks.", unit = "ms")

    private var lastClickTime = 0L

    override fun onEnable() {
        super.onEnable()
        lastClickTime = 0L
        resetPuzzleState()
    }

    override fun onKeybind() {
        toggle()
        if (enabled) modMessage("§aPuzzle Triggerbot §2started§a.")
        else modMessage("§cPuzzle Triggerbot §4stopped§c.")
    }

    private fun resetPuzzleState() {
        ThreeWeirdos.reset()
        Boulder.reset()
        WaterBoard.reset()
    }

    init {
        on<WorldEvent.Load> {
            lastClickTime = 0L
            resetPuzzleState()
        }

        on<RoomEnterEvent> {
            resetPuzzleState()
        }

        on<ChatPacketEvent> {
            ThreeWeirdos.onChat(value)
        }

        on<TickEvent.End> {
            if (!enabled || !DungeonUtils.inDungeons) return@on
            val level = mc.level ?: return@on
            val hit = mc.hitResult ?: return@on

            val onClicked = when {
                TicTacToe.wantsClick(level, hit) -> TicTacToe::onClicked
                ThreeWeirdos.wantsClick(hit) -> ThreeWeirdos::onClicked
                Boulder.wantsClick(hit) -> Boulder::onClicked
                WaterBoard.wantsClick(level, hit) -> WaterBoard::onClicked
                else -> return@on
            }

            val now = System.currentTimeMillis()
            if (now - lastClickTime < clickCooldown.toLong()) return@on

            lastClickTime = now
            onClicked(hit)
            // Must run synchronously against the same hit: dispatching this to a background coroutine let the
            // crosshair move on to a different target before the OS-level click actually fired, so the wrong
            // lever/button ended up getting interacted with. Robot() is cached now (see PlayerUtils), so this
            // is cheap enough to not reintroduce the earlier render-thread hitch.
            simulateRightClick()
        }
    }

    private fun inPuzzleRoom(puzzle: Puzzle): Boolean {
        val room = DungeonUtils.currentRoom?.data ?: return false
        return room.type == RoomType.PUZZLE && room.name.equals(puzzle.displayName, ignoreCase = true)
    }

    /**
     * Board is drawn with item frames holding filled maps mounted on a wall; empty cells are a bare
     * §lStone Button§r (confirmed against NoammAddons' current TicTacToeSolver - not an Iron Block, which
     * is what older 1.8.9-era solvers assumed). The AI (X) always plays first.
     */
    private object TicTacToe {
        private const val EMPTY = 0
        private const val AI_MARK = 1
        private const val HUMAN_MARK = 2

        private const val MAP_COLOR_INDEX = 8256
        private const val MAP_COLOR_X = 114
        private const val MAP_COLOR_O = 33
        private const val TOP_ROW_Y = 72

        private const val SCAN_RADIUS = 20.0

        private data class FrameCell(val row: Int, val pos: BlockPos, val mark: Int, val facing: Direction)

        fun wantsClick(level: Level, hit: HitResult): Boolean {
            val bestMove = bestMoveBlockPos(level) ?: return false
            val hitPos = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos
            return hitPos == bestMove
        }

        fun onClicked(hit: HitResult) {
            devMessage("§bPuzzle Triggerbot: clicking Tic Tac Toe move at ${(hit as BlockHitResult).blockPos}.")
        }

        private fun bestMoveBlockPos(level: Level): BlockPos? {
            if (!inPuzzleRoom(Puzzle.TTT)) return null

            val frameCells = scanFrameCells(level)
            if (frameCells.isEmpty()) return null // AI hasn't made its first move yet

            val aiCount = frameCells.count { it.mark == AI_MARK }
            val humanCount = frameCells.count { it.mark == HUMAN_MARK }
            if (aiCount == humanCount) return null // AI's turn, nothing to click yet

            // The wall the frames hang on is perpendicular to their facing direction, so columns vary along
            // whichever horizontal axis the facing direction does NOT point along.
            val anchor = frameCells.first()
            val variesInZ = anchor.facing.axis == Direction.Axis.X
            val buttonPositions = scanButtonPositions(level, anchor, variesInZ)

            val varyingValues = (frameCells.map { it.pos } + buttonPositions)
                .map { if (variesInZ) it.z else it.x }
                .distinct().sorted()
            if (varyingValues.size < 2) return null // not enough of the board resolved yet

            fun columnOf(pos: BlockPos): Int? =
                varyingValues.indexOf(if (variesInZ) pos.z else pos.x).takeIf { it >= 0 }

            val board = IntArray(9) { EMPTY }
            val positionByIndex = arrayOfNulls<BlockPos>(9)
            for (cell in frameCells) {
                val col = columnOf(cell.pos) ?: continue
                val index = cell.row * 3 + col
                board[index] = cell.mark
                positionByIndex[index] = cell.pos
            }
            for (pos in buttonPositions) {
                val col = columnOf(pos) ?: continue
                val index = (TOP_ROW_Y - pos.y) * 3 + col
                positionByIndex[index] = pos
            }

            if (winnerOf(board) != null || board.none { it == EMPTY }) return null // puzzle already decided

            val bestIndex = bestMoveIndex(board) ?: return null
            return positionByIndex[bestIndex]
        }

        private fun scanFrameCells(level: Level): List<FrameCell> {
            val player = mc.player ?: return emptyList()
            val area = AABB.ofSize(player.position(), SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)

            return level.getEntitiesOfClass(ItemFrame::class.java, area).mapNotNull { frame ->
                if (frame.rotation != 0 || frame.item.item != Items.FILLED_MAP) return@mapNotNull null
                val mark = markOf(level, frame) ?: return@mapNotNull null
                val wallPos = frame.pos.relative(frame.direction.opposite)
                FrameCell(TOP_ROW_Y - wallPos.y, wallPos, mark, frame.direction)
            }
        }

        private fun scanButtonPositions(level: Level, anchor: FrameCell, variesInZ: Boolean): List<BlockPos> {
            val anchorVarying = if (variesInZ) anchor.pos.z else anchor.pos.x
            val positions = mutableListOf<BlockPos>()
            for (y in (TOP_ROW_Y - 2)..TOP_ROW_Y) {
                for (v in (anchorVarying - 2)..(anchorVarying + 2)) {
                    val pos = if (variesInZ) BlockPos(anchor.pos.x, y, v) else BlockPos(v, y, anchor.pos.z)
                    if (level.getBlockState(pos).block == Blocks.STONE_BUTTON) positions += pos
                }
            }
            return positions
        }

        private fun markOf(level: Level, frame: ItemFrame): Int? {
            val mapId = frame.getFramedMapId(frame.item) ?: return null
            val colorByte = level.getMapData(mapId)?.colors?.get(MAP_COLOR_INDEX) ?: return null
            return when (colorByte.toInt() and 0xFF) {
                MAP_COLOR_X -> AI_MARK
                MAP_COLOR_O -> HUMAN_MARK
                else -> null
            }
        }

        private val LINES = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
        )

        private fun winnerOf(board: IntArray): Int? {
            for ((a, b, c) in LINES.map { Triple(it[0], it[1], it[2]) }) {
                if (board[a] != EMPTY && board[a] == board[b] && board[b] == board[c]) return board[a]
            }
            return null
        }

        private fun bestMoveIndex(board: IntArray): Int? {
            var bestScore = Int.MIN_VALUE
            var bestIndex: Int? = null
            for (i in 0..8) {
                if (board[i] != EMPTY) continue
                board[i] = HUMAN_MARK
                val score = minimax(board, 1, false)
                board[i] = EMPTY
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = i
                }
            }
            return bestIndex
        }

        private fun minimax(board: IntArray, depth: Int, maximizing: Boolean): Int {
            winnerOf(board)?.let { return if (it == HUMAN_MARK) 10 - depth else depth - 10 }
            if (board.none { it == EMPTY }) return 0

            val mark = if (maximizing) HUMAN_MARK else AI_MARK
            var best = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
            for (i in 0..8) {
                if (board[i] != EMPTY) continue
                board[i] = mark
                val score = minimax(board, depth + 1, !maximizing)
                board[i] = EMPTY
                best = if (maximizing) maxOf(best, score) else minOf(best, score)
            }
            return best
        }
    }

    /**
     * Three NPCs (ArmorStands) each recite a riddle (chat message "[NPC] <name>: <riddle>") when interacted
     * with. A known set of riddle phrasings unambiguously reveal which NPC is trustworthy; the reward chest
     * is adjacent to that NPC. Regex list verified against NoammAddons' current ThreeWeirdosSolver (their
     * "wrong" list is unneeded here - we only ever act on a confirmed-correct chest).
     */
    private object ThreeWeirdos {
        private val solutionPhrases = listOf(
            Regex("The reward is not in my chest!"),
            Regex("At least one of them is lying, and the reward is not in .+'s chest.?"),
            Regex("My chest doesn't have the reward. We are all telling the truth.?"),
            Regex("My chest has the reward and I'm telling the truth!"),
            Regex("The reward isn't in any of our chests.?"),
            Regex("Both of them are telling the truth. Also, .+ has the reward in their chest.?"),
        )
        private val npcMessageRegex = Regex("^\\[NPC] (\\w+): (.+)$")
        private val neighbors = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

        // NPCs stand above floor level; the reward chest sits next to them at this fixed floor height.
        private const val CHEST_FLOOR_Y = 69

        private const val SCAN_RADIUS = 20.0

        private val interactedEntities = mutableSetOf<Int>()
        private var correctChestPos: BlockPos? = null
        private var chestClicked = false

        fun reset() {
            interactedEntities.clear()
            correctChestPos = null
            chestClicked = false
        }

        fun onChat(message: String) {
            if (correctChestPos != null || !inPuzzleRoom(Puzzle.WEIRDOS)) return
            val match = npcMessageRegex.find(message) ?: return
            val npcName = match.groupValues[1]
            val riddle = match.groupValues[2]
            if (solutionPhrases.none { it.matches(riddle) }) return

            val level = mc.level ?: return
            val player = mc.player ?: return
            val area = AABB.ofSize(player.position(), SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)
            val npc = level.getEntitiesOfClass(ArmorStand::class.java, area) { it.customName?.string?.noControlCodes?.contains(npcName) == true }
                .firstOrNull() ?: return

            val npcFloorPos = BlockPos(floor(npc.x).toInt(), CHEST_FLOOR_Y, floor(npc.z).toInt())
            correctChestPos = neighbors.map { npcFloorPos.relative(it) }
                .firstOrNull { level.getBlockState(it).block.let { block -> block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST } }
            devMessage("§bPuzzle Triggerbot: $npcName has the blessing, chest at $correctChestPos.")
        }

        fun wantsClick(hit: HitResult): Boolean {
            if (!inPuzzleRoom(Puzzle.WEIRDOS)) return false
            return when {
                hit is EntityHitResult -> hit.entity is ArmorStand && hit.entity.id !in interactedEntities
                hit is BlockHitResult && hit.type == HitResult.Type.BLOCK -> !chestClicked && hit.blockPos == correctChestPos
                else -> false
            }
        }

        fun onClicked(hit: HitResult) {
            when (hit) {
                is EntityHitResult -> {
                    interactedEntities += hit.entity.id
                    devMessage("§bPuzzle Triggerbot: interacting with weirdo NPC ${hit.entity.id}.")
                }
                is BlockHitResult -> {
                    chestClicked = true
                    devMessage("§bPuzzle Triggerbot: clicking the correct Three Weirdos chest at ${hit.blockPos}.")
                }
                else -> {}
            }
        }
    }

    /**
     * Boulders must be pushed into place by clicking buttons/levers/signs in a specific order. The wall
     * facing the entrance is scanned into a 42-cell open/blocked pattern (7x6 grid, 3 blocks apart) and
     * looked up against a catalog of the small number of distinct layouts Hypixel actually generates.
     * Coordinates/scan order verified against the original OdinFabric (odtheking/OdinFabric, BSD-3-Clause,
     * the mod this project is forked from) BoulderSolver - the JSON catalog bundled in this project's
     * resources is the exact same file that mod ships.
     */
    private object Boulder {
        private const val SCAN_Y = 66
        private const val TARGET_Y = 65

        private val solutions: Map<String, List<List<Int>>> =
            JsonResourceLoader.loadJson("/assets/hxpaddons/puzzles/boulderSolutions.json", emptyMap())

        private var moves: MutableList<BlockPos> = mutableListOf()
        private var solved = false

        fun reset() {
            moves = mutableListOf()
            solved = false
        }

        fun wantsClick(hit: HitResult): Boolean {
            if (!inPuzzleRoom(Puzzle.BOULDER)) return false
            if (!solved) solve()

            val target = moves.firstOrNull() ?: return false
            return hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && hit.blockPos == target
        }

        fun onClicked(hit: HitResult) {
            if (moves.isNotEmpty()) moves.removeAt(0)
            devMessage("§bPuzzle Triggerbot: clicking Boulder step at ${(hit as BlockHitResult).blockPos}, ${moves.size} left.")
        }

        private fun solve() {
            val room = DungeonUtils.currentRoom ?: return
            val level = mc.level ?: return
            solved = true

            val pattern = buildString {
                for (z in 24 downTo 9 step 3) for (x in 24 downTo 6 step 3) {
                    val pos = room.getRealCoords(BlockPos(x, SCAN_Y, z))
                    append(if (level.getBlockState(pos).isAir) '0' else '1')
                }
            }

            val solution = solutions[pattern]
            if (solution == null) {
                devMessage("§cPuzzle Triggerbot: no known Boulder solution for pattern $pattern.")
                return
            }
            // Each entry is [renderX, renderZ, clickX, clickZ]; we only need the click position.
            moves = solution.map { room.getRealCoords(BlockPos(it[2], TARGET_Y, it[3])) }.toMutableList()
            devMessage("§bPuzzle Triggerbot: Boulder solved, ${moves.size} steps.")
        }
    }

    /**
     * Seven levers must be pulled at specific times (seconds since the WATER lever starts the countdown)
     * to fill five colored gates with water in the right order - i.e. click each lever once it becomes
     * "ready" per this timing table. Reference block positions verified against the original OdinFabric
     * (odtheking/OdinFabric, BSD-3-Clause, the mod this project is forked from) WaterSolver - the JSON
     * timing table bundled in this project's resources is the exact same file that mod ships.
     */
    private object WaterBoard {
        private const val LEVER_Y = 61
        private const val WATER_Y = 60

        // Matches upstream's own "Optimized Solutions" setting default (off) - a separate, equally valid
        // solution set exists under the "true" key if this ever needs to be exposed as its own toggle.
        private const val USE_OPTIMIZED = false

        private val solutions: Map<String, Map<String, Map<String, Map<String, List<Double>>>>> =
            JsonResourceLoader.loadJson("/assets/hxpaddons/puzzles/waterSolutions.json", emptyMap())

        private enum class Lever(val x: Int, val z: Int, val key: String) {
            COAL(20, 10, "coal_block"),
            GOLD(20, 15, "gold_block"),
            QUARTZ(20, 20, "quartz_block"),
            DIAMOND(10, 20, "diamond_block"),
            EMERALD(10, 15, "emerald_block"),
            CLAY(10, 10, "hardened_clay"),
            WATER(15, 5, "water"),
        }

        private enum class Gate(val z: Int) {
            PURPLE(19), ORANGE(18), BLUE(17), GREEN(16), RED(15),
        }

        private var solved = false
        private var schedule: Map<Lever, List<Double>> = emptyMap()
        private var leverPositions: Map<BlockPos, Lever> = emptyMap()
        private val clickCounts = mutableMapOf<Lever, Int>()
        private var waterStartMillis = -1L

        fun reset() {
            solved = false
            schedule = emptyMap()
            leverPositions = emptyMap()
            clickCounts.clear()
            waterStartMillis = -1L
        }

        fun wantsClick(level: Level, hit: HitResult): Boolean {
            if (!inPuzzleRoom(Puzzle.WATER_BOARD)) return false
            if (!solved) solve(level)

            val pos = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos ?: return false
            val lever = leverPositions[pos] ?: return false

            if (lever == Lever.WATER) return waterStartMillis == -1L
            if (waterStartMillis == -1L) return false

            val times = schedule[lever] ?: return false
            val clicked = clickCounts.getOrDefault(lever, 0)
            if (clicked >= times.size) return false

            val elapsedSeconds = (System.currentTimeMillis() - waterStartMillis) / 1000.0
            return elapsedSeconds >= times[clicked]
        }

        fun onClicked(hit: HitResult) {
            val pos = (hit as BlockHitResult).blockPos
            val lever = leverPositions[pos] ?: return
            if (lever == Lever.WATER && waterStartMillis == -1L) waterStartMillis = System.currentTimeMillis()
            clickCounts[lever] = clickCounts.getOrDefault(lever, 0) + 1
            devMessage("§bPuzzle Triggerbot: clicking Water Board lever ${lever.name}.")
        }

        private fun solve(level: Level) {
            val room = DungeonUtils.currentRoom ?: return

            // "Extended" gate = anything other than air sitting in the gate slot (matches upstream's own check).
            val closedGates = Gate.entries.mapIndexedNotNull { index, gate ->
                index.takeIf { !level.getBlockState(room.getRealCoords(BlockPos(15, 56, gate.z))).isAir }
            }
            if (closedGates.size != 3) return // gates not fully resolved yet, retry next tick

            val patternId = when {
                level.getBlockState(room.getRealCoords(BlockPos(14, 77, 27))).block == Blocks.TERRACOTTA -> 0
                level.getBlockState(room.getRealCoords(BlockPos(16, 78, 27))).block == Blocks.EMERALD_BLOCK -> 1
                level.getBlockState(room.getRealCoords(BlockPos(14, 78, 27))).block == Blocks.DIAMOND_BLOCK -> 2
                level.getBlockState(room.getRealCoords(BlockPos(14, 78, 27))).block == Blocks.QUARTZ_BLOCK -> 3
                else -> null
            } ?: return // pattern marker not resolved yet, retry next tick

            val gatesKey = closedGates.joinToString("")
            val raw = solutions[USE_OPTIMIZED.toString()]?.get(patternId.toString())?.get(gatesKey)

            leverPositions = Lever.entries.associateBy { room.getRealCoords(BlockPos(it.x, if (it == Lever.WATER) WATER_Y else LEVER_Y, it.z)) }
            solved = true

            if (raw == null) {
                devMessage("§cPuzzle Triggerbot: no known Water Board solution for pattern $patternId gates $gatesKey.")
                return
            }
            schedule = raw.mapNotNull { (key, times) -> Lever.entries.find { it.key == key }?.let { it to times } }.toMap()
            devMessage("§bPuzzle Triggerbot: Water Board solved (pattern $patternId, gates $gatesKey).")
        }
    }
}
