package de.hxp.hxpaddons.features.impl.general

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.events.PacketEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.mixin.accessors.LanguageAccessor
import de.hxp.hxpaddons.mixin.accessors.LanguageManagerAccessor
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.resources.language.ClientLanguage
import net.minecraft.client.resources.language.LanguageInfo
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentContents
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.contents.KeybindContents
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import java.util.*
import kotlin.jvm.optionals.getOrNull

object ModIdHider : Module(
    name = "Mod ID Hider",
    description = "Hides your mod list and client brand from servers.",
    category = Category.GENERAL,
    toggled = true
) {
    @JvmStatic
    fun isActive() = enabled

    private val serverLanguages = IdentityHashMap<ClientPacketListener?, Language?>()
    private val language = LanguageAccessor.invokeLoadDefault()

    @JvmStatic
    fun getString(component: Component): String {
        if (!enabled) return component.string
        if (component !is MutableComponent) return component.string
        val sb = StringBuilder()
        visit(component.contents)?.let { sb.append(it) }
        for (sibling in component.siblings) sb.append(getString(sibling))
        return sb.toString()
    }

    private fun visit(contents: ComponentContents): String? = when (contents) {
        is KeybindContents if !canTranslate(contents.name) -> contents.name
        is TranslatableContents if !canTranslate(contents.key) -> contents.fallback ?: contents.key
        else -> contents.visit(Optional<String>::of).getOrNull()
    }

    private fun canTranslate(key: String): Boolean {
        if (mc.currentServer?.resourcePackStatus == ServerData.ServerPackStatus.ENABLED) {
            if (!serverLanguages.containsKey(mc.connection)) {
                if (serverLanguages.isNotEmpty()) serverLanguages.clear()
                serverLanguages[mc.connection] = createServerLanguage()
            }
            return serverLanguages[mc.connection]?.has(key) ?: language.has(key)
        }
        return language.has(key)
    }

    private fun createServerLanguage(): Language {
        val allPacks = mc.resourceManager.listPacks().toList()
        val packs = ArrayList<PackResources>().apply { add(allPacks.first()) }
        for (i in 1 until allPacks.size) {
            val src = allPacks[i].location().source()
            if (src == PackSource.FEATURE || src == PackSource.WORLD || src == PackSource.SERVER) {
                packs.add(allPacks[i])
            }
        }
        val rm = MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs)
        val code = mc.languageManager.selected
        var info: LanguageInfo? = null
        val languages = LanguageManagerAccessor.invokeExtractLanguages(rm.listPacks())
        val list = ArrayList<String>(2).apply { add("en_us") }
        var bidi = LanguageManagerAccessor.getDefaultLanguage().bidirectional()
        if (code != "en_us" && (languages[code].also { info = it }) != null) {
            list.add(code)
            bidi = info!!.bidirectional()
        }
        return ClientLanguage.loadFrom(rm, list, bidi)
    }

    init {
        on<PacketEvent.Send> {
            if (!enabled) return@on
            val pkt = packet as? ServerboundCustomPayloadPacket ?: return@on
            if (pkt.payload.type().id().namespace != "minecraft") {
                cancel()
            }
        }
    }
}
