package de.hxp.hxpaddons.utils.skyblock.dungeon

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.HxPMod.scope
import de.hxp.hxpaddons.events.ChatPacketEvent
import de.hxp.hxpaddons.events.RoomEnterEvent
import de.hxp.hxpaddons.events.SecretPickupEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.EventPriority
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.events.core.onReceive
import de.hxp.hxpaddons.utils.network.WebUtils.hasBonusPaulScore
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.romanToInt
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.network.protocol.game.*
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import kotlin.jvm.optionals.getOrNull

object DungeonListener {

    var dungeonTeammates: ArrayList<DungeonPlayer> = ArrayList(5)
    var dungeonTeammatesNoSelf: List<DungeonPlayer> = ArrayList(4)
    var leapTeammates: List<DungeonPlayer> = ArrayList(4)

    inline val passedRooms: MutableSet<Room> get() = ScanUtils.passedRooms
    inline val currentRoom: Room? get() = ScanUtils.currentRoom
    private var expectingBloodUpdate = false
    var dungeonStats = DungeonStats()
    var puzzles = ArrayList<Puzzle>()
    var floor: Floor? = null
    var inBoss = false
    var paul = false

    private fun getBoss(): Boolean = with(mc.player) {
        if (this == null || floor?.floorNumber == null) return false
        when (floor?.floorNumber) {
            1 -> x > -71 && z > -39
            in 2..4 -> x > -39 && z > -39
            in 5..6 -> x > -39 && z > -7
            7 -> x > -7 && z > -7
            else -> false
        }
    }

    init {
        on<TickEvent.End> {
            if (DungeonUtils.inDungeons) inBoss = getBoss()
        }

        on<WorldEvent.Load> {
            Blessing.entries.forEach { it.reset() }
            dungeonTeammatesNoSelf = emptyList()
            dungeonStats = DungeonStats()
            expectingBloodUpdate = false
            leapTeammates = emptyList()
            dungeonTeammates.clear()
            puzzles.clear()
            inBoss = false
            floor = null
            paul = false
        }

        on<RoomEnterEvent> (EventPriority.HIGH) {
            val room = room?.takeUnless { room -> passedRooms.any { it.data.name == room.data.name } } ?: return@on
            dungeonStats.knownSecrets += room.data.secrets
        }

        on<SecretPickupEvent.Interact> { currentRoom?.let { it.secretsFound++ } }
        on<SecretPickupEvent.Item> { currentRoom?.let { it.secretsFound++ } }
        on<SecretPickupEvent.Bat> { currentRoom?.let { it.secretsFound++ } }

        onReceive<ClientboundPlayerInfoUpdatePacket> {
            val tabListEntries = entries().mapNotNull { it.displayName?.string }.ifEmpty { return@onReceive }
            updateDungeonTeammates(tabListEntries)
            updateDungeonStats(tabListEntries)
            getDungeonPuzzles(tabListEntries)
        }

        onReceive<ClientboundSetPlayerTeamPacket> {
            val text = parameters.getOrNull()?.let { it.playerPrefix.string.plus(it.playerSuffix.string).noControlCodes } ?: return@onReceive

            floorRegex.find(text)?.groupValues?.get(1)?.let {
                if (floor == null) scope.launch(Dispatchers.IO) { paul = hasBonusPaulScore() }
                floor = Floor.valueOf(it)
            }

            clearedRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                if (dungeonStats.percentCleared != it && expectingBloodUpdate) dungeonStats.bloodDone = true
                dungeonStats.percentCleared = it
            }
        }

        onReceive<ClientboundTabListPacket> {
            Blessing.entries.forEach { blessing ->
                blessing.regex.find(footer.string)?.let { blessing.current = romanToInt(it.groupValues[1]) }
            }
        }

        on<ChatPacketEvent> {
            if (expectingBloodRegex.matches(value)) expectingBloodUpdate = true
            doorOpenRegex.find(value)?.let { dungeonStats.doorOpener = it.groupValues[1] }
            deathRegex.find(value)?.let { match ->
                dungeonTeammates.find { teammate ->
                    teammate.name == (match.groupValues[1].takeUnless { it == "You" } ?: mc.player?.name?.string)
                }?.deaths?.inc()
            }

            when (partyMessageRegex.find(value)?.groupValues?.get(1)?.lowercase() ?: return@on) {
                "mimic killed", "mimic slain", "mimic killed!", "mimic dead", "mimic dead!", ->
                    if (DungeonUtils.isFloor(6, 7)) dungeonStats.mimicKilled = true

                "prince killed", "prince slain", "prince killed!", "prince dead", "prince dead!", ->
                    dungeonStats.princeKilled = true

                "blaze done!", "blaze done", "blaze puzzle solved!" ->
                    puzzles.find { it == Puzzle.BLAZE }.let { it?.status = PuzzleStatus.Completed }
            }
        }

        onReceive<ClientboundRemoveEntitiesPacket> {
            DungeonUtils.dungeonTeammates.forEach {
                val id = it.entity?.id ?: return@forEach
                if (entityIds.contains(id)) it.entity = null
            }
        }

        onReceive<ClientboundAddEntityPacket> {
            if (type == EntityType.PLAYER)
                DungeonUtils.dungeonTeammates.find { it.entity == null && it.name == mc.level?.getEntity(id)?.name?.string }?.entity =
                    mc.level?.getEntity(id) as? Player
        }
    }

    private fun getDungeonPuzzles(tabList: List<String>) {
        for (entry in tabList) {
            val (name, status, player) = puzzleRegex.find(entry)?.destructured ?: continue
            val puzzle = Puzzle.entries.find { it.displayName == name }?.takeIf { it != Puzzle.UNKNOWN } ?: continue
            if (puzzle !in puzzles) puzzles.add(puzzle)

            if (player.isNotEmpty()) puzzle.player = player
            puzzle.status = when (status) {
                "✖" -> PuzzleStatus.Failed
                "✔" -> PuzzleStatus.Completed
                "✦" -> PuzzleStatus.Incomplete
                else -> continue
            }
        }
    }

    private fun updateDungeonStats(text: List<String>) {
        for (entry in text) {
            with(dungeonStats) {
                secretsPercent = secretPercentRegex.find(entry)?.groupValues?.get(1)?.toFloatOrNull() ?: secretsPercent
                completedRooms = completedRoomsRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: completedRooms
                secretsFound = secretCountRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: secretsFound
                openedRooms = openedRoomsRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: openedRooms
                puzzleCount = puzzleCountRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: puzzleCount
                deaths = deathsRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: deaths
                crypts = cryptRegex.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: crypts
                elapsedTime = timeRegex.find(entry)?.groupValues?.get(1) ?: elapsedTime
            }
        }
    }

    private fun updateDungeonTeammates(tabList: List<String>) = mc.execute {
        dungeonTeammates = DungeonUtils.getDungeonTeammates(dungeonTeammates, tabList)
        dungeonTeammatesNoSelf = dungeonTeammates.filter { it.name != mc.player?.name?.string }

        leapTeammates = dungeonTeammatesNoSelf
    }

    private val puzzleRegex = Regex("^ (\\w+(?: \\w+)*|\\?\\?\\?): \\[([✖✔✦])] ?(?:\\((\\w+)\\))?$")
    private val expectingBloodRegex = Regex("^\\[BOSS] The Watcher: You have proven yourself. You may pass.")
    private val doorOpenRegex = Regex("^(?:\\[\\w+] )?(\\w+) opened a (?:WITHER|Blood) door!")
    private val secretPercentRegex = Regex("^ Secrets Found: ([\\d.]+)%$")
    private val deathRegex = Regex("☠ (\\w{1,16}) .* and became a ghost\\.")
    private val timeRegex = Regex("^ Time: ((?:\\d+h ?)?(?:\\d+m ?)?\\d+s)$")
    private val completedRoomsRegex = Regex("^ Completed Rooms: (\\d+)$")
    private val clearedRegex = Regex("^Cleared: (\\d+)% \\(\\d+\\)$")
    private val secretCountRegex = Regex("^ Secrets Found: (\\d+)$")
    private val openedRoomsRegex = Regex("^ Opened Rooms: (\\d+)$")
    private val floorRegex = Regex("The Catacombs \\((\\w+)\\)$")
    private val partyMessageRegex = Regex("^Party > .*?: (.+)$")
    private val puzzleCountRegex = Regex("^Puzzles: \\((\\d+)\\)$")
    private val deathsRegex = Regex("^Team Deaths: (\\d+)$")
    private val cryptRegex = Regex("^ Crypts: (\\d+)$")

    data class DungeonStats(
        var secretsFound: Int = 0,
        var secretsPercent: Float = 0f,
        var knownSecrets: Int = 0,
        var crypts: Int = 0,
        var openedRooms: Int = 0,
        var completedRooms: Int = 0,
        var deaths: Int = 0,
        var percentCleared: Int = 0,
        var elapsedTime: String = "0s",
        private var _mimicKilled: Boolean = false,
        var princeKilled: Boolean = false,
        var doorOpener: String = "Unknown",
        var bloodDone: Boolean = false,
        var puzzleCount: Int = 0,
    ) {
        var mimicKilled: Boolean
            get() = _mimicKilled
            set(value) {
                if (value && !DungeonUtils.isFloor(6, 7)) {
                    error("Attempted to set mimicKilled = true on floor that has no mimic")
                }
                _mimicKilled = value
            }
    }
}