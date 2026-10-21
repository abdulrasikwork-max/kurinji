package com.safesphere.app.feature.safewalk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyRecord
import com.safesphere.app.feature.safewalk.domain.ActiveJourneyStore
import com.safesphere.app.feature.safewalk.domain.Concern
import com.safesphere.app.feature.safewalk.domain.ConcernKind
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.activeJourneyDataStore: DataStore<Preferences> by preferencesDataStore(name = "active_journey")

/** App-private, excluded from backup and device transfer. Ids and times only; never the destination or a location. */
class DataStoreActiveJourneyStore(context: Context) : ActiveJourneyStore {

    private val dataStore = context.applicationContext.activeJourneyDataStore

    override suspend fun load(): ActiveJourneyRecord? {
        val p = dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()
        return ActiveJourneyRecord(
            userId = p[USER_ID] ?: return null,
            journeyId = p[JOURNEY_ID] ?: return null,
            checkInIntervalMinutes = p[INTERVAL] ?: return null,
            startedAt = Instant.ofEpochMilli(p[STARTED_AT] ?: return null),
            localDueAt = Instant.ofEpochMilli(p[LOCAL_DUE_AT] ?: return null),
            pendingEnd = p[PENDING_END]?.let(JourneyStatus::fromApi),
            movementChecks = p[MOVEMENT_CHECKS] ?: true,
            concern = concernFrom(p[CONCERN_KIND], p[CONCERN_AT])
        )
    }

    private fun concernFrom(kind: String?, atMillis: Long?): Concern? {
        val concernKind = ConcernKind.entries.firstOrNull { it.name == kind } ?: return null
        return Concern(concernKind, Instant.ofEpochMilli(atMillis ?: return null))
    }

    override suspend fun save(record: ActiveJourneyRecord) {
        dataStore.edit { p ->
            p[USER_ID] = record.userId
            p[JOURNEY_ID] = record.journeyId
            p[INTERVAL] = record.checkInIntervalMinutes
            p[STARTED_AT] = record.startedAt.toEpochMilli()
            p[LOCAL_DUE_AT] = record.localDueAt.toEpochMilli()
            val pendingEnd = record.pendingEnd
            if (pendingEnd != null) p[PENDING_END] = pendingEnd.name else p.remove(PENDING_END)
            p[MOVEMENT_CHECKS] = record.movementChecks
            val concern = record.concern
            if (concern != null) {
                p[CONCERN_KIND] = concern.kind.name
                p[CONCERN_AT] = concern.raisedAt.toEpochMilli()
            } else {
                p.remove(CONCERN_KIND)
                p.remove(CONCERN_AT)
            }
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val USER_ID = longPreferencesKey("user_id")
        val JOURNEY_ID = longPreferencesKey("journey_id")
        val INTERVAL = intPreferencesKey("interval_minutes")
        val STARTED_AT = longPreferencesKey("started_at_millis")
        val LOCAL_DUE_AT = longPreferencesKey("local_due_at_millis")
        val PENDING_END = stringPreferencesKey("pending_end")
        val MOVEMENT_CHECKS = booleanPreferencesKey("movement_checks")
        val CONCERN_KIND = stringPreferencesKey("concern_kind")
        val CONCERN_AT = longPreferencesKey("concern_at_millis")
    }
}
