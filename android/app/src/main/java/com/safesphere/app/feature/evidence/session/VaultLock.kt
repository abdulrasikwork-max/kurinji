package com.safesphere.app.feature.evidence.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The vault's VIEWING lock (Phase 5 hardening). Capturing and uploading never need it: evidence must be sealed while
 * the phone is locked in a pocket. Opening, checking, removing or deleting evidence on the vault screen needs the
 * phone's own screen lock (PIN, pattern, password or a strong biometric) first.
 *
 * Unlocked for ONE person ([unlockedFor]) until the vault screen is left (home button, screen off, another screen) or
 * the account changes: someone picking up an unlocked phone later cannot open the vault without the screen lock.
 *
 * Honest limit: this is an access gate in the app, not encryption bound to the screen lock. The Keystore key is not
 * auth-bound on purpose (capture while locked; and removing the screen lock would otherwise destroy every key).
 */
class VaultLock(scope: CoroutineScope, userIds: Flow<Long?>) {

    private val _unlockedFor = MutableStateFlow<Long?>(null)
    val unlockedFor: StateFlow<Long?> = _unlockedFor.asStateFlow()

    /** True when the phone has no screen lock, so the gate could not be applied (the screen says so). */
    private val _unprotected = MutableStateFlow(false)
    val unprotected: StateFlow<Boolean> = _unprotected.asStateFlow()

    init {
        // Any change of account (sign out, another person signs in) locks the vault again.
        scope.launch { userIds.distinctUntilChanged().collect { lock() } }
    }

    fun isUnlocked(userId: Long?): Boolean = userId != null && _unlockedFor.value == userId

    /** The person confirmed the phone's screen lock (or the phone has none: [withoutScreenLock]). */
    fun unlock(userId: Long, withoutScreenLock: Boolean = false) {
        _unprotected.value = withoutScreenLock
        _unlockedFor.value = userId
    }

    fun lock() {
        _unlockedFor.value = null
        _unprotected.value = false
    }
}
