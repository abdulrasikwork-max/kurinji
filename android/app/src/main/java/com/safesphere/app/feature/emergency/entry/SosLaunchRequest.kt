package com.safesphere.app.feature.emergency.entry

/**
 * Phase 13F (docs/PHASE13F_PLAN.md F11, F3): "start the SOS countdown" from the launcher shortcut or the Quick Settings
 * tile. Both open the NON-exported [SosEntryActivity], which records a request here, in this process's memory only;
 * MainActivity starts the countdown only when it [consume]s one. Another app can open the exported launcher activity,
 * but it cannot write this memory, so it cannot start an SOS countdown. A request is used once and expires after
 * [VALID_MS] (for example while the person signs in first, then it is simply ignored).
 *
 * Times are `SystemClock.elapsedRealtime()` values passed in, so the rule is testable without Android.
 */
class SosLaunchRequest {

    private var requestedAt: Long? = null

    @Synchronized
    fun request(nowMs: Long) {
        requestedAt = nowMs
    }

    /** True once for a request made at most [VALID_MS] ago; every other call (and every later call) is false. */
    @Synchronized
    fun consume(nowMs: Long): Boolean {
        val at = requestedAt ?: return false
        requestedAt = null
        return nowMs - at in 0..VALID_MS
    }

    companion object {
        const val VALID_MS = 15_000L
    }
}
