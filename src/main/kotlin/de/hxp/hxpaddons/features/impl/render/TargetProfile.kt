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
 * All fields are matched the same way: case-insensitive substring, exactly like every other text match in
 * this module. Armor started as one combined field matched against any of the 4 armor slots, split into one
 * field per slot (2026-08-16, on request - "unterteile armor bitte in alle 4 pieces einzeln") so a profile can
 * pin down e.g. specifically the boots instead of "any armor piece contains X".
 */
data class TargetProfile(
    /** Matched against [Entity.getType]'s translated description, e.g. "Zombie". */
    var entityType: String = "",
    /** Matched against the entity's current display name/nametag - same field [CustomESP]'s own Name Tag mode reads. */
    var name: String = "",
    /** Matched against any [de.hxp.hxpaddons.utils.disguiseSignals] value found on ANY equipped slot (skull skin texture, CustomModelData string, or ItemModel id) - same signal [CustomESP]'s Custom Texture mode and [de.hxp.hxpaddons.features.impl.skyblock.OdonataESP] read. */
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

    /** Short one-line description for the profile list row - only the fields actually filled in. */
    fun summary(): String {
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

    /** Whether [entity] satisfies every one of this profile's non-blank fields. */
    fun matches(entity: Entity): Boolean {
        if (entityType.isNotBlank() && !entity.type.description.string.contains(entityType, ignoreCase = true)) return false
        if (name.isNotBlank() && !entity.name.string.noControlCodes.contains(name, ignoreCase = true)) return false

        val living = entity as? LivingEntity
        if (skinId.isNotBlank()) {
            val hasSignal = living != null && EquipmentSlot.entries.any { slot ->
                living.getItemBySlot(slot).disguiseSignals.any { it.contains(skinId, ignoreCase = true) }
            }
            if (!hasSignal) return false
        }
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
