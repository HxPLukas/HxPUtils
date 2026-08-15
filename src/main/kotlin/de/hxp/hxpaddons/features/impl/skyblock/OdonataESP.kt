package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.events.RenderEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.events.core.onReceive
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.Color.Companion.multiplyAlpha
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.readSidebarLines
import de.hxp.hxpaddons.utils.render.drawFilledBox
import de.hxp.hxpaddons.utils.render.drawWireFrameBox
import de.hxp.hxpaddons.utils.skyblock.Island
import de.hxp.hxpaddons.utils.skyblock.LocationUtils
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Highlights Odonata (the invisible Rift dragonfly) through walls by its `enchanted_hit` particle burst -
 * it has no entity/nametag of its own to ESP directly, same situation as SkyHanni's `InvisibugHighlighter`
 * detecting Invisibugs via their CRIT particles (see [de.hxp.hxpaddons.features.impl.render.CustomESP]'s own
 * doc for that writeup) - reacts to [net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket],
 * already-existing generic packet-event infrastructure, no new mixin needed.
 *
 * Only active while [LocationUtils.isCurrentArea] is [Island.Rift] AND the sidebar scoreboard currently
 * shows "Wyld Woods" (on request - "rift ist die island und wyld woods das was im scoreboard steht", i.e.
 * the sub-area text isn't tracked by [LocationUtils.currentArea] at all, only the sidebar shows it, read via
 * [readSidebarLines]) - Odonata is specific to that sub-area of the Rift.
 *
 * A single Odonata burst fires several `enchanted_hit` particles at once, all essentially on top of each
 * other, while separate Odonata (or the same one on its next burst) are always more than a few blocks apart
 * (on request - "nur 1 particle pro 3 blöcke highlighten da eins mehrere hat und immer über 3 blöcke
 * auseinander sind") - so instead of tracking every single particle as its own highlight (which would draw
 * several overlapping boxes per burst), any existing tracked cluster within [CLUSTER_DISTANCE] blocks of a
 * newly-received particle is dropped and replaced with a fresh one at the new particle's position, always
 * showing the most recent position for that cluster (on request - "lass ihn immer den neuesten markieren")
 * rather than the first one seen. [TrackedCluster] is immutable and clusters are only ever removed+re-added
 * (never mutated in place) since particle packets arrive off the network thread while rendering reads/prunes
 * the list from the render thread - same [CopyOnWriteArrayList] convention [CustomESP]'s own particle
 * tracking already uses for that reason.
 *
 * Entirely unverified live - first time this exact particle/area gate combination gets exercised.
 */
object OdonataESP : Module(
    name = "Odonata ESP",
    description = "Highlights Odonata (the invisible Rift dragonfly) through walls via its enchanted_hit particle burst, while in the Rift's Wyld Woods.",
    category = Category.SKYBLOCK
) {
    private val espColor by ColorSetting("Odonata Color", Colors.MINECRAFT_LIGHT_PURPLE, true, desc = "The color a matched Odonata is highlighted in.")
    private val boxSize by NumberSetting("Box Size", 0.4, 0.1, 2.0, 0.1, unit = " blocks", desc = "Size of the box drawn at each matched Odonata's position.")
    private val highlightDurationMs by NumberSetting("Highlight Duration", 3000, 500, 10000, 100, unit = "ms", desc = "How long a highlight stays visible after its last matching particle before fading out.")

    /** How close (in blocks) two enchanted_hit particles must be to count as the same Odonata burst rather than a separate one - see this module's own doc. */
    private const val CLUSTER_DISTANCE = 3.0

    private data class TrackedCluster(val pos: Vec3, val expiresAtMs: Long)

    // CopyOnWriteArrayList, not a plain list: added to from the network thread (particle packets arrive via
    // onReceive, which fires off the network thread, not the render thread), read/pruned every render frame
    // from RenderEvent.Extract - same reasoning/convention as CustomESP's own particle tracking.
    private val clusters = CopyOnWriteArrayList<TrackedCluster>()

    private fun inWyldWoods(): Boolean =
        LocationUtils.isCurrentArea(Island.Rift) && readSidebarLines().any { it.contains("Wyld Woods", ignoreCase = true) }

    init {
        onReceive<ClientboundLevelParticlesPacket> {
            if (!enabled) return@onReceive
            val registryName = BuiltInRegistries.PARTICLE_TYPE.getKey(particle.type)?.path ?: return@onReceive
            if (registryName != "enchanted_hit") return@onReceive
            if (!inWyldWoods()) return@onReceive

            val pos = Vec3(x, y, z)
            clusters.removeIf { it.pos.distanceTo(pos) <= CLUSTER_DISTANCE }
            clusters.add(TrackedCluster(pos, System.currentTimeMillis() + highlightDurationMs.toLong()))
        }

        on<RenderEvent.Extract> {
            if (!enabled) return@on
            clusters.removeIf { it.expiresAtMs <= System.currentTimeMillis() }

            val half = boxSize / 2.0
            clusters.forEach { cluster ->
                val aabb = AABB(
                    cluster.pos.x - half, cluster.pos.y - half, cluster.pos.z - half,
                    cluster.pos.x + half, cluster.pos.y + half, cluster.pos.z + half
                )
                drawFilledBox(aabb, espColor.multiplyAlpha(0.35f), depth = false)
                drawWireFrameBox(aabb, espColor, thickness = 3f, depth = false)
            }
        }

        on<WorldEvent.Load> { clusters.clear() }
    }
}
