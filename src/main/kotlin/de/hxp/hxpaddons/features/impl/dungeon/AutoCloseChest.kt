package de.hxp.hxpaddons.features.impl.dungeon

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.events.core.onReceive
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.inventory.MenuType

/**
 * Ported from NoammAddons' "Close Chest" setting (features/impl/dungeon/Secrets.kt): a dungeon secret
 * chest's loot is already granted from the initial block interaction, so its inventory screen is just a
 * visual extra step - this cancels that screen from ever opening and immediately tells the server to
 * close it again, skipping straight past the GUI instead of requiring a manual close.
 */
object AutoCloseChest : Module(
    name = "Auto Close Chest",
    description = "Instantly closes dungeon secret chests instead of opening their inventory screen.",
    category = Category.DUNGEON
) {
    private val chestTitles = setOf("Chest", "Large Chest")

    init {
        onReceive<ClientboundOpenScreenPacket> { event ->
            if (!enabled || !DungeonUtils.inDungeons) return@onReceive
            if (type != MenuType.GENERIC_9x3 && type != MenuType.GENERIC_9x6) return@onReceive
            if (title.string !in chestTitles) return@onReceive

            mc.player?.connection?.send(ServerboundContainerClosePacket(containerId))
            event.cancel()
        }
    }
}
