package de.hxp.hxpaddons.features.impl.general

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.WipModule
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
 * it shows up in chat text - join/leave messages, guild/party/co-op chat, achievement broadcasts, etc.
 *
 * The name to look for is always your own account name ([mc]'s game profile, falling back to the launcher
 * session name if no player entity exists yet) - not user-entered, so it can't drift out of sync if you
 * change accounts.
 *
 * Hooks Fabric API's `MODIFY_GAME` event directly (not this mod's own [de.hxp.hxpaddons.events.ChatPacketEvent],
 * which only supports hiding a message outright, not rewriting it) and rebuilds the message's component tree:
 * each [PlainTextContents] leaf gets its matching text swapped for the replacement (styled with the chosen
 * color, everything else keeping that leaf's original style), non-literal content (translatable/score/etc.)
 * is left untouched, and siblings are processed recursively - so formatting/hover/click events elsewhere in
 * the message survive intact.
 *
 * WIP: only chat text is covered so far. The floating name tag above your own head, the tab list and the
 * scoreboard all show your name too but aren't touched yet - those need dedicated render-level mixins rather
 * than this chat hook.
 */
@WipModule
object NameChanger : Module(
    name = "Name Changer",
    description = "Replaces your own name with another one (in a configurable color) wherever it shows up in chat.",
    category = Category.GENERAL
) {
    private val targetName: String get() = mc.player?.gameProfile?.name ?: mc.user.name
    private val replacementName by StringSetting("Replacement", "", 32, "The name shown instead.")
    private val replacementColor by ColorSetting("Replacement Color", Colors.MINECRAFT_AQUA, desc = "Color the replacement name is shown in.")

    init {
        ClientReceiveMessageEvents.MODIFY_GAME.register { message, _ ->
            val target = targetName.trim()
            val replacement = replacementName.trim()
            if (!enabled || target.isEmpty() || replacement.isEmpty() || !message.string.contains(target, ignoreCase = true)) {
                return@register message
            }
            message.withNameReplaced(target, replacement, TextColor.fromRgb(replacementColor.rgba and 0xFFFFFF))
        }
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

    private fun splitAndReplace(text: String, target: String, replacement: String, color: TextColor, ownStyle: Style): MutableComponent {
        var result: MutableComponent? = null
        var remaining = text
        while (true) {
            val idx = remaining.indexOf(target, ignoreCase = true)
            if (idx < 0) {
                val piece = Component.literal(remaining).withStyle(ownStyle)
                result = result?.append(piece) ?: piece
                break
            }
            if (idx > 0) {
                val prefix = Component.literal(remaining.substring(0, idx)).withStyle(ownStyle)
                result = result?.append(prefix) ?: prefix
            }
            val replaced = Component.literal(replacement).withStyle(ownStyle.withColor(color))
            result = result?.append(replaced) ?: replaced
            remaining = remaining.substring(idx + target.length)
        }
        return result ?: Component.literal("").withStyle(ownStyle)
    }
}
