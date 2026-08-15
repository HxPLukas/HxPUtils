package de.hxp.hxpaddons.features.impl.general

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.StringSetting
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.Colors
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.chat.contents.PlainTextContents

/**
 * Cosmetically replaces every occurrence of your own name with another (in a configurable color) wherever
 * it shows up in chat text and the tab list (player list) - join/leave messages, guild/party/co-op chat,
 * achievement broadcasts, your own tab-list row, etc.
 *
 * Purely client-side rendering - only this client ever sees the replacement. Chat packets and the tab
 * list's underlying [net.minecraft.client.multiplayer.PlayerInfo]/[com.mojang.authlib.GameProfile] data are
 * left untouched; only the [net.minecraft.network.chat.Component] that actually gets drawn to the screen is
 * rewritten. The server, Hypixel, and every other player still see and log your real name exactly as before.
 *
 * The name to look for is always your own account name ([mc]'s game profile, falling back to the launcher
 * session name if no player entity exists yet) - not user-entered, so it can't drift out of sync if you
 * change accounts.
 *
 * Chat is hooked via Fabric API's `MODIFY_GAME` event directly (not this mod's own
 * [de.hxp.hxpaddons.events.ChatPacketEvent], which only supports hiding a message outright, not rewriting
 * it); the tab list is hooked via a mixin into [net.minecraft.client.gui.components.PlayerTabOverlay]'s
 * `getNameForDisplay`, both funneling into [applyIfEnabled] so the same replacement logic (and the enabled
 * check) only lives in one place. [withNameReplaced] rebuilds the passed-in component tree: each
 * [PlainTextContents] leaf gets its matching text swapped for the replacement (styled with the chosen color,
 * everything else keeping that leaf's original style), non-literal content (translatable/score/etc.) is left
 * untouched, and siblings are processed recursively - so formatting/hover/click events elsewhere in the
 * component survive intact.
 *
 * The floating name tag above your own head is hooked via a mixin into [net.minecraft.client.renderer.entity.EntityRenderer]'s
 * `getNameTag`, restricted to `entity == mc.player` so it never touches other players'/mobs' name tags.
 *
 * Only matches the target name as a whole token, not a substring inside a longer name/word - e.g. with
 * target "Lukas", "Lukas" and "xX_Lukas" both match but "Lukasstrasse" or "xLukasx" don't, since the
 * characters immediately before/after the match must not themselves be name characters (letter/digit/`_`,
 * the only characters Minecraft usernames can contain).
 *
 * Not covered: the scoreboard sidebar also shows text but isn't touched - Hypixel draws it via team
 * prefix/suffix wrapped around blank score-holder names (see [de.hxp.hxpaddons.utils.readPurseBalance]'s own
 * doc) rather than your literal username, so it's unlikely to ever need a name here anyway.
 */
object NameChanger : Module(
    name = "Name Changer",
    description = "Replaces your own name with another one (in a configurable color) wherever it shows up in chat and the tab list.",
    category = Category.GENERAL
) {
    private val targetName: String get() = mc.player?.gameProfile?.name ?: mc.user.name
    private val replacementName by StringSetting("Replacement", "", 32, "The name shown instead.")
    private val replacementColor by ColorSetting("Replacement Color", Colors.MINECRAFT_AQUA, desc = "Color the replacement name is shown in.")

    init {
        ClientReceiveMessageEvents.MODIFY_GAME.register { message, _ -> applyIfEnabled(message) }
    }

    /** Shared by the chat hook above and [de.hxp.hxpaddons.mixin.mixins.PlayerTabOverlayMixin] for the tab list - single place for the enabled check and the actual replacement. */
    @JvmStatic
    fun applyIfEnabled(component: Component): Component {
        val target = targetName.trim()
        val replacement = replacementName.trim()
        if (!enabled || target.isEmpty() || replacement.isEmpty() || !component.string.contains(target, ignoreCase = true)) {
            return component
        }
        return component.withNameReplaced(target, replacement, TextColor.fromRgb(replacementColor.rgba and 0xFFFFFF))
    }

    private fun Component.withNameReplaced(target: String, replacement: String, color: TextColor): Component {
        val ownStyle = style
        val rebuiltOwn: MutableComponent = when (val c = contents) {
            is PlainTextContents -> splitAndReplace(c.text(), target, replacement, color, ownStyle)
            else -> MutableComponent.create(c).withStyle(ownStyle)
        }
        for (sibling in siblings) rebuiltOwn.append(sibling.withNameReplaced(target, replacement, color))
        return rebuiltOwn
    }

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun splitAndReplace(text: String, target: String, replacement: String, color: TextColor, ownStyle: Style): MutableComponent {
        var result: MutableComponent? = null
        var searchFrom = 0
        var appendedUpTo = 0
        while (true) {
            val idx = text.indexOf(target, searchFrom, ignoreCase = true)
            if (idx < 0) break
            val matchEnd = idx + target.length
            val boundedBefore = idx == 0 || !isNameChar(text[idx - 1])
            val boundedAfter = matchEnd == text.length || !isNameChar(text[matchEnd])
            if (!boundedBefore || !boundedAfter) {
                searchFrom = idx + 1
                continue
            }

            if (idx > appendedUpTo) {
                val prefix = Component.literal(text.substring(appendedUpTo, idx)).withStyle(ownStyle)
                result = result?.append(prefix) ?: prefix
            }
            val replaced = Component.literal(replacement).withStyle(ownStyle.withColor(color))
            result = result?.append(replaced) ?: replaced
            appendedUpTo = matchEnd
            searchFrom = matchEnd
        }

        if (appendedUpTo < text.length) {
            val suffix = Component.literal(text.substring(appendedUpTo)).withStyle(ownStyle)
            result = result?.append(suffix) ?: suffix
        }
        return result ?: Component.literal("").withStyle(ownStyle)
    }
}
