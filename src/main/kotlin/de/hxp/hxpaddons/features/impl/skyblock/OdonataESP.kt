package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.events.RenderEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
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
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB

/**
 * Highlights Odonata (the invisible Rift dragonfly) through walls, while in the Rift's Wyld Woods, by
 * directly matching its ArmorStand entity: [ODONATA_TEXTURE] is its exact disguise signal (a player-head skin
 * texture value, held in the ArmorStand's MAINHAND slot rather than its HEAD slot) confirmed live via Custom
 * ESP's own Custom Texture debug dump against a reported Odonata sighting - see
 * [de.hxp.hxpaddons.utils.disguiseSignals]'s own doc for what that actually reads and why a skull texture
 * isn't necessarily in the HEAD slot. Every [TickEvent.End], every equipped slot of every nearby
 * [LivingEntity] gets checked for an exact match against that one known value (not a substring search like
 * Custom ESP's own general-purpose matching - this module only ever looks for this one specific disguise),
 * and matches are drawn as a live box around the actual entity - no cluster/expiry bookkeeping needed, a real
 * entity reference already has its own lifecycle ([Entity.isAlive]) to drive that.
 *
 * 2026-08-16: originally also had a "Particles" mode reacting to Odonata's `enchanted_hit` particle burst
 * (same idea as SkyHanni's `InvisibugHighlighter` detecting Invisibugs via their CRIT particles), selectable
 * alongside Head Texture - removed the same day on request once Head Texture was confirmed working ("entferne
 * bei odonata den particle mode er soll jetzt immer über das andere") in favor of always using this one
 * exclusively.
 *
 * Only active while [LocationUtils.isCurrentArea] is [Island.Rift] AND the sidebar scoreboard currently
 * shows "Wyld Woods" (on request - "rift ist die island und wyld woods das was im scoreboard steht", i.e.
 * the sub-area text isn't tracked by [LocationUtils.currentArea] at all, only the sidebar shows it, read via
 * [readSidebarLines]) - Odonata is specific to that sub-area of the Rift.
 */
object OdonataESP : Module(
    name = "Odonata ESP",
    description = "Highlights Odonata (the invisible Rift dragonfly) through walls, while in the Rift's Wyld Woods.",
    category = Category.SKYBLOCK
) {
    private val espColor by ColorSetting("Odonata Color", Colors.MINECRAFT_LIGHT_PURPLE, true, desc = "The color a matched Odonata is highlighted in.")

    /** Odonata's confirmed disguise signal - a player-head skin texture value, held in its ArmorStand's MAINHAND slot (not HEAD) - captured live 2026-08-16 via Custom ESP's own Custom Texture debug dump. */
    private const val ODONATA_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTYyNTUxMjE4ODY3NCwKICAicHJvZmlsZUlkIiA6ICI3MzgyZGRmYmU0ODU0NTVjODI1ZjkwMGY4OGZkMzJmOCIsCiAgInByb2ZpbGVOYW1lIiA6ICJJb3lhbCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS85ZmQ4MDZkZWZkZmRmNTliMWYyNjA5YzhlZTM2NDY2NmRlNjYxMjdhNjIzNDE1YjU0MzBjOTM1OGM2MDFlZjdjIgogICAgfQogIH0KfQ=="

    /** Live matches - rebuilt every tick, no expiry to track since a real entity's own [Entity.isAlive] already covers that. */
    private val trackedEntities = mutableSetOf<Entity>()

    private fun inWyldWoods(): Boolean =
        LocationUtils.isCurrentArea(Island.Rift) && readSidebarLines().any { it.contains("Wyld Woods", ignoreCase = true) }

    init {
        on<TickEvent.End> {
            if (!enabled) {
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
            trackedEntities.forEach { entity ->
                if (!entity.isAlive) return@forEach
                val full = entity.renderBoundingBox
                // The visible model actually sits above the ArmorStand's own vanilla hitbox entirely, not
                // low inside it as first assumed - confirmed live 2026-08-16 after the first (bottom-anchored)
                // version was checked in-game ("mach die box so das die neue unterseite sozusagen die alte
                // oberseite von als das noch hoch war ist"): the new box's bottom is the old full box's TOP,
                // extending upward from there by the same quarter-height size as before.
                val quarterHeight = (full.maxY - full.minY) / 4.0
                val minY = full.maxY
                val maxY = minY + quarterHeight
                val aabb = AABB(full.minX, minY, full.minZ, full.maxX, maxY, full.maxZ)
                drawFilledBox(aabb, espColor.multiplyAlpha(0.35f), depth = false)
                drawWireFrameBox(aabb, espColor, thickness = 3f, depth = false)
            }
        }

        on<WorldEvent.Load> { trackedEntities.clear() }
    }
}
