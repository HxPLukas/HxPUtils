package de.hxp.hxpaddons.features.impl.dungeon

import de.hxp.hxpaddons.clickgui.settings.Setting.Companion.withDependency
import de.hxp.hxpaddons.clickgui.settings.impl.BooleanSetting
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.clickgui.settings.impl.SelectorSetting
import de.hxp.hxpaddons.events.RenderEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.Color.Companion.multiplyAlpha
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.render.EntityOutlineESP
import de.hxp.hxpaddons.utils.render.drawFilledBox
import de.hxp.hxpaddons.utils.render.drawWireFrameBox
import de.hxp.hxpaddons.utils.renderBoundingBox
import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonUtils
import de.hxp.hxpaddons.utils.skyblock.dungeon.ScanUtils
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB

/**
 * Same detection as OdinFabric's "Highlight Starred Mobs" (odtheking/OdinFabric, BSD-3-Clause, the mod this
 * project is forked from) - a starred dungeon mob's nametag matches a fixed pattern - but extended to also
 * highlight a set of unique/miniboss dungeon mobs regardless of star, each independently toggleable, and to
 * offer two through-walls ESP styles: a 3D box (see [drawMobBox]), or a glowing outline of the mob's actual
 * model (see [EntityOutlineESP]) - the same technique behind vanilla's "Glowing" effect/spectator highlight.
 */
object StarMobESP : Module(
    name = "Star Mob ESP",
    description = "Highlights starred dungeon mobs through walls.",
    category = Category.DUNGEON
) {
    private val espType by SelectorSetting("ESP Type", "Outline", listOf("Outline", "Box"), desc = "Whether mobs are rendered as a glowing outline of the mob itself, or a 3D box around it.")
    private val outlineOpacity by NumberSetting("Outline Opacity", 100, 0, 100, 1, unit = "%", desc = "Opacity of the glowing outline.").withDependency { espType == ESP_TYPE_OUTLINE }
    private val boxOutlineWidth by NumberSetting("Box Outline Width", 3, 0, 10, 1, desc = "Width of the box's outline.").withDependency { espType == ESP_TYPE_BOX }
    private val boxOpacity by NumberSetting("Box Opacity", 50, 0, 100, 1, unit = "%", desc = "Opacity of the box's fill.").withDependency { espType == ESP_TYPE_BOX }

    private val hideNonNames by BooleanSetting("Hide non-starred names", true, desc = "Hides names of entities that are not starred.")

    private val batESP by BooleanSetting("Bat ESP", true, desc = "Highlights Bats.")
    private val felsESP by BooleanSetting("Fels ESP", true, desc = "Highlights Fels.")
    private val shadowAssassinESP by BooleanSetting("Shadow Assassin ESP", true, desc = "Highlights Shadow Assassins.")
    private val minibossESP by BooleanSetting("Dungeon Miniboss ESP", false, desc = "Highlights dungeon minibosses (Dragon Adventurers, Frozen Adventurer, Angry Archaeologist).")

    private val starredColor by ColorSetting("Starred Mobs Color", Color("1BFF00FF"), true, desc = "The color of starred mobs.")

    private val separateMinibossColors by BooleanSetting("Separate Miniboss Colors", false, desc = "Uses a separate color for each miniboss type instead of one shared color.").withDependency { minibossESP }
    private val minibossColor by ColorSetting("Miniboss Color", Color("1B00FFFF"), true, desc = "The color of all dungeon minibosses.").withDependency { minibossESP && !separateMinibossColors }
    private val unstableDragonAdventurerColor by ColorSetting("Unstable Dragon Adventurer Color", Colors.MINECRAFT_GRAY, true, desc = "The color of the Unstable Dragon Adventurer.").withDependency { minibossESP && separateMinibossColors }
    private val youngDragonAdventurerColor by ColorSetting("Young Dragon Adventurer Color", Colors.MINECRAFT_GREEN, true, desc = "The color of the Young Dragon Adventurer.").withDependency { minibossESP && separateMinibossColors }
    private val superiorDragonAdventurerColor by ColorSetting("Superior Dragon Adventurer Color", Colors.MINECRAFT_GOLD, true, desc = "The color of the Superior Dragon Adventurer.").withDependency { minibossESP && separateMinibossColors }
    private val holyDragonAdventurerColor by ColorSetting("Holy Dragon Adventurer Color", Colors.WHITE, true, desc = "The color of the Holy Dragon Adventurer.").withDependency { minibossESP && separateMinibossColors }
    private val frozenAdventurerColor by ColorSetting("Frozen Adventurer Color", Colors.MINECRAFT_AQUA, true, desc = "The color of the Frozen Adventurer.").withDependency { minibossESP && separateMinibossColors }
    private val angryArchaeologistColor by ColorSetting("Angry Archaeologist Color", Colors.MINECRAFT_DARK_GRAY, true, desc = "The color of the Angry Archaeologist.").withDependency { minibossESP && separateMinibossColors }
    private val felsColor by ColorSetting("Fels Color", Color("FF00A8FF"), true, desc = "The color of Fels.").withDependency { felsESP }
    private val batColor by ColorSetting("Bat Color", Color("33D715FF"), true, desc = "The color of Bats.").withDependency { batESP }
    private val shadowAssassinColor by ColorSetting("Shadow Assassin Color", Color("3B003BFF"), true, desc = "The color of Shadow Assassins.").withDependency { shadowAssassinESP }

    // Indices into the "ESP Type" SelectorSetting's option list above.
    private const val ESP_TYPE_OUTLINE = 0
    private const val ESP_TYPE_BOX = 1

    private val dungeonMobSpawns = hashSetOf(
        "Lurker", "Dreadlord", "Souleater", "Zombie", "Skeleton", "Skeletor", "Sniper", "Super Archer", "Spider",
        "Fels", "Withermancer", "Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer", "Bat", "Shadow Assassin",
        "Unstable Dragon Adventurer", "Young Dragon Adventurer", "Superior Dragon Adventurer", "Holy Dragon Adventurer"
    )
    // https://regex101.com/r/QQf502/2
    private val starredRegex = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")

    private val entities = mutableMapOf<Entity, Color>()

    init {
        on<TickEvent.End> {
            if (!enabled || !DungeonUtils.inClear) return@on

            val entitiesToRemove = mutableListOf<Entity>()
            mc.level?.entitiesForRendering()?.forEach { e ->
                if (!e.isAlive) return@forEach

                // Fels spawns dormant (disguised as a head stuck in the floor, via the vanilla
                // "Dinnerbone" upside-down name trick on a real EnderMan) well before it wakes up and
                // gets the usual health-bar ArmorStand nametag the branch below relies on - same
                // detection NoammAddons uses for its own Star Mob ESP. Once it wakes up and renames
                // itself, this stops matching and the ArmorStand branch below takes over seamlessly.
                if (felsESP && e is EnderMan && e.name.string == "Dinnerbone" && inCurrentRoom(e)) {
                    entities[e] = felsColor
                    return@forEach
                }

                // Same story for Shadow Assassin: it's a real Player-type NPC entity (uuid version 2,
                // like other dungeon NPCs - see isValidEntity) whose name is already its real "Shadow
                // Assassin" profile name from the moment it spawns, well before any health-bar ArmorStand
                // nametag appears above it. Detected directly here instead of waiting for that nametag.
                if (shadowAssassinESP && e is Player && e.uuid.version() == 2 && e.name.string == "Shadow Assassin" && inCurrentRoom(e)) {
                    entities[e] = shadowAssassinColor
                    return@forEach
                }

                if (e !is ArmorStand) return@forEach

                val entityName = e.name.string
                if (!dungeonMobSpawns.any { it in entityName }) return@forEach

                val isStarred = starredRegex.matches(entityName)

                if (hideNonNames && e.isInvisible && !isStarred) entitiesToRemove.add(e)

                val color = resolveColor(entityName, isStarred) ?: return@forEach
                mc.level?.getEntities(e, e.boundingBox.move(0.0, -1.0, 0.0)) { isValidEntity(it) }
                    ?.firstOrNull()?.let { if (inCurrentRoom(it)) entities[it] = color }
            }
            entitiesToRemove.forEach { it.remove(Entity.RemovalReason.DISCARDED) }
            entities.entries.removeIf { (entity, _) -> !entity.isAlive || !inCurrentRoom(entity) }
        }

        on<RenderEvent.Extract> {
            if (!enabled || !DungeonUtils.inClear) {
                EntityOutlineESP.clear()
                return@on
            }

            // The outline registry is fully rebuilt every frame from the currently tracked entities rather
            // than incrementally patched, so an entity that stops being tracked (dies, leaves the room, ESP
            // Type gets switched to Box) never keeps glowing from a stale registry entry.
            EntityOutlineESP.clear()
            entities.forEach { (entity, color) ->
                if (!entity.isAlive) return@forEach
                if (espType == ESP_TYPE_OUTLINE) EntityOutlineESP.set(entity, color.multiplyAlpha(outlineOpacity / 100f).rgba)
                else drawMobBox(entity.renderBoundingBox, color)
            }
        }

        on<WorldEvent.Load> {
            entities.clear()
            EntityOutlineESP.clear()
        }
    }

    private fun resolveColor(entityName: String, isStarred: Boolean): Color? = when {
        isStarred -> starredColor
        batESP && "Bat" in entityName -> batColor
        felsESP && "Fels" in entityName -> felsColor
        shadowAssassinESP && "Shadow Assassin" in entityName -> shadowAssassinColor
        minibossESP && "Unstable Dragon Adventurer" in entityName -> if (separateMinibossColors) unstableDragonAdventurerColor else minibossColor
        minibossESP && "Young Dragon Adventurer" in entityName -> if (separateMinibossColors) youngDragonAdventurerColor else minibossColor
        minibossESP && "Superior Dragon Adventurer" in entityName -> if (separateMinibossColors) superiorDragonAdventurerColor else minibossColor
        minibossESP && "Holy Dragon Adventurer" in entityName -> if (separateMinibossColors) holyDragonAdventurerColor else minibossColor
        minibossESP && "Frozen Adventurer" in entityName -> if (separateMinibossColors) frozenAdventurerColor else minibossColor
        minibossESP && "Angry Archaeologist" in entityName -> if (separateMinibossColors) angryArchaeologistColor else minibossColor
        else -> null
    }

    private fun RenderEvent.Extract.drawMobBox(aabb: AABB, color: Color) {
        drawFilledBox(aabb, color.multiplyAlpha(boxOpacity / 100f), depth = false)
        drawWireFrameBox(aabb, color, thickness = boxOutlineWidth.toFloat(), depth = false)
    }

    private fun inCurrentRoom(entity: Entity): Boolean {
        val room = DungeonUtils.currentRoom ?: return false
        val cell = ScanUtils.getRoomCenter(entity.x.toInt(), entity.z.toInt())
        return room.roomComponents.any { it.vec2 == cell }
    }

    private fun isValidEntity(entity: Entity): Boolean =
        when (entity) {
            is ArmorStand -> false
            is WitherBoss -> false
            is Player -> entity.uuid.version() == 2 && entity != mc.player
            else -> !entity.isInvisible
        }
}
