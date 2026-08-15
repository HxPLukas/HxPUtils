package de.hxp.hxpaddons.features.impl.general

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.events.ChatPacketEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.mixin.accessors.ChatComponentAccessor
import de.hxp.hxpaddons.utils.ChatManager
import de.hxp.hxpaddons.utils.ChatManager.hideMessage
import de.hxp.hxpaddons.utils.add
import de.hxp.hxpaddons.utils.handlers.schedule
import de.hxp.hxpaddons.utils.removeLines
import net.minecraft.network.chat.Component

/**
 * A message repeated within [compactTime] of its last occurrence gets collapsed into that same line with
 * a "(n)" counter incremented in place, instead of spamming the chat with identical lines. Each tracked
 * message gets its own tag id (see [de.hxp.hxpaddons.utils.add]/[removeLines]) the first time it repeats,
 * so that same line keeps getting found and replaced on every further repeat even once its displayed text
 * (the counter suffix) no longer matches the original message.
 */
object CompactChat : Module(
    name = "Compact Chat",
    description = "Compacts repeated chat messages into a single counted line.",
) {
    private val compactTime by NumberSetting("Compact Time", 60, 5, 120, desc = "How long a repeated message keeps getting compacted into the same line before starting fresh.", unit = "s")

    /** One tracked message's repeat state - [tagId] is assigned once, from [nextTagId], the first time a message repeats. */
    private data class RepeatState(val count: Int, val lastSeenMs: Long, val tagId: Int)

    private val repeats = mutableMapOf<String, RepeatState>()
    private var nextTagId = 1

    init {
        on<ChatPacketEvent> {
            if (!enabled) return@on

            val msg = value.trim()
            if (msg.isEmpty() || msg.all { it == '-' || it == '=' || it == '▬' }) return@on

            val now = System.currentTimeMillis()
            // Anything past its own compaction window is done repeating - drop it so it starts fresh
            // (and doesn't just accumulate here forever) if it ever shows up again later.
            repeats.entries.removeIf { (_, state) -> now - state.lastSeenMs >= compactTime * 1000L }

            val previous = repeats[msg]
            if (previous == null) {
                repeats[msg] = RepeatState(count = 1, lastSeenMs = now, tagId = nextTagId++)
                return@on
            }

            val updated = previous.copy(count = previous.count + 1, lastSeenMs = now)
            repeats[msg] = updated
            val original = component
            with(ChatManager) { hideMessage() }

            // Deferred to the next client tick: ChatPacketEvent fires from the network thread (see
            // ConnectionMixin), and touching the chat GUI's message lists off the main thread isn't safe.
            schedule(1) {
                val chatComp = mc.gui.chat as ChatComponentAccessor
                val scrollBefore = chatComp.chatScrollbarPos
                removeLines(updated.tagId, msg)
                mc.gui.chat.add(original.copy().append(Component.literal(" §7(${updated.count})")), updated.tagId)
                chatComp.chatScrollbarPos = scrollBefore
            }
        }
    }
}
