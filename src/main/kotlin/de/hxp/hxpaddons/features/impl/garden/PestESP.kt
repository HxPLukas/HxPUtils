package de.hxp.hxpaddons.features.impl.garden

import de.hxp.hxpaddons.clickgui.settings.Setting.Companion.withDependency
import de.hxp.hxpaddons.clickgui.settings.WipModule
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.clickgui.settings.impl.SelectorSetting
import de.hxp.hxpaddons.events.RenderEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.Color.Companion.multiplyAlpha
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.render.EntityOutlineESP
import de.hxp.hxpaddons.utils.render.drawFilledBox
import de.hxp.hxpaddons.utils.render.drawWireFrameBox
import de.hxp.hxpaddons.utils.renderBoundingBox
import de.hxp.hxpaddons.utils.skyblock.Island
import de.hxp.hxpaddons.utils.skyblock.LocationUtils
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB

/**
 * Highlights Garden pests through walls. Pests aren't a distinct entity type on Hypixel - they're regular
 * mobs (Silverfish/Bat/etc.) whose own display name is just the pest's name (no separate health-bar
 * ArmorStand like dungeon mobs use, see [de.hxp.hxpaddons.features.impl.dungeon.StarMobESP]) - detection
 * here matches that name directly, the same principle SkyHanni's PestApi uses (github.com/hannibal002/SkyHanni,
 * GPL-3.0; logic reimplemented independently here, not ported).
 *
 * Marked [WipModule] since the pest name list/detection couldn't be verified against a live garden - enable
 * via `/hxp wip` to test and report back if any pest doesn't get picked up.
 */
@WipModule
object PestESP : Module(
    name = "Pest ESP",
    description = "Highlights garden pests through walls.",
    category = Category.GARDEN
) {
    private val espType by SelectorSetting("ESP Type", "Outline", listOf("Outline", "Box"), desc = "Whether pests are rendered as a glowing outline of the mob itself, or a 3D box around it.")
    private val outlineOpacity by NumberSetting("Outline Opacity", 100, 0, 100, 1, unit = "%", desc = "Opacity of the glowing outline.").withDependency { espType == ESP_TYPE_OUTLINE }
    private val boxOutlineWidth by NumberSetting("Box Outline Width", 3, 0, 10, 1, desc = "Width of the box's outline.").withDependency { espType == ESP_TYPE_BOX }
    private val boxOpacity by NumberSetting("Box Opacity", 50, 0, 100, 1, unit = "%", desc = "Opacity of the box's fill.").withDependency { espType == ESP_TYPE_BOX }

    private val pestColor by ColorSetting("Pest Color", Colors.MINECRAFT_GREEN, true, desc = "The color pests are highlighted in.")

    // Indices into the "ESP Type" SelectorSetting's option list above.
    private const val ESP_TYPE_OUTLINE = 0
    private const val ESP_TYPE_BOX = 1

    // The exact display names Hypixel gives pest mobs, lowercased for a case-insensitive match.
    private val pestNames = hashSetOf(
        "beetle", "cricket", "earthworm", "field mouse", "fly", "locust", "lunar moth", "mite",
        "mosquito", "moth", "rat", "slug", "praying mantis", "firefly", "dragonfly"
    )

    private val entities = mutableSetOf<Entity>()

    init {
        on<TickEvent.End> {
            if (!enabled || !LocationUtils.isCurrentArea(Island.Garden)) return@on

            entities.clear()
            mc.level?.entitiesForRendering()?.forEach { e ->
                if (e !is LivingEntity || e is Player || e is ArmorStand || !e.isAlive) return@forEach
                if (e.name.string.noControlCodes.lowercase() !in pestNames) return@forEach
                entities.add(e)
            }
        }

        on<RenderEvent.Extract> {
            if (!enabled || !LocationUtils.isCurrentArea(Island.Garden)) {
                EntityOutlineESP.clear()
                return@on
            }

            // Rebuilt every frame from the currently tracked entities, same as StarMobESP, so a pest that
            // stops being tracked (dies, ESP Type gets switched) never keeps glowing from a stale entry.
            EntityOutlineESP.clear()
            entities.forEach { entity ->
                if (!entity.isAlive) return@forEach
                if (espType == ESP_TYPE_OUTLINE) EntityOutlineESP.set(entity, pestColor.multiplyAlpha(outlineOpacity / 100f).rgba)
                else drawPestBox(entity.renderBoundingBox)
            }
        }

        on<WorldEvent.Load> {
            entities.clear()
            EntityOutlineESP.clear()
        }
    }

    private fun RenderEvent.Extract.drawPestBox(aabb: AABB) {
        drawFilledBox(aabb, pestColor.multiplyAlpha(boxOpacity / 100f), depth = false)
        drawWireFrameBox(aabb, pestColor, thickness = boxOutlineWidth.toFloat(), depth = false)
    }
}
