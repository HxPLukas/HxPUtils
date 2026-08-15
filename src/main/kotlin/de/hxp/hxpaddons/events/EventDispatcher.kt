package de.hxp.hxpaddons.events

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.events.core.onReceive
import de.hxp.hxpaddons.events.core.onSend
import de.hxp.hxpaddons.utils.ChatManager
import de.hxp.hxpaddons.utils.containsOneOf
import de.hxp.hxpaddons.utils.equalsOneOf
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.render.RenderBatchManager
import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonUtils
import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonUtils.isSecret
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.network.protocol.game.*
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.phys.Vec3

object EventDispatcher {

    private val creditedSecretItemIds = mutableSetOf<Int>()

    init {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> WorldEvent.Load.postAndCatch() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> WorldEvent.Unload.postAndCatch() }

        ClientTickEvents.START_LEVEL_TICK.register { world -> TickEvent.Start(world).postAndCatch() }
        ClientTickEvents.END_LEVEL_TICK.register { world -> TickEvent.End(world).postAndCatch() }

        ClientChunkEvents.CHUNK_LOAD.register { _, chunk -> ChunkLoadEvent(chunk).postAndCatch() }

        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register { context ->
            RenderEvent.Extract(context, RenderBatchManager.renderConsumer).postAndCatch()
            RenderEvent.Last(context).postAndCatch()
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ -> ScreenEvent.Open(screen).postAndCatch() }
        ScreenEvents.BEFORE_INIT.register { _, screen, _, _ ->
            ScreenEvents.remove(screen).register {
                ScreenEvent.Close(screen).postAndCatch()
            }
            ScreenMouseEvents.allowMouseClick(screen).register { screen, event ->
                !ScreenEvent.MouseClick(screen, event).postAndCatch()
            }
            ScreenMouseEvents.allowMouseRelease(screen).register { screen, event ->
                !ScreenEvent.MouseRelease(screen, event).postAndCatch()
            }
            ScreenKeyboardEvents.allowKeyPress(screen).register { screen, event ->
                !ScreenEvent.KeyPress(screen, event).postAndCatch()
            }
            ScreenMouseEvents.allowMouseScroll(screen).register { screen, mouseX, mouseY, deltaX, deltaY ->
                !ScreenEvent.MouseScroll(screen, mouseX, mouseY, deltaX, deltaY).postAndCatch()
            }
            ScreenEvents.afterExtract(screen).register { _, guiGraphicsExtractor, mouseX, mouseY, _ ->
                ScreenEvent.RenderPost(screen, guiGraphicsExtractor, mouseX, mouseY).postAndCatch()
            }
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { text, overlay ->
            if (overlay) return@register true
            !ChatManager.shouldCancelMessage(text)
        }

        // A single item-drop secret pickup can fire both packets below for the same entity - the "take"
        // packet (our own pickup animation) and, moments later, the "remove entities" packet as the
        // entity despawns (also the only signal we get for a teammate picking it up instead of us).
        // creditedSecretItemIds makes sure each entity only ever posts SecretPickupEvent.Item once,
        // regardless of which packet path catches it first - without this, a solo pickup double-counts.
        onReceive<ClientboundTakeItemEntityPacket> {
            if (mc.player == null || !DungeonUtils.inClear) return@onReceive
            val itemEntity = mc.level?.getEntity(itemId) as? ItemEntity ?: return@onReceive
            if (itemEntity.item.hoverName.string.containsOneOf(dungeonItemDrops, true) &&
                itemEntity.distanceTo(mc.player ?: return@onReceive) <= 6 &&
                creditedSecretItemIds.add(itemEntity.id)
            ) SecretPickupEvent.Item(itemEntity).postAndCatch()
        }

        onReceive<ClientboundRemoveEntitiesPacket> {
            if (mc.player == null || !DungeonUtils.inClear) return@onReceive
            entityIds.forEach { id ->
                val entity = mc.level?.getEntity(id) as? ItemEntity ?: return@forEach
                if (entity.item.hoverName.string.containsOneOf(dungeonItemDrops, true) &&
                    entity.distanceTo(mc.player ?: return@onReceive) <= 6 &&
                    creditedSecretItemIds.add(entity.id)
                ) SecretPickupEvent.Item(entity).postAndCatch()
            }
        }

        on<WorldEvent.Load> { creditedSecretItemIds.clear() }

        onReceive<ClientboundSoundPacket> {
            if (!DungeonUtils.inClear) return@onReceive
            if (sound.value().equalsOneOf(SoundEvents.BAT_HURT, SoundEvents.BAT_DEATH) && volume == 0.1f)
                SecretPickupEvent.Bat(this).postAndCatch()
        }

        onSend<ServerboundUseItemOnPacket> {
            if (!DungeonUtils.inDungeons || hand == InteractionHand.OFF_HAND) return@onSend
            val blockState = mc.level?.getBlockState(hitResult.blockPos) ?: return@onSend
            if (blockState.block is SkullBlock) {
                val distance = mc.player?.eyePosition?.distanceToSqr(Vec3(hitResult.blockPos)) ?: return@onSend
                if (distance > 20.25) return@onSend
            }

            if (isSecret(blockState, hitResult.blockPos)) SecretPickupEvent.Interact(hitResult.blockPos, blockState).postAndCatch()
        }

        onReceive<ClientboundSystemChatPacket> {
            if (!overlay) ChatPacketEvent(content.string.noControlCodes, content).postAndCatch()
        }
    }

    private val dungeonItemDrops = listOf(
        "Health Potion VIII Splash Potion", "Healing Potion 8 Splash Potion", "Healing Potion VIII Splash Potion", "Healing VIII Splash Potion", "Healing 8 Splash Potion",
        "Decoy", "Inflatable Jerry", "Spirit Leap", "Trap", "Training Weights", "Defuse Kit", "Dungeon Chest Key", "Treasure Talisman", "Revive Stone", "Architect's First Draft",
        "Secret Dye", "Candycomb"
    )
}