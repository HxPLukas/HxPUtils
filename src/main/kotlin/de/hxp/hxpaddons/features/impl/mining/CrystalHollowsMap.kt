package de.hxp.hxpaddons.features.impl.mining

import de.hxp.hxpaddons.clickgui.settings.impl.BooleanSetting
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.render.drawImage
import de.hxp.hxpaddons.utils.render.text
import de.hxp.hxpaddons.utils.skyblock.Island
import de.hxp.hxpaddons.utils.skyblock.LocationUtils
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f

/**
 * from quoi (GPL-3.0), adapted to HxPAddons' own settings/HUD system
 * original: https://github.com/pigeonlover1998/quoi/blob/26.1.x/src/main/kotlin/quoi/module/impl/mining/CrystalHollowsMap.kt
 * - the map background/marker images are the same assets as the original; the loaded-chunk overlay and
 * griefer-tracker integration weren't ported since this codebase has no equivalent scanning for either.
 */
object CrystalHollowsMap : Module(
    name = "Crystal Hollows Map",
    description = "Shows a live overview map of Crystal Hollows with your position, other players, and landmark structures found by Crystal Hollows Structure Finder.",
    category = Category.custom("Mining")
) {
    private val drawPlayers by BooleanSetting("Draw Players", true, desc = "Shows other nearby players as markers on the map.")
    private val drawNames by BooleanSetting("Draw Names", false, desc = "Shows player names next to their markers.")
    private val drawStructures by BooleanSetting("Draw Structures", true, desc = "Shows landmark structures found by Crystal Hollows Structure Finder.")
    private val markerScale by NumberSetting("Marker Scale", default = 1f, min = 0.5f, max = 3f, increment = 0.1f, desc = "Scale of player markers on the map.")

    // World coordinate bounds of Crystal Hollows and the map image's own pixel size - same values as quoi.
    private const val X_MIN = 202
    private const val X_MAX = 823
    private const val Z_MIN = 202
    private const val Z_MAX = 823
    private const val MAP_IMAGE_SIZE = 2613
    private const val MAP_SIZE = 300
    private const val MARKER_WIDTH = 10
    private const val MARKER_HEIGHT = 14
    private const val STRUCTURE_MARKER_SIZE = 4

    private val MAP_IMAGE = Identifier.fromNamespaceAndPath("hxpaddons", "textures/mining/crystal_hollows_map.png")
    private val GREEN_MARKER = Identifier.fromNamespaceAndPath("hxpaddons", "textures/mining/green_marker.png")
    private val WHITE_MARKER = Identifier.fromNamespaceAndPath("hxpaddons", "textures/mining/white_marker.png")

    private fun worldToMap(value: Double, min: Int, max: Int): Float =
        (((value - min) / (max - min).toFloat()) * MAP_SIZE).toFloat().coerceIn(0f, MAP_SIZE.toFloat())

    private fun Double.mapX() = worldToMap(this, X_MIN, X_MAX)
    private fun Double.mapZ() = worldToMap(this, Z_MIN, Z_MAX)

    val hud = +HUD(name = "Crystal Hollows Map", desc = "Live overview map of Crystal Hollows.") { example ->
        if (example) {
            drawImage(MAP_IMAGE, 0, 0, MAP_SIZE, MAP_SIZE, MAP_IMAGE_SIZE, MAP_IMAGE_SIZE)
            return@HUD MAP_SIZE to MAP_SIZE
        }
        if (!LocationUtils.isCurrentArea(Island.CrystalHollows)) return@HUD 0 to 0

        drawImage(MAP_IMAGE, 0, 0, MAP_SIZE, MAP_SIZE, MAP_IMAGE_SIZE, MAP_IMAGE_SIZE)

        if (drawStructures) {
            CrystalHollowsStructureFinder.foundStructures.forEach { (structure, positions) ->
                positions.forEach { pos ->
                    val x = pos.x.toDouble().mapX()
                    val z = pos.z.toDouble().mapZ()
                    val half = STRUCTURE_MARKER_SIZE / 2
                    fill((x - half).toInt(), (z - half).toInt(), (x + half).toInt(), (z + half).toInt(), structure.colour.rgba)
                }
            }
        }

        val self = mc.player

        if (drawPlayers) {
            mc.level?.players()?.forEach { p ->
                if (p == self) return@forEach
                val x = p.x.mapX()
                val z = p.z.mapZ()
                val w = MARKER_WIDTH * markerScale
                val h = MARKER_HEIGHT * markerScale
                drawImage(WHITE_MARKER, (x - w / 2).toInt(), (z - h / 2).toInt(), w.toInt(), h.toInt(), MARKER_WIDTH, MARKER_HEIGHT)
                if (drawNames) text(p.name.string, (x + w / 2).toInt(), (z - h / 2).toInt())
            }
        }

        self?.let { player ->
            val x = player.x.mapX()
            val z = player.z.mapZ()
            val w = MARKER_WIDTH * markerScale
            val h = MARKER_HEIGHT * markerScale

            pose().pushMatrix()
            pose().translate(x, z)
            pose().mul(Matrix3x2f().identity().rotate(Math.toRadians(player.yRot.toDouble() + 180.0).toFloat()))
            drawImage(GREEN_MARKER, (-w / 2).toInt(), (-h / 2).toInt(), w.toInt(), h.toInt(), MARKER_WIDTH, MARKER_HEIGHT)
            pose().popMatrix()
        }

        MAP_SIZE to MAP_SIZE
    }
}
