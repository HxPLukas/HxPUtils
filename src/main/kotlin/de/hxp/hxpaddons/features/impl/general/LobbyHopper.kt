package de.hxp.hxpaddons.features.impl.general

import de.hxp.hxpaddons.clickgui.settings.Setting.Companion.withDependency
import de.hxp.hxpaddons.clickgui.settings.impl.BooleanSetting
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.clickgui.settings.impl.StringSetting
import de.hxp.hxpaddons.events.ChatPacketEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.alert
import de.hxp.hxpaddons.utils.devMessage
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.sendCommand
import de.hxp.hxpaddons.utils.skyblock.LocationUtils

object LobbyHopper : Module(
    name = "Lobby Hopper",
    description = "Repeatedly switches between two commands using Hypixel's server-switch cooldown."
) {

    private val commandOne by StringSetting("First Command", default = "hub", desc = "First command to run (without /).")
    private val commandTwo by StringSetting("Second Command", default = "hub", desc = "Second command to run (without /).")

    private val switchDelay by NumberSetting("Switch Delay", default = 5f, min = 1f, max = 30f, increment = 0.5f, unit = "s", desc = "Delay after arriving in a lobby before hopping again, to match Hypixel's server-switch cooldown.")

    private val dayCheck by BooleanSetting("Day", false, desc = "Stops hopping once a lobby's day (shown in F3) falls within the range below.")
    private val maxDay by NumberSetting("Max Day", default = 15, min = 0, max = 15, desc = "Stops on any lobby with a day between 0 and this value.").withDependency { dayCheck }
    private val onlySecondCommand by BooleanSetting("Only 2nd Command", false, desc = "Only checks the day on lobbies reached via the Second Command, ignoring the First Command's lobby entirely.").withDependency { dayCheck }

    private val playerCountCheck by BooleanSetting("Player Count", false, desc = "Also stops on any lobby whose tab list player count (the number shown on the tab list itself, not the raw player-entry count) is at or below Max Players - even if Day is enabled and the day doesn't match, a low-population lobby stops the hop too.")
    private val maxPlayers by NumberSetting("Max Players", default = 9, min = 0, max = 80, desc = "Stops on any lobby with this many players (read from the tab list's own header/footer text) or fewer.").withDependency { playerCountCheck }

    private val dupeCheck by BooleanSetting("Skip Duplicate Lobbies", false, desc = "Reads the server ID Hypixel sends in chat on every switch (\"Sending to server X...\") - a lobby whose ID was already seen this run is skipped immediately, without re-checking Day/Player Count.")

    // Hypixel sometimes routes a switch through an intermediate lobby before landing on the final one,
    // and the day/time isn't synced from the server the instant WorldEvent.Load fires - so instead of
    // reading the day immediately on load, we wait this many ticks after the *last* load seen. Any
    // further WorldEvent.Load during that wait (a redirect) restarts the countdown, so we only ever
    // read the day of the lobby we actually end up standing in.
    private const val SETTLE_TICKS = 20

    // Safety fallback in case we never get a WorldEvent.Load at all after hopping (e.g. bad command).
    private const val LOAD_FALLBACK_TICKS = 300

    // Hypixel rejects the switch with this message when its own internal transfer cooldown hasn't
    // expired yet - retrying immediately just gets rejected again, so wait this long and resend.
    private const val TRANSFER_COOLDOWN_MARKER = "PLAYER_TRANSFER_COOLDOWN"
    private const val RETRY_TICKS = 100

    // Hypixel sends this the instant a switch actually starts, e.g. "Sending to server mini123M..." -
    // same mechanism Devonian's PreviousLobby feature uses (github.com/Synnerz/devonian), confirmed
    // against its own working regex rather than guessed from scratch. Far cheaper than round-tripping
    // /locraw: no extra command, no waiting for a reply, and it fires before the switch even completes.
    private val lobbySwapRegex = Regex("^Sending to server (\\w+)\\.\\.\\.$")

    // Confirmed live (user report): ClientPacketListener.getListedOnlinePlayers().size() reads WAY more
    // than the actual lobby population shown on screen (102 vs. an expected low-teens/single-digit count).
    // Also confirmed NOT the tab list's header/footer text (tried that first, wrong per user report too) -
    // the real number sits in a fake "player" entry Hypixel sorts to the very top-left of the tab list
    // itself. Confirmed live via the entries dump (before it got trimmed back down) it's actually plural
    // "Players (21)", not singular "Player (21)" as first (mis-)reported - "s?" covers both just in case
    // Hypixel varies the wording by count. Matched via each listed player's own tabListDisplayName (falls
    // back to the raw profile name if Hypixel didn't set one) rather than the header/footer Component.
    private val tabPlayerCountRegex = Regex("players?\\s*\\((\\d+)\\)", RegexOption.IGNORE_CASE)

    private var cooldown = 0
    private var useFirst = true
    private var lastHopUsedFirst = true
    private var awaitingLoad = false
    private var settleTimer = -1
    private var loadWaitTicks = 0
    private var retryTimer = -1
    // Server ID captured off [lobbySwapRegex] for the hop currently in flight - reset to null right
    // before every [sendHop] so a stale ID from an earlier hop is never mistaken for this one's.
    private var pendingServerId: String? = null
    // Server IDs seen for the whole client session (see [dupeCheck]) - deliberately NOT cleared on
    // [onEnable], so stopping on a match and then manually re-enabling to keep looking doesn't forget
    // every lobby already visited before that stop (confirmed live this was a real bug - see onEnable's
    // own comment).
    private val seenServerIds = mutableSetOf<String>()

    init {
        on<TickEvent.Server> {
            if (retryTimer >= 0) {
                if (retryTimer-- == 0) retryHop()
                return@on
            }

            if (awaitingLoad) {
                if (settleTimer >= 0) {
                    if (settleTimer-- == 0) {
                        awaitingLoad = false
                        arrived()
                    }
                } else if (++loadWaitTicks > LOAD_FALLBACK_TICKS) {
                    awaitingLoad = false
                    cooldown = 0
                }
                return@on
            }

            if (cooldown > 0) {
                cooldown--
                return@on
            }
            if (!LocationUtils.isInSkyblock) return@on
            hop()
        }

        on<WorldEvent.Load> {
            if (!awaitingLoad) return@on
            settleTimer = SETTLE_TICKS
        }

        on<ChatPacketEvent> {
            lobbySwapRegex.find(value.noControlCodes.trim())?.let { pendingServerId = it.groupValues[1] }

            if (awaitingLoad && TRANSFER_COOLDOWN_MARKER in value) {
                awaitingLoad = false
                settleTimer = -1
                retryTimer = RETRY_TICKS
            }
        }
    }

    override fun onEnable() {
        super.onEnable()
        cooldown = 0
        useFirst = true
        lastHopUsedFirst = true
        awaitingLoad = false
        settleTimer = -1
        loadWaitTicks = 0
        retryTimer = -1
        pendingServerId = null
        // NOT seenServerIds.clear() - confirmed live this used to wipe the whole dupe-tracking history
        // every time the hopper re-enabled, including a stop-then-manually-restart after finishArrival()'s
        // own toggle() (Day/Player Count match) - losing track of every lobby seen before that stop, so a
        // genuine repeat visited after restarting no longer got recognized. Kept for the whole client
        // session now (see [dupeCheck]'s own doc) - only ever grows, never reset short of a full relaunch.
    }

    /**
     * Runs right after a lobby's load has settled. With [dupeCheck] on, [pendingServerId] (captured off
     * [lobbySwapRegex] when the switch started - see the chat listener) decides whether this lobby's
     * already been seen this run: a repeat is skipped immediately, without even running Day/Player Count,
     * since there's nothing new to learn from a lobby already evaluated. A missing [pendingServerId]
     * (Hypixel's "Sending to server..." line never matched, or never arrived) just skips the dupe check
     * for this one arrival instead of blocking the hopper.
     */
    private fun arrived() {
        val serverId = pendingServerId
        if (dupeCheck && serverId != null && !seenServerIds.add(serverId)) {
            devMessage("[LobbyHopper] Duplicate lobby ($serverId) - skipping without re-checking Day/Player Count.")
            cooldown = (switchDelay * 20f).toInt()
            return
        }
        if (dupeCheck && serverId == null) {
            devMessage("[LobbyHopper] No 'Sending to server ...' message seen for this hop - skipping the duplicate-lobby check just this once.")
        }
        finishArrival()
    }

    /**
     * The actual "did we find what we're looking for" check, run once a lobby is confirmed new (or
     * [dupeCheck] is off entirely). [dayCheck] and [playerCountCheck] are independent OR'd stop
     * conditions - either one matching (if enabled) stops the hop, so a low-population lobby (see
     * [maxPlayers]) stops it even when [dayCheck] is also on and the day itself doesn't match.
     */
    private fun finishArrival() {
        val checkDay = dayCheck && (!onlySecondCommand || !lastHopUsedFirst)
        if (checkDay) {
            val level = mc.level
            if (level != null) {
                val day = (level.overworldClockTime / 24000L).toInt()
                if (day in 0..maxDay) {
                    modMessage("§aFound a lobby on day $day, stopping.")
                    alert("§aLobby found! §7(Day $day)")
                    toggle()
                    return
                }
            }
        }
        if (playerCountCheck) {
            val count = readTabPlayerCount()
            if (count != null && count <= maxPlayers) {
                modMessage("§aFound a low-population lobby ($count players), stopping.")
                alert("§aLobby found! §7($count players)")
                toggle()
                return
            }
        }
        cooldown = (switchDelay * 20f).toInt()
    }

    /**
     * The player count as actually shown on the tab list (see [tabPlayerCountRegex]'s own doc - neither
     * `listedOnlinePlayers.size` nor the header/footer text is the right source, both tried and rejected per
     * live user reports). Scans every listed player's own displayed tab text (`PlayerInfo.tabListDisplayName`,
     * falling back to the raw profile name if Hypixel never set one - same field [PlayerTabOverlay] itself
     * reads to render each row) for [tabPlayerCountRegex], rather than relying on tab sort order to find "the"
     * top-left entry specifically.
     */
    private fun readTabPlayerCount(): Int? {
        val entries = mc.connection?.listedOnlinePlayers.orEmpty()
        val parsed = entries.firstNotNullOfOrNull { entry ->
            val text = (entry.tabListDisplayName?.string ?: entry.profile.name).noControlCodes
            tabPlayerCountRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
        }
        devMessage("[LobbyHopper] readTabPlayerCount(): $parsed players")
        return parsed
    }

    private fun hop() = sendHop(useFirst)

    private fun retryHop() {
        retryTimer = -1
        sendHop(lastHopUsedFirst)
    }

    private fun sendHop(usingFirst: Boolean) {
        val command = (if (usingFirst) commandOne else commandTwo).removePrefix("/").trim()
        if (command.isEmpty()) return

        pendingServerId = null
        sendCommand(command)
        lastHopUsedFirst = usingFirst
        useFirst = !usingFirst
        awaitingLoad = true
        settleTimer = -1
        loadWaitTicks = 0
    }
}
