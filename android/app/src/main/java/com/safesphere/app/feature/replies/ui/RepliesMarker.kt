package com.safesphere.app.feature.replies.ui

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.replies.domain.RepliesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Phase N5 (D19): the dot on the Replies tab, never a number. It is read when IRL comes to the front, on tab changes
 * and every [POLL_MS] while IRL is on the screen (push arrives in N7). Same never lights it (the server decides).
 * One call at a time; a check sooner than [MIN_GAP_MS] after the last one is skipped unless forced. A failure leaves the
 * dot as it was, except a refusal (no membership, signed out), which clears it. Nothing is stored.
 */
class RepliesMarker(
    private val repository: RepliesRepository,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val _fresh = MutableStateFlow(false)
    val fresh: StateFlow<Boolean> = _fresh.asStateFlow()

    private val running = Mutex()
    private var lastMs: Long? = null

    suspend fun check(force: Boolean = false) {
        val now = nowMs()
        val last = lastMs
        if (!force && last != null && now - last < MIN_GAP_MS) return
        if (!running.tryLock()) return
        try {
            lastMs = now
            when (val result = repository.marker()) {
                is ApiResult.Success -> _fresh.value = result.data
                is ApiResult.Failure -> if (result.error.refuses) _fresh.value = false
            }
        } finally {
            running.unlock()
        }
    }

    /** The person opened Replies: the dot goes at once (the server was told in the same step). */
    fun opened() {
        _fresh.value = false
    }

    private val com.safesphere.app.core.network.ApiError.refuses: Boolean
        get() = this == com.safesphere.app.core.network.ApiError.UNAUTHORIZED ||
            this == com.safesphere.app.core.network.ApiError.FORBIDDEN

    companion object {
        const val POLL_MS = 120_000L
        const val MIN_GAP_MS = 15_000L
    }
}
