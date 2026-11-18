package com.safesphere.app.core.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * N7 (D33): permission in context, never at launch. A screen [offer]s at a moment where notifications obviously help
 * (after sharing a Who's in?, after saying I'm in, after a first private reply; A3: accepting a Circle request);
 * MainActivity shows the short card and only then Android's own question. "Not now" waits 30 days; denied, IRL works
 * as before (the Replies dot).
 */
class NotificationAsk(
    private val scope: CoroutineScope,
    private val preferences: PushPreferences,
    private val configured: () -> Boolean,
    private val runtimePermission: () -> Boolean,
    private val granted: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun offer() {
        scope.launch {
            if (shouldAsk(runtimePermission(), configured(), granted(), nowMs(), preferences.askAgainAfterMs())) {
                _requests.tryEmit(Unit)
            }
        }
    }

    fun notNow() {
        scope.launch { preferences.notNow(nowMs()) }
    }

    companion object {
        fun shouldAsk(runtimePermission: Boolean, configured: Boolean, granted: Boolean, nowMs: Long,
                      askAgainAfterMs: Long): Boolean =
            runtimePermission && configured && !granted && nowMs >= askAgainAfterMs
    }
}
