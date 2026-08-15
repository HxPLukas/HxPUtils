package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.impl.BooleanSetting
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.events.core.onReceive
import de.hxp.hxpaddons.events.core.onSend
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.mixin.accessors.LocalPlayerAccessor
import de.hxp.hxpaddons.utils.EtherwarpHelper
import de.hxp.hxpaddons.utils.InstantTransmissionHelper
import de.hxp.hxpaddons.utils.addVec
import de.hxp.hxpaddons.utils.customData
import de.hxp.hxpaddons.utils.equalsOneOf
import de.hxp.hxpaddons.utils.handlers.schedule
import de.hxp.hxpaddons.utils.itemId
import de.hxp.hxpaddons.utils.skyblock.Island
import de.hxp.hxpaddons.utils.skyblock.LocationUtils
import de.hxp.hxpaddons.utils.skyblock.SkyblockPlayer
import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonUtils
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.RoomType
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import kotlin.jvm.optionals.getOrDefault

/**
 * Ported from NoammAddons' "NoRotate" feature (features/impl/misc/NoRotate.kt): predicts the landing position
 * of Etherwarp / Instant Transmission / Wither Impact client-side before the server confirms the teleport,
 * then silently reconciles the confirming [ClientboundPlayerPositionPacket] so the server can never snap the
 * camera to the direction it forces you to face after teleporting.
 */
object NoRotate : Module(
    name = "No Rotate",
    description = "Prevents the server from snapping your view when using teleport items.",
    category = Category.SKYBLOCK
) {
    private val etherwarp by BooleanSetting("Etherwarp", false, desc = "Applies to Aspect of the Void/End's Etherwarp ability.")
    private val instantTransmission by BooleanSetting("Instant Transmission", false, desc = "Applies to Aspect of the Void/End's Instant Transmission ability.")
    private val witherImpact by BooleanSetting("Wither Impact", false, desc = "Applies to the Shadow Warp/Implosion/Wither Shield scroll combo.")

    private const val RESYNC_TIMEOUT_TICKS = 10

    private val pendingTeleports = mutableListOf<TeleportPrediction>()
    private var lastWitherImpact = System.currentTimeMillis()

    init {
        on<WorldEvent.Load> {
            pendingTeleports.clear()
            lastWitherImpact = System.currentTimeMillis()
        }

        onSend<ServerboundUseItemPacket> {
            if (!enabled) return@onSend
            if (LocationUtils.isCurrentArea(Island.PrivateIsland, Island.Garden)) return@onSend
            if (DungeonUtils.isFloor(7) && DungeonUtils.inBoss) return@onSend
            if (SkyblockPlayer.currentMana < SkyblockPlayer.maxMana * 0.1) return@onSend
            if (DungeonUtils.currentRoom?.data?.type == RoomType.TRAP) return@onSend
            val player = mc.player ?: return@onSend
            if (player.isPassenger) return@onSend

            val heldStack = player.getItemInHand(hand)
            val tpInfo = getTeleportInfo(heldStack) ?: return@onSend

            when (tpInfo.type) {
                TeleportType.Etherwarp -> predictEtherwarp(tpInfo, yRot, xRot)
                TeleportType.InstantTransmission -> predictInstantTransmission(tpInfo, yRot, xRot)
                TeleportType.WitherImpact -> predictWitherImpact(tpInfo, yRot, xRot)
            }
        }

        onReceive<ClientboundPlayerPositionPacket> { event ->
            if (!enabled || pendingTeleports.isEmpty()) return@onReceive
            val prediction = pendingTeleports.removeFirst().position
            val expected = change().position()

            if (expected != prediction) {
                pendingTeleports.clear()
                return@onReceive
            }

            val player = mc.player ?: return@onReceive

            val old = PositionMoveRotation.of(player)
            val new = PositionMoveRotation.calculateAbsolute(old, change(), relatives())

            player.setPos(new.position())
            player.deltaMovement = new.deltaMovement()

            val newOldPos = PositionMoveRotation.calculateAbsolute(
                PositionMoveRotation(player.oldPosition(), Vec3.ZERO, player.yRotO, player.xRotO), change(), relatives()
            )

            player.xo = newOldPos.position().x
            player.yo = newOldPos.position().y
            player.zo = newOldPos.position().z

            player.connection.send(ServerboundAcceptTeleportationPacket(id))
            player.connection.send(ServerboundMovePlayerPacket.PosRot(player.x, player.y, player.z, new.yRot, new.xRot, false, false))

            (player as LocalPlayerAccessor).setServerYaw(new.yRot)
            player.setServerPitch(new.xRot)

            event.cancel()
        }
    }

    // Chains off the last still-unconfirmed prediction (if any) rather than the player's last known
    // server position, so a second teleport queued before the first one's confirmation arrives is
    // calculated from where the player will actually end up, not where they still visually are.
    private fun startPos(): Vec3 {
        pendingTeleports.lastOrNull()?.let { return it.position }
        val player = mc.player as LocalPlayerAccessor
        return Vec3(player.serverX, player.serverY, player.serverZ)
    }

    private fun teleport(prediction: TeleportPrediction) {
        pendingTeleports.add(prediction)
        schedule(RESYNC_TIMEOUT_TICKS) { pendingTeleports.remove(prediction) }
    }

    private fun predictEtherwarp(tpInfo: TeleportInfo, yaw: Float, pitch: Float) {
        val playerPos = startPos()
        val etherPos = EtherwarpHelper.getEtherPos(playerPos, getLookVec(yaw, pitch), tpInfo.distance)
        if (!etherPos.succeeded || etherPos.pos == null) return
        if (DungeonUtils.roomAt(etherPos.vec!!)?.data?.type == RoomType.TRAP) return

        val state = mc.level?.getBlockState(etherPos.pos)
        val isFenceLike = state != null && (state.`is`(BlockTags.FENCES) || state.`is`(BlockTags.WALLS) || state.`is`(BlockTags.FENCE_GATES))
        val landingVec = etherPos.vec
        val prediction = if (isFenceLike) landingVec.addVec(0.5, 2.05, 0.5) else landingVec.addVec(0.5, 1.05, 0.5)
        teleport(TeleportPrediction(prediction, tpInfo))
    }

    private fun predictInstantTransmission(tpInfo: TeleportInfo, yaw: Float, pitch: Float) {
        val player = mc.player as LocalPlayerAccessor
        val playerPos = startPos()
        val pos = InstantTransmissionHelper.predictTeleport(tpInfo.distance, playerPos, yaw, pitch, player.isCrouchingServer) ?: return
        if (DungeonUtils.roomAt(pos)?.data?.type == RoomType.TRAP) return
        val prediction = if (mc.level?.getBlockState(BlockPos.containing(pos.x, pos.y - 1, pos.z))?.isAir == true) pos.addVec(y = -1) else pos
        teleport(TeleportPrediction(prediction, tpInfo))
    }

    private fun predictWitherImpact(tpInfo: TeleportInfo, yaw: Float, pitch: Float) {
        if (System.currentTimeMillis() - lastWitherImpact <= 125) return // 8 CPS limit
        lastWitherImpact = System.currentTimeMillis()
        predictInstantTransmission(tpInfo, yaw, pitch)
    }

    private fun getLookVec(yaw: Float, pitch: Float): Vec3 {
        val yawRad = -yaw * (Math.PI.toFloat() / 180f) - Math.PI.toFloat()
        val pitchRad = -pitch * (Math.PI.toFloat() / 180f)
        val cosPitch = -kotlin.math.cos(pitchRad)
        return Vec3((kotlin.math.sin(yawRad) * cosPitch).toDouble(), kotlin.math.sin(pitchRad).toDouble(), (kotlin.math.cos(yawRad) * cosPitch).toDouble())
    }

    private fun getTeleportInfo(stack: ItemStack?): TeleportInfo? {
        if (stack == null || stack.isEmpty) return null
        val player = mc.player as LocalPlayerAccessor
        val id = stack.itemId
        val nbt = stack.customData

        if (id.equalsOneOf("ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END")) {
            val tuners = nbt.getByte("tuned_transmission").getOrDefault(0).toDouble()

            return if (etherwarp && player.isCrouchingServer && nbt.getByte("ethermerge").orElse(0) == 1.toByte()) {
                TeleportInfo(57 + tuners, TeleportType.Etherwarp)
            } else if (instantTransmission) TeleportInfo(8 + tuners, TeleportType.InstantTransmission) else null
        } else if (witherImpact && nbt.getList("ability_scroll").toString().run {
                contains("SHADOW_WARP_SCROLL") && contains("IMPLOSION_SCROLL") && contains("WITHER_SHIELD_SCROLL")
            }) {
            return TeleportInfo(10.0, TeleportType.WitherImpact)
        }

        return null
    }

    enum class TeleportType { Etherwarp, InstantTransmission, WitherImpact }
    data class TeleportInfo(val distance: Double, val type: TeleportType)
    data class TeleportPrediction(val position: Vec3, val info: TeleportInfo)
}
