package de.hxp.hxpaddons.features.impl.render

import de.hxp.hxpaddons.clickgui.settings.Setting.Companion.withDependency
import de.hxp.hxpaddons.clickgui.settings.impl.BooleanSetting
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.clickgui.settings.impl.SelectorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.StringSetting
import de.hxp.hxpaddons.events.RenderEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.events.core.onReceive
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.Color.Companion.multiplyAlpha
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.render.EntityOutlineESP
import de.hxp.hxpaddons.utils.render.drawFilledBox
import de.hxp.hxpaddons.utils.render.drawText
import de.hxp.hxpaddons.utils.render.drawWireFrameBox
import de.hxp.hxpaddons.utils.renderBoundingBox
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/**
 * General-purpose through-walls entity ESP, unlike [de.hxp.hxpaddons.features.impl.dungeon.StarMobESP]/
 * [de.hxp.hxpaddons.features.impl.garden.PestESP] (both hardcoded to a fixed mob list and a specific island/
 * dungeon context) - matches any entity in the loaded world against a player-typed, comma-separated list, by
 * either its Minecraft entity type ([matchMode] "Entity Type") or its current display name/nametag
 * ("Name Tag"), same visual configuration (Outline/Box, opacity, box width) as those two modules. Originally
 * shipped as "Entity ESP" (2026-08-13), renamed to "Custom ESP" the same day on request.
 *
 * [ignoreEntityNames] (2026-08-13, on request - the inverse of [entityNames], "wenn nichts eingegeben ist oder
 * es so weit matchen würde er trotzdem geht das ich die ignoren lassen kann") is a blocklist checked BEFORE
 * [entityNames]/debug mode - a match here excludes the entity even if it would otherwise have matched
 * [entityNames] or been swept in by the empty-list highlight-everything debug mode, so it can carve out
 * exceptions from either.
 *
 * Debug mode ([entityNames] left empty): highlights literally every entity instead of filtering. With
 * [debugShowNames] also on (2026-08-13: split out into its own explicit toggle, on request - "es soll nicht
 * immer so sein sondern nur wenn das feld leer ist und es dann da eingestellt ist" - labeling used to be
 * unconditional the moment the list was empty, now it's an opt-in on top of that), each entity's own name/type
 * is additionally drawn as floating text above it (see [drawText]) - lets a player identify an unfamiliar
 * entity's exact name/type before adding it to the list, on request ("ob sachen bei denen man sich unsicher
 * ist wie das entity heißt auch espn zu können"). [debugShowNames] itself only has any effect (and only shows
 * up enabled in the Click GUI, via [withDependency]) while [entityNames] is empty. [labelSize] (2026-08-13, on
 * request - "die namen von den entity in der größe verändern") is a user-controlled multiplier on top of the
 * distance-based auto-scaling both this label and the particle debug label ([particleDebugShowNames]) already
 * use (see [labelScale]) - shared by both rather than two separate size settings.
 *
 * [scanDistanceInput] bounds how far away an entity can be and still get matched/highlighted - added on
 * request ("eine option für die distanz über die er scannt"), a plain radius check against the player's own
 * position. 2026-08-13: switched from a [NumberSetting] (a mouse-drag slider hard-clamped between fixed
 * min/max - no way to type past the visible max) to a typed [StringSetting], parsed as a plain number each
 * tick - on request ("das range limit höher machen und das per input feld einstellen lassen also zum
 * eintippen"). [MAX_SCAN_DISTANCE] is only a sanity ceiling against fat-fingering an extra zero, not a real
 * functional limit (per the same request - "wenn es viel fps zieht ist egal dann stellt man die distanz
 * niedriger").
 *
 * [boxOutlineWidth] controls the box ESP type's thickness (already existed). A matching thickness setting for
 * the OUTLINE type was also requested but isn't actually possible: [EntityOutlineESP] only overrides the
 * *color* of vanilla's own glowing-outline post-process effect (the same one behind the "Glowing" status
 * effect/spectator highlight, see [de.hxp.hxpaddons.mixin.mixins.EntityRendererMixin]) - that effect's line
 * width is baked into Minecraft's own outline shader/post-process pass, not something exposed per-entity (or
 * even per-module) through this hook. Left out rather than adding a setting that would silently do nothing.
 *
 * [particleESP] (2026-08-13, on request - "ein setting welche auch particles espt um auch sowas sehen zu
 * können", after researching [[project_custom_esp]]'s own memory note on how SkyHanni's `InvisibugHighlighter`
 * detects Invisibugs via their CRIT particle burst - see that entry for the full writeup): highlights
 * server-sent particles ([net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket], caught via
 * [onReceive] - already-existing generic packet-event infrastructure, no new mixin needed) matching
 * [particleTypes] (registry name, e.g. "crit"), same [espColor]/[currentScanDistance] as entity matching. An
 * empty [particleTypes] highlights every particle instead, same debug-style convention [entityNames] already
 * uses - initially left out on the assumption that particles' much higher volume would make that a spam/perf
 * problem, then added back the same day on request ("auch wenn er dann viele hat aber man kann particles ja
 * togglen dann ist das ja egal und trotzdem um neue sachen zu machen hilfreich" - Particle ESP is its own
 * toggle, so a noisy "everything" view is fine to turn on only while actively looking for something new). With
 * [particleDebugShowNames] also on (same idea as [debugShowNames], only enabled while [particleTypes] is
 * blank), each highlighted particle's registry name is drawn next to it. A particle is a one-tick event with
 * no entity to keep glowing, so matches are tracked as plain positions with a [particleHighlightMs] fade timer
 * ([TrackedParticle]) instead of being registered with [EntityOutlineESP] - always drawn as a small box
 * ([particleBoxSize]), the Outline ESP Type has no meaning for a bare position.
 *
 * ArmorStand handling (2026-08-13, on request - "ein setting um armorstand aus machen zu können", right after
 * [particleESP] shipped - ArmorStands are extremely common in Skyblock as shop displays/decoration/nametag
 * holders, cluttering debug mode especially): originally one `ignoreArmorStands` toggle that excluded them
 * from the whole match/highlight pass, split the same day into two independent settings on request ("in 2
 * unterteilen nähmlich einmal box/outline ... oder ob man nur die nametags ... sehen will") - ArmorStands are
 * now always tracked/matched normally, and [ignoreArmorStandVisual]/[ignoreArmorStandLabels] each separately
 * gate whether a matched one gets the box/outline highlight vs. the debug-mode floating name label, so e.g.
 * an ArmorStand's name can be shown without boxing it, or vice versa. Both default on (same net default
 * behavior the single old toggle had). Deliberately entity-side only - doesn't affect [particleESP] at all.
 *
 * Name Tag mode + ArmorStand-held mob names (2026-08-15, bugfix): a matched ArmorStand's highlight now also
 * resolves to the real mob entity standing directly beneath it (same technique [StarMobESP] already uses) -
 * previously a match against the ArmorStand's name (which is where dungeon-style mobs like "Crypt Ghoul"
 * actually carry their display name, not the mob entity itself) got silently dropped by
 * [ignoreArmorStandVisual] (on by default) before ever reaching the render pass, making the match look like
 * it never fired.
 *
 * Entirely unverified live - first time this exact matching+labeling combination gets exercised.
 */
object CustomESP : Module(
    name = "Custom ESP",
    description = "Highlights entities (and optionally particles) matching a name/type list through walls - leave Entity Names empty to highlight and label everything.",
    category = Category.RENDER
) {
    private val matchMode by SelectorSetting(
        "Match Mode", "Entity Type", listOf("Entity Type", "Name Tag"),
        desc = "Whether the Entity Names list is matched against each entity's Minecraft type (e.g. \"Zombie\") or its current display name/nametag."
    )
    private val entityNames by StringSetting(
        "Entity Names", "", length = 256,
        desc = "Comma-separated list to match (e.g. \"Zombie, Skeleton\") - matched as a case-insensitive substring against whichever Match Mode picks. Leave empty to highlight (and label) every entity instead - useful for figuring out what an unfamiliar entity is actually called."
    )
    private val ignoreEntityNames by StringSetting(
        "Ignore Entity Names", "", length = 256,
        desc = "Comma-separated list to NEVER highlight, matched the same way (case-insensitive substring, same Match Mode) as Entity Names - takes priority over everything else, including a match in Entity Names itself and the empty-list highlight-everything debug mode."
    )
    private val scanDistanceInput by StringSetting(
        "Scan Distance", "64", length = 10,
        desc = "Maximum distance (in blocks) an entity can be from you to get matched/highlighted - type any number, no fixed upper limit."
    )
    private val debugShowNames by BooleanSetting(
        "Debug: Show Entity Names", false,
        desc = "Shows each highlighted entity's name/type as floating text above it. Only has any effect while Entity Names is empty (highlight-everything mode) - it's not meant to label a filtered list, only to help identify unfamiliar entities."
    ).withDependency { entityNames.isBlank() }
    private val labelSize by NumberSetting(
        "Label Size", 1.0, 0.1, 5.0, 0.1,
        desc = "Scales the floating name labels (Debug: Show Entity Names and Debug: Show Particle Names) up or down - they otherwise auto-scale with distance to stay a roughly constant apparent size, this is a multiplier on top of that."
    )
    private val ignoreArmorStandVisual by BooleanSetting(
        "Ignore Armor Stands (Box/Outline)", true,
        desc = "Excludes ArmorStand entities from the box/outline highlight - most are just decoration/shop displays/nametag holders, not something worth an ESP box for. Independent of Ignore Armor Stands (Names) below - an ArmorStand can still be labeled without being boxed."
    )
    private val ignoreArmorStandLabels by BooleanSetting(
        "Ignore Armor Stands (Names)", true,
        desc = "Excludes ArmorStand entities from the debug-mode floating name label (see Debug: Show Entity Names) - ArmorStands can still be matched/found via Entity Names either way, this only controls the label. Independent of Ignore Armor Stands (Box/Outline) above - e.g. turn this off but leave the box/outline one on to see an ArmorStand's name without boxing it."
    )

    private val particleESP by BooleanSetting(
        "Particle ESP", false,
        desc = "Also highlights matching particles through walls (e.g. the CRIT particle bursts an Invisibug gives off, its only visible sign) - lets you spot things that have no entity/nametag an Entity ESP could ever match."
    )
    private val particleTypes by StringSetting(
        "Particle Types", "", length = 128,
        desc = "Comma-separated particle type(s) to highlight (e.g. \"crit\" for Invisibugs) - matched as a case-insensitive substring against the particle's registry name. Leave empty to highlight every particle instead (same debug-style behavior as an empty Entity Names) - can be a lot at once, but Particle ESP is its own toggle, so just turn it off again once you've found what you're after."
    ).withDependency { particleESP }
    private val particleDebugShowNames by BooleanSetting(
        "Debug: Show Particle Names", false,
        desc = "Shows each highlighted particle's registry name as floating text next to it. Only has any effect while Particle Types is empty (highlight-everything mode) - helps identify a particle type worth adding to the list."
    ).withDependency { particleESP && particleTypes.isBlank() }
    private val particleBoxSize by NumberSetting("Particle Box Size", 0.3, 0.1, 2.0, 0.1, unit = " blocks", desc = "Size of the box drawn at each matched particle's position.").withDependency { particleESP }
    private val particleHighlightMs by NumberSetting("Particle Highlight Duration", 2000, 200, 10000, 100, unit = "ms", desc = "How long a matched particle's highlight box stays visible before fading out - a particle itself is a one-tick event, this is what makes it actually visible.").withDependency { particleESP }

    private val espType by SelectorSetting("ESP Type", "Outline", listOf("Outline", "Box"), desc = "Whether matched entities are rendered as a glowing outline of the entity itself, or a 3D box around it.")
    private val outlineOpacity by NumberSetting("Outline Opacity", 100, 0, 100, 1, unit = "%", desc = "Opacity of the glowing outline.").withDependency { espType == ESP_TYPE_OUTLINE }
    private val boxOutlineWidth by NumberSetting("Box Outline Width", 3, 0, 10, 1, desc = "Width/thickness of the box's outline.").withDependency { espType == ESP_TYPE_BOX }
    private val boxOpacity by NumberSetting("Box Opacity", 50, 0, 100, 1, unit = "%", desc = "Opacity of the box's fill.").withDependency { espType == ESP_TYPE_BOX }
    private val espColor by ColorSetting("ESP Color", Colors.MINECRAFT_RED, true, desc = "The color matched entities are highlighted in.")

    // Indices into the "ESP Type"/"Match Mode" SelectorSettings' option lists above.
    private const val ESP_TYPE_OUTLINE = 0
    private const val ESP_TYPE_BOX = 1
    private const val MATCH_MODE_TYPE = 0

    /** [scanDistanceInput]'s fallback if the typed text doesn't parse as a number at all. */
    private const val DEFAULT_SCAN_DISTANCE = 64.0

    /** [scanDistanceInput]'s sanity ceiling - not a real functional limit (per request, "wenn es viel fps zieht ist egal"), just a guard against a typo like an extra zero causing a multi-million-block radius scan. */
    private const val MAX_SCAN_DISTANCE = 100_000.0

    private val entities = mutableSetOf<Entity>()
    private var debugModeActive = false

    /** One matched particle's world position, registry name (for the debug-mode label) and when its highlight should disappear - see [particleESP]'s own doc. */
    private data class TrackedParticle(val pos: Vec3, val name: String, val expiresAtMs: Long)

    // CopyOnWriteArrayList, not a plain list: added to from the network thread (particle packets arrive via
    // onReceive, which - same as ChatPacketEvent elsewhere in this codebase - fires off the network thread,
    // not the render thread), read/pruned every render frame from RenderEvent.Extract. A plain MutableList
    // would risk a ConcurrentModificationException or a torn read across those two threads.
    private val trackedParticles = CopyOnWriteArrayList<TrackedParticle>()

    private fun currentScanDistance(): Double = scanDistanceInput.trim().toDoubleOrNull()?.coerceIn(1.0, MAX_SCAN_DISTANCE) ?: DEFAULT_SCAN_DISTANCE

    init {
        on<TickEvent.End> {
            if (!enabled) return@on
            val player = mc.player ?: return@on

            val names = entityNames.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val ignoreNames = ignoreEntityNames.split(",").map { it.trim() }.filter { it.isNotBlank() }
            debugModeActive = names.isEmpty()
            val maxDistSq = currentScanDistance() * currentScanDistance()

            entities.clear()
            mc.level?.entitiesForRendering()?.forEach { e ->
                if (e === player || !e.isAlive) return@forEach
                if (player.distanceToSqr(e) > maxDistSq) return@forEach
                val label = labelFor(e)
                // Ignore list wins over everything else - a match here excludes the entity even if it also
                // matched Entity Names, or would've been swept in by the empty-list debug/highlight-everything
                // mode (on request - "wenn nichts eingegeben ist oder es so weit matchen würde er trotzdem
                // geht das ich die ignoren lassen kann").
                if (ignoreNames.any { name -> label.contains(name, ignoreCase = true) }) return@forEach
                if (debugModeActive || names.any { name -> label.contains(name, ignoreCase = true) }) {
                    entities.add(e)
                    // Dungeon-style mobs (e.g. "Crypt Ghoul") carry their real display name on a separate
                    // invisible ArmorStand riding just above them, not on the mob entity itself - same
                    // pattern StarMobESP's own doc explains. Without this, a Name Tag match against that
                    // ArmorStand only ever highlighted the ArmorStand, which "Ignore Armor Stands
                    // (Box/Outline)" (on by default) then silently excluded at render time, making the
                    // match look like it never fired at all - on request, after exactly that symptom got
                    // reported ("er markiert mir garnichts mehr ... sobald ich irgendwas eingebe"). Also
                    // registers the real mob standing directly beneath the matched ArmorStand so IT gets
                    // the box/outline; the ArmorStand itself stays in the set too, purely so its name still
                    // shows correctly in the debug-mode label instead of the mob's own generic name.
                    if (matchMode != MATCH_MODE_TYPE && e is ArmorStand) {
                        mc.level?.getEntities(e, e.boundingBox.move(0.0, -1.0, 0.0)) { it !is ArmorStand && it.isAlive }
                            ?.firstOrNull()?.let { entities.add(it) }
                    }
                }
            }
        }

        // Server-sent particle bursts (ClientboundLevelParticlesPacket) - the same signal SkyHanni's own
        // InvisibugHighlighter reacts to (a CRIT particle is the only visible sign an Invisibug ever gives
        // off, having no entity model/nametag of its own). Deliberately just tracks the raw particle
        // position with a fade timer here, unlike SkyHanni's approach of also hunting down a nearby "blank"
        // ArmorStand to attach to - this is a general Particle ESP, not an Invisibug-specific feature, so it
        // has no per-particle-type entity-resolution logic to piggyback on.
        onReceive<ClientboundLevelParticlesPacket> {
            if (!enabled || !particleESP) return@onReceive
            val types = particleTypes.split(",").map { it.trim() }.filter { it.isNotBlank() }

            val registryName = BuiltInRegistries.PARTICLE_TYPE.getKey(particle.type)?.path ?: return@onReceive
            // Empty list = highlight everything, same debug-style convention as an empty Entity Names -
            // on request ("auch bei particles ... auch wenn er dann viele hat aber man kann particles ja
            // togglen dann ist das ja egal und trotzdem um neue sachen zu machen hilfreich").
            if (types.isNotEmpty() && types.none { registryName.contains(it, ignoreCase = true) }) return@onReceive

            val player = mc.player ?: return@onReceive
            val pos = Vec3(x, y, z)
            if (player.position().distanceToSqr(pos) > currentScanDistance() * currentScanDistance()) return@onReceive

            trackedParticles.add(TrackedParticle(pos, registryName, System.currentTimeMillis() + particleHighlightMs.toLong()))
        }

        on<RenderEvent.Extract> {
            if (!enabled) {
                EntityOutlineESP.clear()
                return@on
            }

            // Rebuilt every frame from the currently tracked entities, same as StarMobESP/PestESP, so an
            // entity that stops being tracked (dies, ESP Type gets switched) never keeps glowing from a
            // stale registry entry.
            EntityOutlineESP.clear()
            val player = mc.player
            entities.forEach { entity ->
                if (!entity.isAlive) return@forEach
                val isArmorStand = entity is ArmorStand

                if (!(isArmorStand && ignoreArmorStandVisual)) {
                    if (espType == ESP_TYPE_OUTLINE) EntityOutlineESP.set(entity, espColor.multiplyAlpha(outlineOpacity / 100f).rgba)
                    else drawEntityBox(entity.renderBoundingBox)
                }

                if (debugModeActive && debugShowNames && !(isArmorStand && ignoreArmorStandLabels)) {
                    val box = entity.renderBoundingBox
                    val labelPos = Vec3((box.minX + box.maxX) / 2.0, box.maxY + 0.3, (box.minZ + box.maxZ) / 2.0)
                    val dist = player?.position()?.distanceTo(labelPos) ?: 0.0
                    drawText(labelFor(entity), labelPos, labelScale(dist), false)
                }
            }

            if (particleESP) {
                trackedParticles.removeIf { it.expiresAtMs <= System.currentTimeMillis() }
                val half = particleBoxSize / 2.0
                val showNames = particleTypes.isBlank() && particleDebugShowNames
                trackedParticles.forEach { tracked ->
                    val aabb = AABB(
                        tracked.pos.x - half, tracked.pos.y - half, tracked.pos.z - half,
                        tracked.pos.x + half, tracked.pos.y + half, tracked.pos.z + half
                    )
                    drawEntityBox(aabb)

                    if (showNames) {
                        val labelPos = tracked.pos.add(0.0, half + 0.3, 0.0)
                        val dist = player?.position()?.distanceTo(labelPos) ?: 0.0
                        drawText(tracked.name, labelPos, labelScale(dist), false)
                    }
                }
            }
        }

        on<WorldEvent.Load> {
            entities.clear()
            trackedParticles.clear()
            EntityOutlineESP.clear()
        }
    }

    /** The entity's type name (e.g. "Zombie") in [MATCH_MODE_TYPE], its current display name/nametag otherwise - used both for matching against [entityNames] and as the debug-mode floating label. */
    private fun labelFor(entity: Entity): String =
        if (matchMode == MATCH_MODE_TYPE) entity.type.description.string
        else entity.name.string.noControlCodes

    /** [drawText]'s scale for a label [dist] blocks away - auto-scales with distance to stay a roughly constant apparent size (same formula [de.hxp.hxpaddons.features.impl.mining.CrystalHollowsStructureFinder]/[de.hxp.hxpaddons.features.impl.mining.GoldenDragonFinder] already use), times [labelSize] on top as a user-controlled multiplier. */
    private fun labelScale(dist: Double): Float = max(1f, (dist * 0.03).toFloat()) * labelSize.toFloat()

    private fun RenderEvent.Extract.drawEntityBox(aabb: AABB) {
        drawFilledBox(aabb, espColor.multiplyAlpha(boxOpacity / 100f), depth = false)
        drawWireFrameBox(aabb, espColor, thickness = boxOutlineWidth.toFloat(), depth = false)
    }
}
