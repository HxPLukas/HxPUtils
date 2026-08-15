package de.hxp.hxpaddons.utils.ui.widget

import de.hxp.hxpaddons.events.ScreenEvent
import de.hxp.hxpaddons.events.core.EventPriority
import de.hxp.hxpaddons.events.core.on

object CustomGUIImpl {
    typealias EventHandler<E> = E.() -> Any?

    data class HandlerSet(
        val enabled: () -> Boolean = { true },
        var render: EventHandler<ScreenEvent.Render>? = null,
        var click: EventHandler<ScreenEvent.MouseClick>? = null,
        var release: EventHandler<ScreenEvent.MouseRelease>? = null,
        var key: EventHandler<ScreenEvent.KeyPress>? = null,
    )

    private val handlers = mutableListOf<HandlerSet>()

    init {
        on<ScreenEvent.Render> (EventPriority.HIGHEST) {
            if (runHandlers(this) { it.render }) cancel()
        }

        on<ScreenEvent.MouseClick> (EventPriority.HIGHEST)  {
            if (runHandlers(this) { it.click }) cancel()
        }

        on<ScreenEvent.MouseRelease> (EventPriority.HIGHEST)  {
            if (runHandlers(this) { it.release }) cancel()
        }

        on<ScreenEvent.KeyPress> (EventPriority.HIGHEST) {
            if (runHandlers(this) { it.key }) cancel()
        }
    }

    fun register(set: HandlerSet) {
        handlers += set
    }

    private fun <E> runHandlers(event: E, selector: (HandlerSet) -> EventHandler<E>?): Boolean {
        var triggered = false
        handlers.toList().forEach { set ->
            if (!set.enabled()) return@forEach
            val handler = selector(set) ?: return@forEach
            if (handler(event) != false) triggered = true
        }
        return triggered
    }
}