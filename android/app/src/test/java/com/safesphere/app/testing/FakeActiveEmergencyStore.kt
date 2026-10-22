package com.safesphere.app.testing

import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyStore

class FakeActiveEmergencyStore(
    var record: ActiveEmergencyRecord? = null,
    var unsent: List<ActiveEmergencyRecord> = emptyList()
) : ActiveEmergencyStore {
    override suspend fun load(): ActiveEmergencyRecord? = record
    override suspend fun save(record: ActiveEmergencyRecord) {
        this.record = record
    }
    override suspend fun clear() {
        record = null
    }
    override suspend fun loadUnsent(): List<ActiveEmergencyRecord> = unsent
    override suspend fun saveUnsent(records: List<ActiveEmergencyRecord>) {
        unsent = records
    }
    override suspend fun clearAll() {
        record = null
        unsent = emptyList()
    }
}
