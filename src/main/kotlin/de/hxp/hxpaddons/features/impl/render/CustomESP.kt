package de.hxp.hxpaddons.features.impl.render

import de.hxp.hxpaddons.clickgui.TargetProfilesScreen
import de.hxp.hxpaddons.clickgui.settings.Setting.Companion.withDependency
import de.hxp.hxpaddons.clickgui.settings.impl.ActionSetting
import de.hxp.hxpaddons.clickgui.settings.impl.BooleanSetting
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.ListSetting
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
import de.hxp.hxpaddons.utils.devMessage
import de.hxp.hxpaddons.utils.disguiseSignals
import de.hxp.hxpaddons.utils.handlers.schedule
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.render.EntityOutlineESP
import de.hxp.hxpaddons.utils.render.drawFilledBox
import de.hxp.hxpaddons.utils.render.drawText
import de.hxp.hxpaddons.utils.render.drawWireFrameBox
import de.hxp.hxpaddons.utils.renderBoundingBox
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
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
 * [showEntityNames] also on, each entity's own name/type is additionally drawn as floating text above it (see
 * [drawText]) - lets a player identify an unfamiliar entity's exact name/type before adding it to the list, on
 * request ("ob sachen bei denen man sich unsicher ist wie das entity heißt auch espn zu können"). 2026-08-13:
 * [showEntityNames] originally only applied in debug mode (split out into its own explicit toggle on request -
 * "es soll nicht immer so sein sondern nur wenn das feld leer ist und es dann da eingestellt ist"); broadened
 * back the same way 2026-08-16 on request ("hinzufügen das er ... darüber den namen anzeigt", e.g. searching
 * for "Treasure Hunter" and seeing that name drawn above whatever it matched) - it now shows a name/type label
 * above ANY highlighted entity, whether that's debug mode's highlight-everything or a real filtered search.
 * [labelSize] (2026-08-13, on request - "die namen von den entity in der größe verändern") is a user-controlled multiplier on top of the
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
 * [particleDebugShowNames] also on (same idea as [showEntityNames], only enabled while [particleTypes] is
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
 * resolves to the real mob entity standing directly beneath it (see [resolveMobBelow], same core idea
 * [StarMobESP] already uses, but preferring the exact riding relationship over a proximity guess where
 * possible - a crowded room with several similar mobs standing close together could otherwise resolve to the
 * *wrong* neighbor) - previously a match against the ArmorStand's name (which is where dungeon-style mobs
 * like "Crypt Ghoul" actually carry their display name, not the mob entity itself) got silently dropped by
 * [ignoreArmorStandVisual] (on by default) before ever reaching the render pass, making the match look like
 * it never fired.
 *
 * Custom Texture match mode (2026-08-16, on request - "kann man die auch irgendwie scannen", after explaining
 * how Hypixel commonly disguises a mob/NPC with a player-head item carrying a custom skin instead of a
 * readable name, e.g. Odonata-style hidden mobs). Originally just the equipped `HEAD` slot's skull skin
 * texture (see [de.hxp.hxpaddons.utils.texture]) - broadened the same day after live testing against Odonata
 * specifically showed "no head texture" even though it visibly renders as something other than particles,
 * meaning it isn't a skull-in-the-HEAD-slot disguise at all. [disguiseSignals] now checks every equipped slot
 * (not just HEAD) for any of three possible signal types: skull skin texture, `CustomModelData` strings, or
 * an `ItemModel` identifier - whichever mechanism Hypixel actually used, without needing to already know
 * which one in advance. Any matched value is just as reliable a match key as a name (identical every time
 * that exact disguise is worn), and equipment data isn't necessarily gated behind the same close-range
 * requirement Hypixel applies to some nametags. Since the combined value can be long/unreadable and is
 * impossible to copy out of rendered 3D world text, [logDiscoveredTexture] substitutes a short "Texture #N" id for
 * the floating label and chat-dumps a full per-slot breakdown (item id + every signal found, or "no signal"
 * if an equipped item's disguise mechanism still isn't one of the three recognized types) the first time each
 * distinct combination is seen, so the actual copy/paste-into-Entity-Names step happens via chat instead.
 *
 * Entirely unverified live - first time this exact matching+labeling combination gets exercised.
 */
object CustomESP : Module(
    name = "Custom ESP",
    description = "Highlights entities (and optionally particles) matching a name/type list through walls - leave Entity Names empty to highlight and label everything.",
    category = Category.RENDER
) {
    private val matchMode by SelectorSetting(
        "Match Mode", "Entity Type", listOf("Entity Type", "Name Tag", "Custom Texture"),
        desc = "Whether the Entity Names list is matched against each entity's Minecraft type (e.g. \"Zombie\"), its current display name/nametag, or any visual disguise signal (skull skin texture, resource-pack model id) found across all of its equipped items - for entities disguised via a custom item instead of a readable name, e.g. hidden mobs/NPCs."
    )
    private val entityNames by StringSetting(
        "Entity Names", "", length = 256,
        desc = "Comma-separated list to match (e.g. \"Zombie, Skeleton\", or a raw value from the Custom Texture debug dump) - matched as a case-insensitive substring against whichever Match Mode picks. Leave empty to highlight (and label) every entity instead - useful for figuring out what an unfamiliar entity is actually called, or which texture/model id it carries in Custom Texture mode."
    )
    private val ignoreEntityNames by StringSetting(
        "Ignore Entity Names", "", length = 256,
        desc = "Comma-separated list to NEVER highlight, matched the same way (case-insensitive substring, same Match Mode) as Entity Names - takes priority over everything else, including a match in Entity Names itself and the empty-list highlight-everything debug mode."
    )
    val targetProfiles by ListSetting<TargetProfile, MutableList<TargetProfile>>("Target Profiles", mutableListOf())

    init {
        registerSetting(ActionSetting(
            "Manage Target Profiles",
            "Opens a manager for advanced multi-field targets (entity type, name, skin/model id, held item, armor) - independent of Entity Names/Match Mode above. An entity matches a profile if ALL of that profile's filled-in fields match; leave a field blank to ignore it. Highlighted if it matches ANY saved profile."
        ) { schedule(0) { mc.setScreen(TargetProfilesScreen) } })
    }
    private val scanDistanceInput by StringSetting(
        "Scan Distance", "64", length = 10,
        desc = "Maximum distance (in blocks) an entity can be from you to get matched/highlighted - type any number, no fixed upper limit."
    )
    private val showEntityNames by BooleanSetting(
        "Show Entity Names", false,
        desc = "Shows each highlighted entity's name/type as floating text above it - both while highlighting everything (empty Entity Names) and while actively matching a specific search, e.g. seeing \"Treasure Hunter\" above whatever that search matched."
    )
    private val labelSize by NumberSetting(
        "Label Size", 1.0, 0.1, 5.0, 0.1,
        desc = "Scales the floating name labels (Show Entity Names and Debug: Show Particle Names) up or down - they otherwise auto-scale with distance to stay a roughly constant apparent size, this is a multiplier on top of that."
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
    private const val MATCH_MODE_TEXTURE = 2

    /** [scanDistanceInput]'s fallback if the typed text doesn't parse as a number at all. */
    private const val DEFAULT_SCAN_DISTANCE = 64.0

    /** [scanDistanceInput]'s sanity ceiling - not a real functional limit (per request, "wenn es viel fps zieht ist egal"), just a guard against a typo like an extra zero causing a multi-million-block radius scan. */
    private const val MAX_SCAN_DISTANCE = 100_000.0

    private val entities = mutableSetOf<Entity>()
    /** ArmorStands matched via a Name Tag search (see the redirect logic below) - highlighted regardless of [ignoreArmorStandVisual], since these are the actual nametag holder, not a decorative ArmorStand that setting is meant to filter out. Rebuilt every tick alongside [entities]. */
    private val forceVisibleArmorStands = mutableSetOf<Entity>()
    private var debugModeActive = false

    /** Raw skin-texture value -> short sequential display id, in discovery order - see [logDiscoveredTexture]'s own doc. Cleared on world change. */
    private val discoveredTextures = linkedMapOf<String, Int>()

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
            // Only "highlight everything" while NEITHER matching path has anything specified - a non-empty
            // Target Profiles list (even with Entity Names left blank) means the profiles are deliberately
            // doing the filtering instead, not "match nothing so show everything".
            debugModeActive = names.isEmpty() && targetProfiles.isEmpty()
            val maxDistSq = currentScanDistance() * currentScanDistance()

            entities.clear()
            forceVisibleArmorStands.clear()
            mc.level?.entitiesForRendering()?.forEach { e ->
                if (e === player || !e.isAlive) return@forEach
                if (player.distanceToSqr(e) > maxDistSq) return@forEach
                val label = labelFor(e)
                // Ignore list wins over everything else - a match here excludes the entity even if it also
                // matched Entity Names, or would've been swept in by the empty-list debug/highlight-everything
                // mode (on request - "wenn nichts eingegeben ist oder es so weit matchen würde er trotzdem
                // geht das ich die ignoren lassen kann").
                if (ignoreNames.any { name -> label.contains(name, ignoreCase = true) }) return@forEach
                val matchedBySearch = names.any { name -> label.contains(name, ignoreCase = true) }
                // Target Profiles (see TargetProfile.kt's own doc) - an entirely separate, independent
                // matching path from Entity Names/Match Mode above: each profile can constrain several
                // fields (type, name, skin/model id, held item, armor) at once, all of which must match
                // together, and an entity is highlighted if it satisfies ANY one saved profile.
                val matchedByProfile = targetProfiles.any { it.matches(e) }
                if (debugModeActive || matchedBySearch || matchedByProfile) {
                    entities.add(e)
                    // Dungeon-style mobs (e.g. "Crypt Ghoul") carry their real display name on a separate
                    // invisible ArmorStand riding just above them, not on the mob entity itself - same
                    // pattern StarMobESP's own doc explains. Without this, a Name Tag match against that
                    // ArmorStand only ever highlighted the ArmorStand, which "Ignore Armor Stands
                    // (Box/Outline)" (on by default) then silently excluded at render time, making the
                    // match look like it never fired at all - on request, after exactly that symptom got
                    // reported ("er markiert mir garnichts mehr ... sobald ich irgendwas eingebe"). Also
                    // registers the real mob standing directly beneath the matched ArmorStand so IT gets
                    // the box/outline. The ArmorStand itself is force-visible too (on request, "er soll den
                    // armorstand und mob highlighten fürs erste") via [forceVisibleArmorStands], bypassing
                    // [ignoreArmorStandVisual] entirely for this specific match - that setting is about
                    // filtering out decorative ArmorStands from debug/highlight-everything mode, not this
                    // explicit name match. A Target Profiles match on an ArmorStand resolves too - profiles
                    // exist specifically for this exact "named mob whose real entity carries nothing" case.
                    if (e is ArmorStand && (matchedByProfile || matchMode != MATCH_MODE_TYPE)) {
                        forceVisibleArmorStands.add(e)
                        resolveMobBelow(e)?.let { entities.add(it) }
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

                if (!(isArmorStand && ignoreArmorStandVisual) || entity in forceVisibleArmorStands) {
                    if (espType == ESP_TYPE_OUTLINE) EntityOutlineESP.set(entity, espColor.multiplyAlpha(outlineOpacity / 100f).rgba)
                    else drawEntityBox(entity.renderBoundingBox)
                }

                if (showEntityNames && !(isArmorStand && ignoreArmorStandLabels)) {
                    // Custom Texture mode still dev-logs newly discovered textures (see logDiscoveredTexture) for
                    // investigation purposes, but the floating label itself is always just the entity's plain
                    // name/type now (on request - "er soll mir den mob name drüber anzeigen ... niemals no
                    // equipped items oder so das ist unnötige informationen nur den name") regardless of
                    // Match Mode, instead of logDiscoveredTexture's Texture-mode-specific placeholder text.
                    if (matchMode == MATCH_MODE_TEXTURE) logDiscoveredTexture(entity)
                    val box = entity.renderBoundingBox
                    val labelPos = Vec3((box.minX + box.maxX) / 2.0, box.maxY + 0.3, (box.minZ + box.maxZ) / 2.0)
                    val dist = player?.position()?.distanceTo(labelPos) ?: 0.0
                    drawText(entity.name.string.noControlCodes, labelPos, labelScale(dist), false)
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
            discoveredTextures.clear()
            EntityOutlineESP.clear()
        }
    }

    /**
     * Resolves a name-holder ArmorStand down to the real mob it belongs to - on request, after a report that
     * a crowded room (several similar mobs standing close together, e.g. a Crypt farming spot) could highlight
     * the *wrong* neighboring mob ("das er das falsche filtert was ich garnicht will"). Tries the exact
     * relationship first: if the ArmorStand is riding something (Hypixel's common implementation for this
     * exact nametag-holder pattern), that vehicle IS the mob, no ambiguity possible. Only falls back to a
     * proximity search (closest non-ArmorStand entity in a small box just below the ArmorStand, not just
     * whatever [net.minecraft.world.level.Level.getEntities] happens to return first) for ArmorStands that
     * aren't riding anything, which is inherently a best-effort guess in a crowded room.
     */
    private fun resolveMobBelow(armorStand: ArmorStand): Entity? {
        val vehicle = armorStand.vehicle
        if (vehicle != null && vehicle !is ArmorStand && vehicle.isAlive) return vehicle

        return mc.level?.getEntities(armorStand, armorStand.boundingBox.move(0.0, -1.0, 0.0)) { it !is ArmorStand && it.isAlive }
            ?.minByOrNull { it.distanceToSqr(armorStand) }
    }

    /**
     * The entity's type name (e.g. "Zombie") in [MATCH_MODE_TYPE], every [de.hxp.hxpaddons.utils.disguiseSignals]
     * value found across ALL of its equipped slots (not just [EquipmentSlot.HEAD] - a disguise doesn't have to
     * be a custom-skin skull; Odonata turned out to have none, see [de.hxp.hxpaddons.features.impl.skyblock.OdonataESP]'s own doc for the full story)
     * joined together in [MATCH_MODE_TEXTURE] (empty string if it's not a [LivingEntity], or has no equipped
     * item carrying any signal at all - matches nothing, same as any other blank search term would), its
     * current display name/nametag otherwise - this is the value actually compared against
     * [entityNames]/[ignoreEntityNames]. For the debug-mode floating label specifically, see [logDiscoveredTexture]
     * instead - the raw joined value can be long/unreadable and impossible to copy back out of 3D world text.
     */
    private fun labelFor(entity: Entity): String = when (matchMode) {
        MATCH_MODE_TYPE -> entity.type.description.string
        MATCH_MODE_TEXTURE -> (entity as? LivingEntity)
            ?.let { living -> EquipmentSlot.entries.flatMap { living.getItemBySlot(it).disguiseSignals } }
            ?.joinToString("|").orEmpty()
        else -> entity.name.string.noControlCodes
    }

    /**
     * Called purely for its side effect in [MATCH_MODE_TEXTURE] - the floating label itself is always just
     * the entity's plain name/type now (see the render pass above), this only assigns each distinct combined
     * signal value seen a short sequential id ("Texture #1", "Texture #2", ...) via [discoveredTextures] and
     * logs it once via [devMessage] (only visible with "Developer Message" on) the first time it's seen -
     * moved off [modMessage]/regular chat (2026-08-16, on request - originally spammed regular chat during the
     * Odonata investigation ("er spammt mir immernoch mein chat voll er muss jetzt nichts mehr loggen"), then
     * re-added as a dev-only log instead of removed outright ("mach den dump wieder rein bei developer
     * message")) - stays out of the way normally but is still there to turn on when actually investigating a
     * new disguise.
     */
    private fun logDiscoveredTexture(entity: Entity): String {
        if (matchMode != MATCH_MODE_TEXTURE) return labelFor(entity)
        val living = entity as? LivingEntity ?: return "(not a living entity)"
        val equipped = EquipmentSlot.entries.mapNotNull { slot ->
            val stack = living.getItemBySlot(slot)
            if (stack.isEmpty) null else slot to stack
        }
        if (equipped.isEmpty()) return "(no equipped items)"

        val combined = labelFor(entity)
        val index = discoveredTextures.getOrPut(combined) {
            val next = discoveredTextures.size + 1
            val entityInfo = "${BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)} name=\"${entity.name.string.noControlCodes}\""
            val details = equipped.joinToString(" | ") { (slot, stack) ->
                val itemId = BuiltInRegistries.ITEM.getKey(stack.item)
                val signals = stack.disguiseSignals
                "$slot=$itemId" + if (signals.isNotEmpty()) " [${signals.joinToString(", ")}]" else " [no signal]"
            }
            devMessage("Custom ESP debug: Texture #$next ($entityInfo) -> $details")
            next
        }
        return "Texture #$index"
    }

    /** [drawText]'s scale for a label [dist] blocks away - auto-scales with distance to stay a roughly constant apparent size (same formula [de.hxp.hxpaddons.features.impl.mining.CrystalHollowsStructureFinder]/[de.hxp.hxpaddons.features.impl.mining.GoldenDragonFinder] already use), times [labelSize] on top as a user-controlled multiplier. */
    private fun labelScale(dist: Double): Float = max(1f, (dist * 0.03).toFloat()) * labelSize.toFloat()

    private fun RenderEvent.Extract.drawEntityBox(aabb: AABB) {
        drawFilledBox(aabb, espColor.multiplyAlpha(boxOpacity / 100f), depth = false)
        drawWireFrameBox(aabb, espColor, thickness = boxOutlineWidth.toFloat(), depth = false)
    }

    /**
     * `/hxp esp dump` - on request ("mach den full dump ... ich will alles an infos haben was man bekommen
     * kann"), dumps everything readily readable about whatever entity is currently under the crosshair to
     * chat: identity (type, UUID, name, distance), live state (pose, invisible, velocity - the last one is
     * the only signal here that actually distinguishes "moving/alive-behaving" from "static prop", since
     * everything else is just a snapshot), riding relationships (what it's riding/being ridden by),
     * ArmorStand-specific flags ([ArmorStand.isMarker] especially - a marker ArmorStand has no
     * hitbox/collision at all and is almost always pure decoration, unlike an interactive disguised mob) and
     * limb poses, equipment (every slot's item id + [de.hxp.hxpaddons.utils.disguiseSignals], reusing the
     * exact same logic [labelFor]'s Custom Texture mode does), health, and - the actual "everything" answer -
     * every entry [net.minecraft.network.syncher.SynchedEntityData.getNonDefaultValues] returns, i.e. every
     * synced entity-data field currently holding a non-default value, by raw id and value. That's the
     * complete set of data this client has received about the entity at all.
     */
    fun dumpLookedAtEntity() {
        val target = (mc.hitResult as? EntityHitResult)?.entity
        if (target == null) {
            modMessage("§cNo entity under your crosshair right now - look directly at one and try again.")
            return
        }

        modMessage("§6=== Entity Dump: ${target.type.description.string.noControlCodes} ===")
        modMessage("§7Type: §f${BuiltInRegistries.ENTITY_TYPE.getKey(target.type)}")
        modMessage("§7UUID: §f${target.uuid}")
        modMessage("§7Distance: §f${"%.2f".format(mc.player?.distanceTo(target) ?: -1.0)}")
        modMessage("§7hasCustomName: §f${target.hasCustomName()} §7name: §f${target.name.string.noControlCodes}")
        modMessage("§7Pose: §f${target.pose} §7Invisible: §f${target.isInvisible} §7Velocity: §f${target.deltaMovement}")

        target.vehicle?.let { modMessage("§7Riding: §f${BuiltInRegistries.ENTITY_TYPE.getKey(it.type)}") }
        if (target.passengers.isNotEmpty()) {
            modMessage("§7Passengers: §f${target.passengers.joinToString(", ") { p -> "${BuiltInRegistries.ENTITY_TYPE.getKey(p.type)}" }}")
        }

        if (target is ArmorStand) {
            modMessage("§7ArmorStand: isMarker=${target.isMarker} isSmall=${target.isSmall} showArms=${target.showArms()} showBasePlate=${target.showBasePlate()}")
            modMessage(
                "§7  HeadPose=${target.headPose} BodyPose=${target.bodyPose} LeftArmPose=${target.leftArmPose} " +
                    "RightArmPose=${target.rightArmPose} LeftLegPose=${target.leftLegPose} RightLegPose=${target.rightLegPose}"
            )
        }

        if (target is LivingEntity) {
            modMessage("§7Health: §f${target.health}/${target.maxHealth}")
            val equipment = EquipmentSlot.entries.mapNotNull { slot ->
                val stack = target.getItemBySlot(slot)
                if (stack.isEmpty) null else {
                    val signals = stack.disguiseSignals
                    "$slot=${BuiltInRegistries.ITEM.getKey(stack.item)}" + if (signals.isNotEmpty()) " [${signals.joinToString(", ")}]" else ""
                }
            }
            modMessage(if (equipment.isEmpty()) "§7Equipment: §fnone" else "§7Equipment: §f${equipment.joinToString(" | ")}")
        }

        val values = target.entityData.nonDefaultValues.orEmpty()
        modMessage("§7Synced data (${values.size} non-default entries):")
        for (value in values) {
            modMessage("§8  #${value.id()}: §f${value.value()}")
        }
    }
}
