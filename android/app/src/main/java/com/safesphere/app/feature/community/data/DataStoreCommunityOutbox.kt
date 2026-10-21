package com.safesphere.app.feature.community.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.safesphere.app.feature.community.domain.CommunityOutbox
import com.safesphere.app.feature.community.domain.PendingReport
import com.safesphere.app.feature.community.domain.SignalCategory
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.communityOutboxDataStore: DataStore<Preferences> by preferencesDataStore(name = "community_outbox")

/**
 * Reports waiting for a connection. Holds the category and the ~150 m cell only (never a coordinate), at most 10,
 * each for at most 24 h. App-private, excluded from backup (data_extraction_rules), cleared on logout.
 */
class DataStoreCommunityOutbox(context: Context) : CommunityOutbox {

    private val dataStore = context.applicationContext.communityOutboxDataStore
    private val gson = Gson()

    private data class Stored(val reports: List<StoredReport>?)
    private data class StoredReport(val category: String?, val cell: String?, val createdAtMillis: Long?)

    override suspend fun load(): List<PendingReport> {
        val json = dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()[KEY] ?: return emptyList()
        return try {
            gson.fromJson(json, Stored::class.java)?.reports.orEmpty().mapNotNull { stored ->
                PendingReport(
                    category = SignalCategory.fromName(stored.category) ?: return@mapNotNull null,
                    blockCell = stored.cell ?: return@mapNotNull null,
                    createdAtMillis = stored.createdAtMillis ?: return@mapNotNull null
                )
            }
        } catch (e: JsonParseException) {
            emptyList()
        } catch (e: RuntimeException) {
            emptyList()
        }
    }

    override suspend fun save(reports: List<PendingReport>) {
        if (reports.isEmpty()) {
            clear()
            return
        }
        val json = gson.toJson(Stored(reports.map { StoredReport(it.category.name, it.blockCell, it.createdAtMillis) }))
        dataStore.edit { it[KEY] = json }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val KEY = stringPreferencesKey("pending_reports")
    }
}
