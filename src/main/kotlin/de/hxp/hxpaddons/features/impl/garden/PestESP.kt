package de.hxp.hxpaddons.features.impl.garden

import de.hxp.hxpaddons.clickgui.settings.Setting.Companion.withDependency
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.clickgui.settings.impl.SelectorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.StringSetting
import de.hxp.hxpaddons.events.RenderEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.Color.Companion.multiplyAlpha
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.render.EntityOutlineESP
import de.hxp.hxpaddons.utils.render.drawFilledBox
import de.hxp.hxpaddons.utils.render.drawWireFrameBox
import de.hxp.hxpaddons.utils.renderBoundingBox
import de.hxp.hxpaddons.utils.skyblock.Island
import de.hxp.hxpaddons.utils.skyblock.LocationUtils
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.AABB

/**
 * Highlights Garden pests through walls, Garden-only. Hypixel renders every pest as a vanilla Silverfish
 * entity (just reskinned/renamed per pest type), so unlike the old name-list approach this matches by
 * [EntityType.SILVERFISH] directly - same [ESP Type]/opacity/color/scan-distance rendering as
 * [de.hxp.hxpaddons.features.impl.render.CustomESP], just with no name-label option at all (on request -
 * every Silverfish in the Garden is a pest, there's nothing to identify).
 */
object PestESP : Module(
    name = "Pest ESP",
    description = "Highlights garden pests through walls.",
    category = Category.GARDEN
) {
    private val espType by SelectorSetting("ESP Type", "Outline", listOf("Outline", "Box"), desc = "Whether pests are rendered as a glowing outline of the mob itself, or a 3D box around it.")
    private val outlineOpacity by NumberSetting("Outline Opacity", 100, 0, 100, 1, unit = "%", desc = "Opacity of the glowing outline.").withDependency { espType == ESP_TYPE_OUTLINE }
    private val boxOutlineWidth by NumberSetting("Box Outline Width", 3, 0, 10, 1, desc = "Width of the box's outline.").withDependency { espType == ESP_TYPE_BOX }
    private val boxOpacity by NumberSetting("Box Opacity", 50, 0, 100, 1, unit = "%", desc = "Opacity of the box's fill.").withDependency { espType == ESP_TYPE_BOX }
    private val boxWidthScale by NumberSetting("Box Width Scale", 100, 100, 500, 5, unit = "%", desc = "Stretches the box wider than the actual Silverfish hitbox - grows evenly from the center (the pest's own model is usually bigger than its tiny hitbox).").withDependency { espType == ESP_TYPE_BOX }
    private val boxHeightScale by NumberSetting("Box Height Scale", 100, 100, 500, 5, unit = "%", desc = "Stretches the box taller than the actual Silverfish hitbox - always grows up from the bottom (its feet stay planted on the ground, since the pest's model sits on top of the hitbox, not centered on it), the extra height is added on top only.").withDependency { espType == ESP_TYPE_BOX }
    private val boxDepthScale by NumberSetting("Box Depth Scale", 100, 100, 500, 5, unit = "%", desc = "Stretches the box deeper (Z axis) than the actual Silverfish hitbox - grows evenly from the center, same as Box Width Scale.").withDependency { espType == ESP_TYPE_BOX }
    private val pestColor by ColorSetting("Pest Color", Colors.MINECRAFT_GREEN, true, desc = "The color pests are highlighted in.")
    private val scanDistanceInput by StringSetting("Scan Distance", "64", length = 10, desc = "Maximum distance (in blocks) a pest can be from you to get matched/highlighted - type any number, no fixed upper limit.")

    // Indices into the "ESP Type" SelectorSetting's option list above.
    private const val ESP_TYPE_OUTLINE = 0
    private const val ESP_TYPE_BOX = 1

    /** [scanDistanceInput]'s fallback if the typed text doesn't parse as a number at all. */
    private const val DEFAULT_SCAN_DISTANCE = 64.0

    /** [scanDistanceInput]'s sanity ceiling - not a real functional limit, just a guard against a typo like an extra zero. */
    private const val MAX_SCAN_DISTANCE = 100_000.0

    private val entities = mutableSetOf<Entity>()

    private fun currentScanDistance(): Double = scanDistanceInput.trim().toDoubleOrNull()?.coerceIn(1.0, MAX_SCAN_DISTANCE) ?: DEFAULT_SCAN_DISTANCE

    init {
        on<TickEvent.End> {
            if (!enabled || !LocationUtils.isCurrentArea(Island.Garden)) return@on
            val player = mc.player ?: return@on
            val maxDistSq = currentScanDistance() * currentScanDistance()

            entities.clear()
            mc.level?.entitiesForRendering()?.forEach { e ->
                if (e.type != EntityType.SILVERFISH || !e.isAlive) return@forEach
                if (player.distanceToSqr(e) > maxDistSq) return@forEach
                entities.add(e)
            }
        }

        on<RenderEvent.Extract> {
            if (!enabled || !LocationUtils.isCurrentArea(Island.Garden)) {
                EntityOutlineESP.clear()
                return@on
            }

            // Rebuilt every frame from the currently tracked entities, same as CustomESP/StarMobESP, so a
            // pest that stops being tracked (dies, ESP Type gets switched) never keeps glowing from a stale
            // entry.
            EntityOutlineESP.clear()
            entities.forEach { entity ->
                if (!entity.isAlive) return@forEach
                if (espType == ESP_TYPE_OUTLINE) EntityOutlineESP.set(entity, pestColor.multiplyAlpha(outlineOpacity / 100f).rgba)
                else drawPestBox(scaledBox(entity.renderBoundingBox))
            }
        }

        on<WorldEvent.Load> {
            entities.clear()
            EntityOutlineESP.clear()
        }
    }

    /**
     * [boxWidthScale]/[boxHeightScale]/[boxDepthScale] applied to [aabb] independently - width/depth (X/Z)
     * grow evenly from the box's own center, height (Y) always grows up from minY only (the Silverfish's
     * feet stay planted on the ground, matching how the actual pest model sits on top of its hitbox rather
     * than being centered on it).
     */
    private fun scaledBox(aabb: AABB): AABB {
        if (boxWidthScale == 100 && boxHeightScale == 100 && boxDepthScale == 100) return aabb

        val centerX = (aabb.minX + aabb.maxX) / 2.0
        val centerZ = (aabb.minZ + aabb.maxZ) / 2.0
        val halfWidth = (aabb.maxX - aabb.minX) / 2.0 * (boxWidthScale / 100.0)
        val halfDepth = (aabb.maxZ - aabb.minZ) / 2.0 * (boxDepthScale / 100.0)
        val newHeight = (aabb.maxY - aabb.minY) * (boxHeightScale / 100.0)

        return AABB(
            centerX - halfWidth, aabb.minY, centerZ - halfDepth,
            centerX + halfWidth, aabb.minY + newHeight, centerZ + halfDepth
        )
    }

    private fun RenderEvent.Extract.drawPestBox(aabb: AABB) {
        drawFilledBox(aabb, pestColor.multiplyAlpha(boxOpacity / 100f), depth = false)
        drawWireFrameBox(aabb, pestColor, thickness = boxOutlineWidth.toFloat(), depth = false)
    }
}
