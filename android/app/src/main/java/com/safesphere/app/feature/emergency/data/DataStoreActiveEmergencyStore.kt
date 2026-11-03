package com.safesphere.app.feature.emergency.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyStore
import com.safesphere.app.feature.emergency.domain.EmergencyTrigger
import com.safesphere.app.feature.emergency.domain.LocalEnd
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.activeEmergencyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "active_emergency"
)

/**
 * App-private DataStore file (not backed up: allowBackup=false). Holds only ids, a random reference and times:
 * locations are never written to disk on the phone.
 */
class DataStoreActiveEmergencyStore(context: Context) : ActiveEmergencyStore {

    private val dataStore = context.applicationContext.activeEmergencyDataStore

    private suspend fun read(): Preferences = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .first()

    override suspend fun load(): ActiveEmergencyRecord? {
        val preferences = read()
        val userId = preferences[KEY_USER_ID] ?: return null
        val emergencyId = preferences[KEY_EMERGENCY_ID] ?: return null
        val startedAt = preferences[KEY_STARTED_AT_MILLIS] ?: return null
        return ActiveEmergencyRecord(
            userId = userId,
            emergencyId = emergencyId,
            startedAt = Instant.ofEpochMilli(startedAt),
            clientRef = preferences[KEY_CLIENT_REF],
            // Records from before Phase 11 had no flag: they always held a server id.
            onServer = preferences[KEY_ON_SERVER] ?: true,
            dialPromptOpened = preferences[KEY_DIAL_PROMPT] ?: false,
            // Records from before Phase 17F had no trigger: they were always MANUAL.
            trigger = preferences[KEY_TRIGGER]?.let(::triggerOf) ?: EmergencyTrigger.MANUAL
        )
    }

    override suspend fun save(record: ActiveEmergencyRecord) {
        dataStore.edit { preferences ->
            removeCurrent(preferences)
            preferences[KEY_USER_ID] = record.userId
            preferences[KEY_EMERGENCY_ID] = record.emergencyId
            preferences[KEY_STARTED_AT_MILLIS] = record.startedAt.toEpochMilli()
            record.clientRef?.let { preferences[KEY_CLIENT_REF] = it }
            preferences[KEY_ON_SERVER] = record.onServer
            preferences[KEY_DIAL_PROMPT] = record.dialPromptOpened
            preferences[KEY_TRIGGER] = record.trigger.name
        }
    }

    override suspend fun clear() {
        dataStore.edit { removeCurrent(it) }
    }

    override suspend fun loadUnsent(): List<ActiveEmergencyRecord> =
        read()[KEY_UNSENT].orEmpty().lineSequence().mapNotNull(::decode).toList()

    override suspend fun saveUnsent(records: List<ActiveEmergencyRecord>) {
        dataStore.edit { preferences ->
            if (records.isEmpty()) preferences.remove(KEY_UNSENT) else preferences[KEY_UNSENT] = records.joinToString("\n", transform = ::encode)
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private fun removeCurrent(preferences: MutablePreferences) {
        listOf(KEY_USER_ID, KEY_EMERGENCY_ID, KEY_STARTED_AT_MILLIS, KEY_CLIENT_REF, KEY_ON_SERVER, KEY_DIAL_PROMPT, KEY_TRIGGER)
            .forEach { preferences.remove(it) }
    }

    private companion object {
        val KEY_USER_ID = longPreferencesKey("user_id")
        val KEY_EMERGENCY_ID = longPreferencesKey("emergency_id")
        val KEY_STARTED_AT_MILLIS = longPreferencesKey("started_at_millis")
        val KEY_CLIENT_REF = stringPreferencesKey("client_ref")
        val KEY_ON_SERVER = booleanPreferencesKey("on_server")
        val KEY_DIAL_PROMPT = booleanPreferencesKey("dial_prompt_opened")
        val KEY_TRIGGER = stringPreferencesKey("trigger")
        val KEY_UNSENT = stringPreferencesKey("unsent_endings")

        fun encode(record: ActiveEmergencyRecord): String = UnsentEndingsCodec.encode(record)
        fun decode(line: String): ActiveEmergencyRecord? = UnsentEndingsCodec.decode(line)
    }
}

/** Phase 11: one ended SOS per line: user;id;startMillis;reference;onServer;endMillis;resolved;dial (numbers, a UUID, flags). */
internal object UnsentEndingsCodec {

    fun encode(record: ActiveEmergencyRecord): String = listOf(
        record.userId, record.emergencyId, record.startedAt.toEpochMilli(), record.clientRef.orEmpty(),
        record.onServer, record.ended?.at?.toEpochMilli() ?: "", record.ended?.resolved ?: "", record.dialPromptOpened,
        triggerCode(record.trigger)
    ).joinToString(";")

    fun decode(line: String): ActiveEmergencyRecord? {
        val parts = line.split(";")
        // Phase 17F added a ninth field (the trigger); lines written before it have eight and are MANUAL.
        if (parts.size != 8 && parts.size != 9) return null
        val endMillis = parts[5].toLongOrNull() ?: return null // only ENDED records are kept here
        return ActiveEmergencyRecord(
            userId = parts[0].toLongOrNull() ?: return null,
            emergencyId = parts[1].toLongOrNull() ?: return null,
            startedAt = Instant.ofEpochMilli(parts[2].toLongOrNull() ?: return null),
            clientRef = parts[3].ifEmpty { null },
            onServer = parts[4].toBooleanStrictOrNull() ?: return null,
            ended = LocalEnd(parts[6].toBooleanStrictOrNull() ?: true, Instant.ofEpochMilli(endMillis)),
            dialPromptOpened = parts[7].toBooleanStrictOrNull() ?: false,
            trigger = parts.getOrNull(8)?.let(::triggerFromCode) ?: EmergencyTrigger.MANUAL
        )
    }
}

/** A stored trigger name; anything unknown reads as MANUAL (it only labels the record, it never decides anything). */
private fun triggerOf(name: String): EmergencyTrigger = EmergencyTrigger.entries.firstOrNull { it.name == name } ?: EmergencyTrigger.MANUAL

/** Phase 17F: the unsent-endings line keeps to digits, so the trigger is a fixed one-digit code (never the enum's position). */
private fun triggerCode(trigger: EmergencyTrigger): String = when (trigger) {
    EmergencyTrigger.MANUAL -> "0"
    EmergencyTrigger.SHAKE -> "1"
    EmergencyTrigger.VOICE -> "2"
    EmergencyTrigger.DURESS_PIN -> "3"
}

private fun triggerFromCode(code: String): EmergencyTrigger = when (code) {
    "1" -> EmergencyTrigger.SHAKE
    "2" -> EmergencyTrigger.VOICE
    "3" -> EmergencyTrigger.DURESS_PIN
    else -> EmergencyTrigger.MANUAL
}
