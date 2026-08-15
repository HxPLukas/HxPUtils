package de.hxp.hxpaddons.features.impl.render

import de.hxp.hxpaddons.utils.disguiseSignals
import de.hxp.hxpaddons.utils.noControlCodes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity

/**
 * One saved, multi-field target for [CustomESP]'s "Target Profiles" system (on request - "wie im chat filter
 * ein neues gui ... nur das ich dann dazu noch rechts einen add button hat welche ein neues gui öffnet wo man
 * alle daten angeben kann"): unlike the module's single [CustomESP] `Entity Names` field (one string, matched
 * against whichever one Match Mode picks), a profile can constrain several independent signals at once, all
 * of which must match together for a given entity to satisfy this profile ("und" across fields) - a blank
 * field is a wildcard and never excludes a match (on request - "wenn nichts angegeben ist soll er nach allen
 * die zum namen passen scannen"), so e.g. only [name] filled in behaves exactly like Name Tag mode's simple
 * search, while filling in both [name] and [heldItem] narrows it down to only entities matching both (on
 * request - "wenn ich jetzt aber in der hand angebe z.b iron sword. bei name zombie das er dann nur diese
 * findet"). An entity matches [CustomESP]'s target-profile system as a whole if it satisfies ANY one profile
 * in the list ("oder" across profiles) - see [CustomESP.matchesAnyProfile].
 *
 * All matching fields are compared the same way: case-insensitive substring, exactly like every other text
 * match in this module. Armor started as one combined field matched against any of the 4 armor slots, split
 * into one field per slot (2026-08-16, on request - "unterteile armor bitte in alle 4 pieces einzeln") so a
 * profile can pin down e.g. specifically the boots instead of "any armor piece contains X".
 *
 * [label]/[enabled] (2026-08-16, on request - "das man filter an und aus togglen kann [und] ihnen namen geben
 * kann") are management-only, not matching fields: [label] is purely a user-chosen display name for the
 * profile list row (distinct from [name], which still matches the ENTITY's own nametag) - falls back to
 * [summary] when left blank. [enabled] lets a saved profile be temporarily excluded from matching without
 * deleting/retyping it - see the early return in [matches].
 */
data class TargetProfile(
    /** User-chosen display label for this profile's row in [de.hxp.hxpaddons.clickgui.TargetProfilesScreen] - purely cosmetic, falls back to [summary] when blank. NOT a matching field, unlike every property below. */
    var label: String = "",
    /** Whether this profile currently takes part in matching at all - toggled from the profile list without needing to delete/retype it. */
    var enabled: Boolean = true,
    /** Matched against [Entity.getType]'s translated description, e.g. "Zombie". */
    var entityType: String = "",
    /** Matched against the entity's current display name/nametag - same field [CustomESP]'s own Name Tag mode reads. */
    var name: String = "",
    /** Matched against any [de.hxp.hxpaddons.utils.disguiseSignals] value found on the entity itself - either its own fake-player skin (if it's secretly a disguised [net.minecraft.world.entity.player.Player]) or any equipped slot's skull skin texture/CustomModelData string/ItemModel id - same signal [CustomESP]'s texture discovery logging and [de.hxp.hxpaddons.features.impl.skyblock.OdonataESP] read. */
    var skinId: String = "",
    /** Matched against the [EquipmentSlot.MAINHAND] item's display name, e.g. "Iron Sword". */
    var heldItem: String = "",
    /** Matched against the [EquipmentSlot.HEAD] item's display name. */
    var helmet: String = "",
    /** Matched against the [EquipmentSlot.CHEST] item's display name. */
    var chestplate: String = "",
    /** Matched against the [EquipmentSlot.LEGS] item's display name. */
    var leggings: String = "",
    /** Matched against the [EquipmentSlot.FEET] item's display name. */
    var boots: String = ""
) {
    val isBlank: Boolean get() = entityType.isBlank() && name.isBlank() && skinId.isBlank() && heldItem.isBlank() &&
        helmet.isBlank() && chestplate.isBlank() && leggings.isBlank() && boots.isBlank()

    /** Auto-generated one-line description of only the match fields actually filled in - ignores [label]/[enabled] entirely, see [summary] for the label-aware display version used in the profile list row. */
    fun autoSummary(): String {
        val parts = buildList {
            if (entityType.isNotBlank()) add("Type: $entityType")
            if (name.isNotBlank()) add("Name: $name")
            if (skinId.isNotBlank()) add("Skin: ${if (skinId.length > 16) skinId.take(16) + "..." else skinId}")
            if (heldItem.isNotBlank()) add("Held: $heldItem")
            if (helmet.isNotBlank()) add("Helmet: $helmet")
            if (chestplate.isNotBlank()) add("Chest: $chestplate")
            if (leggings.isNotBlank()) add("Legs: $leggings")
            if (boots.isNotBlank()) add("Boots: $boots")
        }
        return if (parts.isEmpty()) "(empty profile)" else parts.joinToString(" | ")
    }

    /** Display text for the profile list row - [label] (if the user gave this profile one) followed by [autoSummary], or just [autoSummary] alone otherwise. */
    fun summary(): String = if (label.isNotBlank()) "$label — ${autoSummary()}" else autoSummary()

    /** Whether [entity] satisfies every one of this profile's non-blank fields - always false while [enabled] is off. */
    fun matches(entity: Entity): Boolean {
        if (!enabled) return false
        if (entityType.isNotBlank() && !entity.type.description.string.contains(entityType, ignoreCase = true)) return false
        if (name.isNotBlank() && !entity.name.string.noControlCodes.contains(name, ignoreCase = true)) return false

        val living = entity as? LivingEntity
        if (skinId.isNotBlank() && entity.disguiseSignals.none { it.contains(skinId, ignoreCase = true) }) return false
        if (heldItem.isNotBlank() && !slotMatches(living, EquipmentSlot.MAINHAND, heldItem)) return false
        if (helmet.isNotBlank() && !slotMatches(living, EquipmentSlot.HEAD, helmet)) return false
        if (chestplate.isNotBlank() && !slotMatches(living, EquipmentSlot.CHEST, chestplate)) return false
        if (leggings.isNotBlank() && !slotMatches(living, EquipmentSlot.LEGS, leggings)) return false
        if (boots.isNotBlank() && !slotMatches(living, EquipmentSlot.FEET, boots)) return false
        return true
    }

    private fun slotMatches(living: LivingEntity?, slot: EquipmentSlot, query: String): Boolean {
        val stack = living?.getItemBySlot(slot) ?: return false
        return !stack.isEmpty && stack.hoverName.string.noControlCodes.contains(query, ignoreCase = true)
    }
}
