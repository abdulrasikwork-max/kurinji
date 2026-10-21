package com.safesphere.app.testing

import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyStore

class FakeActiveEmergencyStore(var record: ActiveEmergencyRecord? = null) : ActiveEmergencyStore {
    override suspend fun load(): ActiveEmergencyRecord? = record
    override suspend fun save(record: ActiveEmergencyRecord) {
        this.record = record
    }
    override suspend fun clear() {
        record = null
    }
}
