package de.hxp.hxpaddons.utils

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.utils.network.hypixelapi.HypixelData
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile
import java.util.*

const val ID = "id"
const val UUID_STRING = "uuid"

inline val ItemStack.customData: CompoundTag
    get() =
        getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()

inline val ItemStack.itemId: String
    get() =
        customData.getString(ID).orElse("")

inline val CompoundTag.itemId: String
    get() =
        getString(ID).orElse("")

inline val ItemStack.itemUUID: String
    get() =
        customData.getString(UUID_STRING).orElse("")

inline val ItemStack.lore: List<Component>
    get() =
        getOrDefault(DataComponents.LORE, ItemLore.EMPTY).styledLines()

inline val ItemStack.loreString: List<String>
    get() =
        lore.map { it.string }

inline val HypixelData.ItemData.magicalPower: Int
    get() =
        getSkyblockRarity(lore)?.mp?.let { if (id == "HEGEMONY_ARTIFACT") it * 2 else it } ?: 0

val ItemStack.texture: String?
    get() =
        get(DataComponents.PROFILE)?.partialProfile()?.properties?.get("textures")?.firstOrNull()?.value

/**
 * Every readable "custom disguise" signal a single item carries - its skull skin [texture] value, each
 * non-blank [net.minecraft.world.item.component.CustomModelData] string, and its
 * [DataComponents.ITEM_MODEL] identifier if set AND different from the item's own default (e.g. a plain Iron
 * Pickaxe's [DataComponents.ITEM_MODEL] is present but just reads `minecraft:iron_pickaxe` - its own vanilla
 * id, not a disguise; only a value that differs from that default is an actual resource-pack remap). Shared by
 * [de.hxp.hxpaddons.features.impl.render.CustomESP]'s Custom Texture match mode and
 * [de.hxp.hxpaddons.features.impl.skyblock.OdonataESP]'s Head Texture detection mode - both need to identify
 * whichever mechanism Hypixel actually used to disguise something (a skull skin, or a resource-pack-remapped
 * model via CustomModelData/ItemModel), without needing to already know which one in advance.
 *
 * Bugfix (2026-08-16, on request - "Skin/Model ID: minecraft:iron_pickaxe das kommt nicht hin oder?"): every
 * item carries an [DataComponents.ITEM_MODEL] component by default (it's how the client knows what to render
 * at all), so comparing it for presence alone flagged EVERY item as "disguised" via its own plain id -
 * `/hxp esp mob`'s Skin/Model ID field surfaced this for a completely undisguised Iron Pickaxe. Now only
 * counted as a signal when it doesn't match [BuiltInRegistries.ITEM]'s own key for the item.
 */
val ItemStack.disguiseSignals: List<String>
    get() {
        if (isEmpty) return emptyList()
        val signals = mutableListOf<String>()
        texture?.let { signals.add(it) }
        get(DataComponents.CUSTOM_MODEL_DATA)?.strings()?.forEach { if (it.isNotBlank()) signals.add(it) }
        get(DataComponents.ITEM_MODEL)?.let { modelId ->
            if (modelId != BuiltInRegistries.ITEM.getKey(item)) signals.add(modelId.toString())
        }
        return signals
    }

/**
 * Every readable "disguise" signal a whole entity carries - unlike [ItemStack.disguiseSignals] (equipped-item
 * only), this also covers a mob disguised as its OWN entity rather than via something it's holding/wearing:
 * a fake [Player]-type entity rendered with a custom [GameProfile] skin (Hypixel's other common disguise
 * mechanism - e.g. Shadow Assassin, see [de.hxp.hxpaddons.features.impl.dungeon.StarMobESP]'s own
 * uuid-version-2 check for the same underlying trick), read the same way [texture] reads an item's skull skin.
 * Added 2026-08-16 on request ("aber mir gehts doch um den mob und nicht das item") after `/hxp esp mob`'s
 * Skin/Model ID field only ever looked at held/worn items and came back empty for a mob disguised this way.
 * Falls back to every equipped item's own [ItemStack.disguiseSignals] across all [EquipmentSlot]s either way,
 * so both disguise mechanisms are covered by one call.
 */
val Entity.disguiseSignals: List<String>
    get() {
        val signals = mutableListOf<String>()
        if (this is Player) gameProfile.properties.get("textures").firstOrNull()?.value?.let { signals.add(it) }
        if (this is LivingEntity) EquipmentSlot.entries.forEach { signals.addAll(getItemBySlot(it).disguiseSignals) }
        return signals
    }

val strengthRegex = Regex("Strength: \\+(\\d+)")

inline val ItemStack.strength: Int
    get() = this.loreString.firstOrNull {
        it.startsWith("Strength:")
    }?.let { lineString ->
        strengthRegex.find(lineString)?.groups?.get(1)?.value?.toIntOrNull()
    } ?: 0

enum class ItemRarity(
    val loreName: String,
    val colorCode: String,
    val color: Color,
    val mp: Int,
) {
    COMMON("COMMON", "§f", Colors.WHITE, 3),
    UNCOMMON("UNCOMMON", "§2", Colors.MINECRAFT_GREEN, 5),
    RARE("RARE", "§9", Colors.MINECRAFT_BLUE, 8),
    EPIC("EPIC", "§5", Colors.MINECRAFT_DARK_PURPLE, 12),
    LEGENDARY("LEGENDARY", "§6", Colors.MINECRAFT_GOLD, 16),
    MYTHIC("MYTHIC", "§d", Colors.MINECRAFT_LIGHT_PURPLE, 22),
    DIVINE("DIVINE", "§b", Colors.MINECRAFT_AQUA, 0),
    SPECIAL("SPECIAL", "§c", Colors.MINECRAFT_RED, 3),
    VERY_SPECIAL("VERY SPECIAL", "§c", Colors.MINECRAFT_RED, 5);
}

private val rarityRegex = Regex("(${ItemRarity.entries.joinToString("|") { it.loreName }}) ?([A-Z ]+)?")

fun getSkyblockRarity(lore: List<String>): ItemRarity? {
    for (i in lore.indices.reversed()) {
        val rarity = rarityRegex.find(lore[i])?.groups?.get(1)?.value ?: continue
        return ItemRarity.entries.find { it.loreName == rarity }
    }
    return null
}

fun createSkullStack(textureHash: String): ItemStack {
    val stack = ItemStack(Items.PLAYER_HEAD)

    val property = Property(
        "textures",
        Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$textureHash\"}}}".toByteArray())
    )
    val multimap = ImmutableMultimap.builder<String, Property>().put("textures", property).build()
    val gameProfile = GameProfile(UUID.randomUUID(), "_", PropertyMap(multimap))

    stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(gameProfile))
    return stack
}

fun ItemStack.isEtherwarpItem(): CompoundTag? =
    customData.takeIf { it.getInt("ethermerge").orElse(0) == 1 || it.itemId == "ETHERWARP_CONDUIT" }

fun ItemStack.hasGlint(): Boolean =
    get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == true

fun EquipmentSlot.isItem(itemId: String): Boolean =
    mc.player?.getItemBySlot(this)?.itemId == itemId
