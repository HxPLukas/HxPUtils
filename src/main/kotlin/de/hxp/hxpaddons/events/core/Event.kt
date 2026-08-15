package de.hxp.hxpaddons.events.core

import de.hxp.hxpaddons.utils.logError

interface Event {

    fun postAndCatch(): Boolean {
        runCatching {
            EventBus.post(this)
        }.onFailure {
            logError(it, this)
        }
        return false
    }
}