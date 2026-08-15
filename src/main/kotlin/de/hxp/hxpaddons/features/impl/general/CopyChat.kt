package de.hxp.hxpaddons.features.impl.general

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.AlwaysActive
import de.hxp.hxpaddons.clickgui.settings.impl.SelectorSetting
import de.hxp.hxpaddons.events.ScreenEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.mixin.accessors.ChatComponentAccessor
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.client.gui.screens.ChatScreen
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

@AlwaysActive
object CopyChat : Module(
    name = "Copy Chat",
    description = "Copy chat messages by clicking on them.",
) {
    private val mode by SelectorSetting("Click Mode", "Right Click", listOf("Right Click", "Shift + Left Click"), desc = "How to trigger copying a chat message.")

    init {
        on<ScreenEvent.MouseClick> {
            if (!enabled || screen !is ChatScreen) return@on
            val isRightClick = click.button() == 1 && mode == 0
            val isShiftLeft = click.button() == 0 && mode == 1 && (click.modifiers() and GLFW.GLFW_MOD_SHIFT) != 0
            if (!isRightClick && !isShiftLeft) return@on

            val chatComp = mc.gui.getChat() as? ChatComponentAccessor ?: return@on
            val lines = chatComp.trimmedMessages
            val scrollPos = chatComp.chatScrollbarPos

            val chatScale = mc.options.chatScale().get()
            val lineHeight = (9.0 * chatScale).roundToInt().coerceAtLeast(1)
            val chatBottomY = mc.window.guiScaledHeight - 40
            val linesFromBottom = (chatBottomY - click.y().toInt()) / lineHeight

            val lineIndex = scrollPos + linesFromBottom
            if (lineIndex < 0 || lineIndex >= lines.size) return@on

            val text = buildFullMessage(lines, lineIndex)
            if (text.isBlank()) return@on

            mc.keyboardHandler.clipboard = text
            cancel()
        }
    }

    private fun buildFullMessage(lines: List<GuiMessage.Line>, clicked: Int): String {
        // Collect lines above clicked (going up = higher indices = older/earlier text).
        // Stop before the endOfEntry line of the previous message.
        val result = ArrayDeque<GuiMessage.Line>()
        var up = clicked + 1
        while (up < lines.size) {
            val line = lines[up]
            if (line.endOfEntry()) break
            result.addFirst(line)
            up++
        }

        // Collect clicked + lines below (going down = lower indices = newer/later text).
        // Stop after including the endOfEntry line of this message.
        var down = clicked
        while (down >= 0) {
            val line = lines[down]
            result.addLast(line)
            if (line.endOfEntry()) break
            down--
        }

        val sb = StringBuilder()
        for (line in result) line.content().accept { _, _, cp -> sb.appendCodePoint(cp); true }
        return sb.toString().trim()
    }
}
