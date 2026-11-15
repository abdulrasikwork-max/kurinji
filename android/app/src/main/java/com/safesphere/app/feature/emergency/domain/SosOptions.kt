package com.safesphere.app.feature.emergency.domain

import kotlinx.coroutines.flow.Flow

/** What this person chose for SOS (per account, on this phone only; never sent to the server). Defaults: all off. */
data class SosOptions(
    /** Shake gesture (D-4). Off until the person switches it on. */
    val shakeOn: Boolean = false
)

/**
 * Per-account SOS options on the phone. A different account on the same phone never sees these. (The choice about audio
 * in an SOS left with the Evidence Vault in N1; [forget] still removes what an older build stored.)
 */
interface SosOptionsStore {
    fun options(userId: Long): Flow<SosOptions>
    suspend fun setShake(userId: Long, on: Boolean)

    /** The account was deleted: forget its choices. A normal sign-out keeps them (they are preferences, not data). */
    suspend fun forget(userId: Long)
}
