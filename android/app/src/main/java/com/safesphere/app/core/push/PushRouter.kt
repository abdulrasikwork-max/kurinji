package com.safesphere.app.core.push

import java.time.LocalTime
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Where the router posts (the Android notification manager in the app, a fake in tests). */
interface PushTray {
    fun show(kind: PushKind, notice: PushNotice, silent: Boolean)
    fun showGeneric(kind: PushKind, silent: Boolean)
    fun cancel(thread: String)
    fun cancelAll()
}

/**
 * N7 (D26, D30, D34): one incoming pointer → at most one notification. Unknown or repeated pointers do nothing (the same
 * event can reach a phone twice); social kinds while IRL is on the screen only tell the app ([events]: the Replies dot
 * and the visible screen refresh); otherwise the kind's renderer reads IRL (at most [PushRules.RENDER_TIMEOUT_MS]) and
 * the tray shows its notice, nothing (gone / held / blocked) or the generic line (IRL could not be reached).
 * core/push knows no feature: renderers are registered by kind from the container.
 */
class PushRouter(
    private val renderers: Map<PushKind, PushRenderer>,
    private val tray: PushTray,
    private val appVisible: () -> Boolean,
    private val quietAtNight: suspend () -> Boolean,
    private val now: () -> LocalTime = LocalTime::now,
    private val timeoutMs: Long = PushRules.RENDER_TIMEOUT_MS
) {
    private val seen = ArrayDeque<String>()
    private val _events = MutableSharedFlow<PushPayload>(extraBufferCapacity = 16)

    /** Every pointer that arrived (also the ones not shown): the Replies dot and open screens refresh on it. */
    val events: SharedFlow<PushPayload> = _events.asSharedFlow()

    suspend fun handle(data: Map<String, String>) {
        val payload = PushPayload.parse(data) ?: return
        if (!firstTime(payload.eventId)) return
        _events.tryEmit(payload)
        if (!PushRules.showInTray(payload.kind, appVisible())) return
        val silent = PushRules.silentNow(payload.kind, quietAtNight(), now())
        val renderer = renderers[payload.kind]
        val rendered = if (renderer == null) Rendered.Generic
        else withTimeoutOrNull(timeoutMs) { runCatching { renderer.render(payload) }.getOrDefault(Rendered.Generic) }
            ?: Rendered.Generic
        when (rendered) {
            is Rendered.Show -> tray.show(payload.kind, rendered.notice, silent)
            Rendered.Generic -> tray.showGeneric(payload.kind, silent)
            Rendered.Nothing -> Unit
        }
    }

    @Synchronized
    private fun firstTime(eventId: String): Boolean {
        if (eventId in seen) return false
        seen.addLast(eventId)
        while (seen.size > MAX_REMEMBERED) seen.removeFirst()
        return true
    }

    private companion object {
        const val MAX_REMEMBERED = 64
    }
}
