package de.hxp.hxpaddons.utils.network

import de.hxp.hxpaddons.HxPMod.scope
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.utils.noControlCodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mirrors devMessage() output to a Discord webhook so it survives outside the in-game chat overlay
 * (readable/copyable later, unlike the ephemeral client-side chat message devMessage otherwise only
 * shows). Messages are batched into one request every [FLUSH_INTERVAL_MS] instead of firing a request
 * per call, since devMessage can be called many times a second and Discord webhooks are rate-limited.
 */
object DiscordLogger {
    private const val FLUSH_INTERVAL_MS = 2000L
    private const val MAX_CONTENT_LENGTH = 1900

    private val pending = mutableListOf<String>()
    private val mutex = Mutex()
    private var flushScheduled = false

    fun log(message: String) {
        val url = ClickGUIModule.debugWebhookUrl
        if (url.isBlank()) return
        val clean = message.noControlCodes
        if (clean.isBlank()) return

        scope.launch {
            var shouldFlush = false
            mutex.withLock {
                pending.add(clean)
                if (!flushScheduled) {
                    flushScheduled = true
                    shouldFlush = true
                }
            }
            if (!shouldFlush) return@launch

            delay(FLUSH_INTERVAL_MS)
            val batch = mutex.withLock {
                val copy = pending.toList()
                pending.clear()
                flushScheduled = false
                copy
            }
            if (batch.isEmpty()) return@launch

            val content = batch.joinToString("\n").take(MAX_CONTENT_LENGTH)
            val json = WebUtils.gson.toJson(mapOf("content" to content))
            WebUtils.postData(url, json)
        }
    }
}
