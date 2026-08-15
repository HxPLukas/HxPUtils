package de.hxp.hxpaddons.features.impl.dungeon

import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.WipModule
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.events.ChatPacketEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.devMessage
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.simulateRightClick
import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonUtils
import de.hxp.hxpaddons.utils.skyblock.dungeon.Puzzle
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

@WipModule
object SecretTriggerbot : Module(
    name = "Secret Triggerbot",
    description = "Automatically interacts with secrets (chests, levers, wither essence) as soon as you look at them.",
    category = Category.DUNGEON
) {
    val aimDelay by NumberSetting("Aim Delay", default = 150, min = 0, max = 500, increment = 10, desc = "Delay after aiming at a secret before it gets clicked.", unit = "ms")
    val clickCooldown by NumberSetting("Click Cooldown", default = 200, min = 50, max = 2000, increment = 10, desc = "Minimum time between two clicks, regardless of target.", unit = "ms")

    // Thin hitboxes (levers) can cause the crosshair raycast to briefly miss for a tick even while still aimed at them.
    private const val AIM_GRACE_MS = 150L

    private val triggeredPositions = mutableSetOf<BlockPos>()
    private var pendingPos: BlockPos? = null
    private var lastConfirmedTime = 0L
    private var lastClickTime = 0L
    private var lastClickedPos: BlockPos? = null

    override fun onEnable() {
        super.onEnable()
        triggeredPositions.clear()
        pendingPos = null
        lastConfirmedTime = 0L
        lastClickTime = 0L
        lastClickedPos = null
    }

    override fun onKeybind() {
        toggle()
        if (enabled) modMessage("§aSecret Triggerbot §2started§a.")
        else modMessage("§cSecret Triggerbot §4stopped§c.")
    }

    init {
        on<WorldEvent.Load> {
            triggeredPositions.clear()
            pendingPos = null
            lastClickTime = 0L
            lastClickedPos = null
        }

        on<ChatPacketEvent> {
            if (value.trim() != "That chest is locked!") return@on
            val pos = lastClickedPos ?: return@on
            triggeredPositions.remove(pos)
            devMessage("§bSecret Triggerbot: $pos was locked, allowing a retry.")
        }

        on<TickEvent.End> {
            if (!enabled) return@on
            val level = mc.level
            val room = DungeonUtils.currentRoom?.data
            val blockedPuzzleRoom = room?.type == RoomType.PUZZLE && !room.name.equals(Puzzle.TP_MAZE.displayName, ignoreCase = true)
            // Right-clicking with Aspect of the Void equipped triggers its own blink ability - don't let
            // the triggerbot's simulated right-click hijack that.
            val holdingAspectOfTheVoid = mc.player?.mainHandItem?.hoverName?.string?.noControlCodes?.contains("Aspect of the Void") == true

            val pos = if (!DungeonUtils.inDungeons || blockedPuzzleRoom || holdingAspectOfTheVoid || mc.screen != null || level == null) null
            else (mc.hitResult as? BlockHitResult)
                ?.takeIf { it.type == HitResult.Type.BLOCK }
                ?.blockPos
                ?.takeIf { it !in triggeredPositions && DungeonUtils.isSecret(level.getBlockState(it), it) }

            val now = System.currentTimeMillis()
            if (pos != null) lastConfirmedTime = now

            if (pos == null) {
                // Tolerate a brief raycast miss (thin hitboxes like levers) instead of cancelling immediately.
                if (pendingPos != null && now - lastConfirmedTime > AIM_GRACE_MS) pendingPos = null
                return@on
            }
            if (pos == pendingPos) return@on

            pendingPos = pos
            devMessage("§bSecret Triggerbot: target found at $pos, scheduling click.")
            HxPMod.scope.launch {
                delay(aimDelay.toLong())
                if (pendingPos != pos) {
                    devMessage("§bSecret Triggerbot: cancelled $pos (aim moved before aimDelay elapsed).")
                    return@launch
                }

                val wait = lastClickTime + clickCooldown.toLong() - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                if (pendingPos != pos) {
                    devMessage("§bSecret Triggerbot: cancelled $pos (aim moved during clickCooldown wait).")
                    return@launch
                }

                triggeredPositions += pos
                lastClickTime = System.currentTimeMillis()
                lastClickedPos = pos
                devMessage("§bSecret Triggerbot: clicking $pos.")
                simulateRightClick()
                if (pendingPos == pos) pendingPos = null
            }
        }
    }
}
