package com.safesphere.app.feature.account.session

import com.safesphere.app.feature.account.domain.PendingDeletionStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phase 10A.3: removes a DELETED account's data from this phone. A sign-out already forgets the session, the active
 * emergency / walk records, the community outbox and the Safety Circle copy (they are cleared on every sign-out);
 * what a sign-out deliberately keeps (so that evidence survives an accidental log-out) is removed here:
 * the account's Evidence Vault items with their keys and custody anchors, and its recovery key.
 *
 * Runs only when the server said the account is deleted (401 "account_deleted"), or when the person taps
 * "Remove its data from this phone" on the Login screen after the deletion date. Each part runs even if an earlier
 * one failed; the marker is cleared only when every part succeeded, so a failure can be retried.
 */
class AccountDataWiper(
    private val parts: List<suspend (userId: Long) -> Unit>,
    private val pendingDeletion: PendingDeletionStore
) {

    /** True when everything was removed. */
    suspend fun wipe(userId: Long): Boolean {
        var allDone = true
        for (part in parts) {
            try {
                part(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                allDone = false // nothing about the content is logged; the Login notice stays so it can be retried
            }
        }
        if (allDone) pendingDeletion.clear(userId)
        return allDone
    }
}
