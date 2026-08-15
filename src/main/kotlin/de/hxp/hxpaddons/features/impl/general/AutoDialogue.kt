package de.hxp.hxpaddons.features.impl.general

import de.hxp.hxpaddons.events.ChatPacketEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.sendCommand
import net.minecraft.network.chat.ClickEvent

/**
 * NPC dialogue prompts ("Select an option: ...") render each choice as a clickable component whose click
 * event runs a `/cc` (choose-continue) command server-side - picking the first option is enough to advance
 * every dialogue that only ever offers one, so this just runs whatever that first choice's click event
 * would have run.
 */
object AutoDialogue : Module(
    name = "Auto Dialogue",
    description = "Automatically continues dialogues with NPCs."
) {
    init {
        on<ChatPacketEvent> {
            if (!value.startsWith("Select an option: ") || "[BARBARIANS] [MAGES]" in value) return@on

            val firstChoice = component.siblings.getOrNull(0) ?: return@on
            val runCommand = firstChoice.style.clickEvent as? ClickEvent.RunCommand ?: return@on
            sendCommand(runCommand.command.removePrefix("/"))
        }
    }
}
