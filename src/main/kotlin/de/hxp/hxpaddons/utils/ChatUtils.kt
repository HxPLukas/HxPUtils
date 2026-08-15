package de.hxp.hxpaddons.utils

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.mixin.accessors.ChatComponentAccessor
import de.hxp.hxpaddons.utils.network.DiscordLogger
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import java.util.IdentityHashMap

fun sendChatMessage(message: Any) {
    mc.execute { mc.player?.connection?.sendChat(message.toString()) }
}

fun sendCommand(command: String) {
    mc.execute { mc.player?.connection?.sendCommand(command) }
}

fun modMessage(message: Any?, prefix: String = "§3HxPUtils §8»§r ", chatStyle: Style? = null) {
    val text = Component.literal("$prefix$message")
    chatStyle?.let { text.setStyle(chatStyle) }
    mc.execute { mc.gui.chat.addClientSystemMessage(text) }
}

fun modMessage(message: Component, prefix: String = "§3HxPUtils §8»§r ", chatStyle: Style? = null) {
    val text = Component.literal(prefix).append(message)
    chatStyle?.let { text.setStyle(chatStyle) }
    mc.execute { mc.gui.chat.addClientSystemMessage(text) }
}

fun devMessage(message: Any?) {
    if (!ClickGUIModule.devMessage) return
    modMessage(message, "§3HxPUtils§bDev §8»§r ")
    DiscordLogger.log(message.toString())
}

fun getCenteredText(text: String): String {
    val strippedText = text.noControlCodes
    if (strippedText.isEmpty()) return text
    val textWidth = mc.font.width(strippedText)
    val chatWidth = ChatComponent.getWidth(mc.options.chatWidth().get())

    if (textWidth >= chatWidth) return text

    val spacesNeeded = ((chatWidth - textWidth) / 2 / 4).coerceAtLeast(0)
    return " ".repeat(spacesNeeded) + text
}

fun getChatBreak(): String {
    return ChatComponent.getWidth(mc.options.chatWidth().get()).let {
        "§9§m" + "-".repeat(it / mc.font.width("-"))
    }
}

/**
 * Per-line tags for [add]/[removeLines], keyed by chat-message identity rather than stored on [GuiMessage]
 * itself - avoids needing a Mixin just to stash one extra field on a vanilla class, at the cost of the map
 * needing manual cleanup (done in [removeLines]) instead of the tag just disappearing with the message.
 */
private val chatLineTags = IdentityHashMap<GuiMessage, Int>()

/**
 * Removes every chat line tagged with [id] (see [add]), or - for the very first replacement, before
 * anything carries that tag yet - matching [text] verbatim, then rebuilds the visible/trimmed lines from
 * what's left.
 */
fun removeLines(id: Int, text: String): Boolean {
    val chatComp = mc.gui.chat as? ChatComponentAccessor ?: return false

    val toRemove = chatComp.allMessages.filter {
        (id != 0 && chatLineTags[it] == id) || (text.isNotEmpty() && it.content().string.noControlCodes == text)
    }
    if (toRemove.isEmpty()) return false

    chatComp.allMessages.removeAll(toRemove)
    toRemove.forEach { chatLineTags.remove(it) }
    chatComp.invokeRefreshTrimmedMessages()
    return true
}

/**
 * Adds [message] to chat tagged with [id] (0 = untagged) so a later call can find and replace this exact
 * line via [removeLines] even once its displayed text no longer matches the original (e.g. CompactChat's
 * "(n)" counter suffix) - any existing line already carrying [id] is dropped first.
 */
fun ChatComponent.add(message: Component, id: Int) {
    if (id != 0) removeLines(id, "")
    addClientSystemMessage(message)
    if (id != 0) (this as ChatComponentAccessor).allMessages.firstOrNull()?.let { chatLineTags[it] = id }
}