package com.safesphere.app.core.push

/**
 * N7 (D34): "open this screen" from a tapped notification. Written only by the non-exported
 * [NotificationRouterActivity] (in this process's memory), read once by MainActivity on a signed-in screen. A request
 * older than [VALID_MS] (for example after signing in first) is dropped. Times are elapsed-realtime values passed in.
 */
class PushOpenRequest {

    private var target: PushTarget? = null
    private var at: Long = 0

    @Synchronized
    fun request(target: PushTarget, nowMs: Long) {
        this.target = target
        at = nowMs
    }

    @Synchronized
    fun consume(nowMs: Long): PushTarget? {
        val t = target ?: return null
        target = null
        return if (nowMs - at in 0..VALID_MS) t else null
    }

    companion object {
        const val VALID_MS = 60_000L
    }
}
