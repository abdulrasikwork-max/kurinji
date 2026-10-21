package com.safesphere.app.testing

import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.SessionStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSessionStorage(initial: AuthSession? = null) : SessionStorage {

    private val current = MutableStateFlow(initial)
    var saveCalls = 0
        private set
    var clearCalls = 0
        private set

    override val session: Flow<AuthSession?> = current

    val savedSession: AuthSession? get() = current.value

    override suspend fun save(session: AuthSession) {
        saveCalls++
        current.value = session
    }

    override suspend fun clear() {
        clearCalls++
        current.value = null
    }
}
