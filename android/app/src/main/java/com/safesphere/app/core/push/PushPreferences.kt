package com.safesphere.app.core.push

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.pushDataStore: DataStore<Preferences> by preferencesDataStore(name = "push_preferences")

/**
 * N7 (D31, D33): the two choices kept on the phone ("Show what people wrote", off; "Quiet at night", on) and when the
 * notification card may ask again after "Not now". About this phone's owner only; nothing about other people (Q3).
 */
class PushPreferences(private val context: Context) {

    val showContent: Flow<Boolean> = context.pushDataStore.data.map { it[SHOW_CONTENT] ?: false }
    val quietAtNight: Flow<Boolean> = context.pushDataStore.data.map { it[QUIET] ?: true }

    suspend fun setShowContent(value: Boolean) {
        context.pushDataStore.edit { it[SHOW_CONTENT] = value }
    }

    suspend fun setQuietAtNight(value: Boolean) {
        context.pushDataStore.edit { it[QUIET] = value }
    }

    suspend fun askAgainAfterMs(): Long = context.pushDataStore.data.first()[ASK_AFTER] ?: 0L

    suspend fun notNow(nowMs: Long) {
        context.pushDataStore.edit { it[ASK_AFTER] = nowMs + PushRules.NOT_NOW_DAYS * 24 * 60 * 60 * 1000 }
    }

    private companion object {
        val SHOW_CONTENT = booleanPreferencesKey("show_content")
        val QUIET = booleanPreferencesKey("quiet_at_night")
        val ASK_AFTER = longPreferencesKey("ask_again_after_ms")
    }
}
