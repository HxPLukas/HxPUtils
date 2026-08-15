package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.Setting.Companion.withDependency
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.clickgui.settings.impl.SelectorSetting
import de.hxp.hxpaddons.events.RenderEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.events.core.onReceive
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.Color.Companion.multiplyAlpha
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.disguiseSignals
import de.hxp.hxpaddons.utils.readSidebarLines
import de.hxp.hxpaddons.utils.render.drawFilledBox
import de.hxp.hxpaddons.utils.render.drawWireFrameBox
import de.hxp.hxpaddons.utils.renderBoundingBox
import de.hxp.hxpaddons.utils.skyblock.Island
import de.hxp.hxpaddons.utils.skyblock.LocationUtils
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Highlights Odonata (the invisible Rift dragonfly) through walls, while in the Rift's Wyld Woods - two
 * independent [detectionMode]s to try, since neither has been confirmed as clearly the better one yet.
 *
 * "Particles" reacts to its `enchanted_hit` particle burst - it has no readable name/nametag of its own, same
 * situation as SkyHanni's `InvisibugHighlighter` detecting Invisibugs via their CRIT particles (see
 * [de.hxp.hxpaddons.features.impl.render.CustomESP]'s own doc for that writeup) - reacts to
 * [net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket], already-existing generic
 * packet-event infrastructure, no new mixin needed. A single burst fires several `enchanted_hit` particles at
 * once, all essentially on top of each other, while separate Odonata (or the same one on its next burst) are
 * always more than a few blocks apart (on request - "nur 1 particle pro 3 blöcke highlighten da eins mehrere
 * hat und immer über 3 blöcke auseinander sind") - so instead of tracking every single particle as its own
 * highlight (which would draw several overlapping boxes per burst), any existing tracked cluster within
 * [CLUSTER_DISTANCE] blocks of a newly-received particle is dropped and replaced with a fresh one at the new
 * particle's position, always showing the most recent position for that cluster (on request - "lass ihn
 * immer den neuesten markieren") rather than the first one seen.
 *
 * "Head Texture" (2026-08-16, added after investigating Custom ESP's own Custom Texture debug dump live in
 * the Wyld Woods, on request - "setzt das was du gerade geloggt hast als odonata esp mode") instead directly
 * matches Odonata's own ArmorStand entity: [ODONATA_TEXTURE] is the exact disguise signal (a player-head skin
 * texture value, held in the ArmorStand's MAINHAND rather than its HEAD slot) confirmed live against a
 * reported Odonata sighting - see [de.hxp.hxpaddons.utils.disguiseSignals]'s own doc for what that actually
 * reads and why a skull texture isn't necessarily in the HEAD slot. Every [TickEvent.End], every equipped
 * slot of every nearby [LivingEntity] gets checked for an exact match against that one known value (not a
 * substring search like Custom ESP's own general-purpose matching - this module only ever looks for this one
 * specific disguise), and matches are drawn as a live box around the actual entity - no cluster/expiry
 * bookkeeping needed here, unlike Particles, since a real entity reference already has its own lifecycle
 * ([Entity.isAlive]) to drive that instead.
 *
 * Only active while [LocationUtils.isCurrentArea] is [Island.Rift] AND the sidebar scoreboard currently
 * shows "Wyld Woods" (on request - "rift ist die island und wyld woods das was im scoreboard steht", i.e.
 * the sub-area text isn't tracked by [LocationUtils.currentArea] at all, only the sidebar shows it, read via
 * [readSidebarLines]) - Odonata is specific to that sub-area of the Rift. Applies to both detection modes.
 *
 * Entirely unverified live - first time either of these exact detection mechanisms gets exercised this way.
 */
object OdonataESP : Module(
    name = "Odonata ESP",
    description = "Highlights Odonata (the invisible Rift dragonfly) through walls, while in the Rift's Wyld Woods.",
    category = Category.SKYBLOCK
) {
    private val detectionMode by SelectorSetting(
        "Detection Mode", "Particles", listOf("Particles", "Head Texture"),
        desc = "Particles reacts to the enchanted_hit burst Odonata gives off. Head Texture instead directly matches its equipped disguise item's confirmed skin texture value. Neither is confirmed as clearly the better option yet - try both."
    )
    private val espColor by ColorSetting("Odonata Color", Colors.MINECRAFT_LIGHT_PURPLE, true, desc = "The color a matched Odonata is highlighted in.")
    private val boxSize by NumberSetting("Box Size", 0.4, 0.1, 2.0, 0.1, unit = " blocks", desc = "Size of the box drawn at each matched Odonata's position (Particles mode only).").withDependency { detectionMode == DETECTION_PARTICLES }
    private val highlightDurationMs by NumberSetting("Highlight Duration", 3000, 500, 10000, 100, unit = "ms", desc = "How long a highlight stays visible after its last matching particle before fading out (Particles mode only).").withDependency { detectionMode == DETECTION_PARTICLES }

    private const val DETECTION_PARTICLES = 0
    private const val DETECTION_TEXTURE = 1

    /** Small upward nudge for Head Texture mode's box (see the render pass below) - on request, "minimal höher setzen". */
    private const val HEAD_TEXTURE_BOX_Y_OFFSET = 0.15

    /** How close (in blocks) two enchanted_hit particles must be to count as the same Odonata burst rather than a separate one - see this module's own doc. */
    private const val CLUSTER_DISTANCE = 3.0

    /** Odonata's confirmed disguise signal - a player-head skin texture value, held in its ArmorStand's MAINHAND slot (not HEAD) - captured live 2026-08-16 via Custom ESP's own Custom Texture debug dump. */
    private const val ODONATA_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTYyNTUxMjE4ODY3NCwKICAicHJvZmlsZUlkIiA6ICI3MzgyZGRmYmU0ODU0NTVjODI1ZjkwMGY4OGZkMzJmOCIsCiAgInByb2ZpbGVOYW1lIiA6ICJJb3lhbCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS85ZmQ4MDZkZWZkZmRmNTliMWYyNjA5YzhlZTM2NDY2NmRlNjYxMjdhNjIzNDE1YjU0MzBjOTM1OGM2MDFlZjdjIgogICAgfQogIH0KfQ=="

    private data class TrackedCluster(val pos: Vec3, val expiresAtMs: Long)

    // CopyOnWriteArrayList, not a plain list: added to from the network thread (particle packets arrive via
    // onReceive, which fires off the network thread, not the render thread), read/pruned every render frame
    // from RenderEvent.Extract - same reasoning/convention as CustomESP's own particle tracking.
    private val clusters = CopyOnWriteArrayList<TrackedCluster>()

    /** Head Texture mode's live matches - rebuilt every tick, unlike [clusters] there's no expiry to track since a real entity's own [Entity.isAlive] already covers that. */
    private val trackedEntities = mutableSetOf<Entity>()

    private fun inWyldWoods(): Boolean =
        LocationUtils.isCurrentArea(Island.Rift) && readSidebarLines().any { it.contains("Wyld Woods", ignoreCase = true) }

    init {
        onReceive<ClientboundLevelParticlesPacket> {
            if (!enabled || detectionMode != DETECTION_PARTICLES) return@onReceive
            val registryName = BuiltInRegistries.PARTICLE_TYPE.getKey(particle.type)?.path ?: return@onReceive
            if (registryName != "enchanted_hit") return@onReceive
            if (!inWyldWoods()) return@onReceive

            val pos = Vec3(x, y, z)
            clusters.removeIf { it.pos.distanceTo(pos) <= CLUSTER_DISTANCE }
            clusters.add(TrackedCluster(pos, System.currentTimeMillis() + highlightDurationMs.toLong()))
        }

        on<TickEvent.End> {
            if (!enabled || detectionMode != DETECTION_TEXTURE) {
                trackedEntities.clear()
                return@on
            }
            trackedEntities.clear()
            if (!inWyldWoods()) return@on
            val player = mc.player ?: return@on

            mc.level?.entitiesForRendering()?.forEach { e ->
                if (e === player || !e.isAlive || e !is LivingEntity) return@forEach
                val matches = EquipmentSlot.entries.any { slot -> e.getItemBySlot(slot).disguiseSignals.contains(ODONATA_TEXTURE) }
                if (matches) trackedEntities.add(e)
            }
        }

        on<RenderEvent.Extract> {
            if (!enabled) return@on

            if (detectionMode == DETECTION_PARTICLES) {
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
            } else {
                trackedEntities.forEach { entity ->
                    if (!entity.isAlive) return@forEach
                    val full = entity.renderBoundingBox
                    // Odonata's ArmorStand has a full-height vanilla hitbox, but the actual visible model sits
                    // low in it - quartering the height and nudging the bottom up slightly (on request, "die
                    // box vierteln von der höhe und minimal höher setzen") hugs that low visible part instead
                    // of boxing the whole (mostly empty) ArmorStand hitbox.
                    val minY = full.minY + HEAD_TEXTURE_BOX_Y_OFFSET
                    val maxY = minY + (full.maxY - full.minY) / 4.0
                    val aabb = AABB(full.minX, minY, full.minZ, full.maxX, maxY, full.maxZ)
                    drawFilledBox(aabb, espColor.multiplyAlpha(0.35f), depth = false)
                    drawWireFrameBox(aabb, espColor, thickness = 3f, depth = false)
                }
            }
        }

        on<WorldEvent.Load> {
            clusters.clear()
            trackedEntities.clear()
        }
    }
}
