package de.hxp.hxpaddons.events

import com.mojang.blaze3d.platform.InputConstants
import de.hxp.hxpaddons.events.core.CancellableEvent
import de.hxp.hxpaddons.events.core.Event
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack
import de.hxp.hxpaddons.utils.render.RenderConsumer
import de.hxp.hxpaddons.utils.skyblock.dungeon.tiles.Room
import net.fabricmc.fabric.api.client.rendering.v1.level.AbstractLevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.Vec3

class InputEvent(val key: InputConstants.Key) : CancellableEvent() // better mixin is prob ideal no need for cancellable

class BlockUpdateEvent(val pos: BlockPos, val old: BlockState, val updated: BlockState) : Event

class BlockInteractEvent(val pos: BlockPos) : CancellableEvent()
class EntityInteractEvent(val pos: Vec3, val entity: Entity) : CancellableEvent()

class ChatPacketEvent(val value: String, val component: Component) : Event // mixin instead of packet (still needs to run before vanilla processing for hideMessage()
class MessageSentEvent(val message: String) : CancellableEvent()

class RenderBossBarEvent(val bossBar: BossEvent) : CancellableEvent()

class RoomEnterEvent(val room: Room?) : CancellableEvent()

interface SecretPickupEvent : Event { // all are currently packet based but can probably use mixins
    class Interact(val blockPos: BlockPos, val blockState: BlockState) : SecretPickupEvent
    class Item(val entity: ItemEntity) : SecretPickupEvent
    class Bat(val packet: ClientboundSoundPacket) : SecretPickupEvent
}

interface TickEvent : Event {
    class Start(val world: ClientLevel) : TickEvent
    class End(val world: ClientLevel) : TickEvent
    object Server : TickEvent
}

interface WorldEvent : Event {
    object Load : WorldEvent
    object Unload : WorldEvent
}

class ChunkLoadEvent(val chunk: LevelChunk) : Event

abstract class RenderEvent(open val context: AbstractLevelRenderContext) : Event {
    class Extract(override val context: LevelRenderContext, val consumer: RenderConsumer) : RenderEvent(context)
    class Last(override val context: LevelRenderContext) : RenderEvent(context)
}

abstract class PartyEvent(val members: List<String>) : Event {
    class Leave(members: List<String>) : PartyEvent(members)
}

abstract class PacketEvent(val packet: Packet<*>) : CancellableEvent() { // ideally used less
    class Receive(packet: Packet<*>) : PacketEvent(packet)
    class Send(packet: Packet<*>) : PacketEvent(packet)
}

class RenderItemDecorationsEvent(val guiGraphics: GuiGraphicsExtractor, val stack: ItemStack, val x: Int, val y: Int) : Event
