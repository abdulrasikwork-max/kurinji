package com.safesphere.app.feature.circlealert.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.safesphere.app.feature.circlealert.domain.AlertChannel
import com.safesphere.app.feature.circlealert.domain.AlertMemory
import com.safesphere.app.feature.circlealert.domain.AlertTarget
import com.safesphere.app.feature.circlealert.domain.CircleAlertSnapshot
import com.safesphere.app.feature.circlealert.domain.CircleAlertStore
import com.safesphere.app.feature.circlealert.domain.CircleDelivery
import com.safesphere.app.feature.circlealert.domain.CircleMessageKind
import com.safesphere.app.feature.circlealert.domain.CircleRecipient
import com.safesphere.app.feature.circlealert.domain.DeliveryStatus
import com.safesphere.app.feature.circlealert.domain.EmergencyAlertMemory
import com.safesphere.app.feature.circlealert.domain.WalkAlertMemory
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.circleAlertDataStore: DataStore<Preferences> by preferencesDataStore(name = "circle_alerts")

/**
 * App-private, excluded from backup and device transfer (data_extraction_rules). One JSON value with plain types
 * (times as epoch millis), so the stored format never depends on class internals. Unreadable data is treated as
 * "nothing stored" rather than crashing an emergency screen.
 */
class DataStoreCircleAlertStore(context: Context) : CircleAlertStore {

    private val dataStore = context.applicationContext.circleAlertDataStore
    private val gson = Gson()

    override suspend fun load(): CircleAlertSnapshot? {
        val json = dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()[SNAPSHOT] ?: return null
        return try {
            gson.fromJson(json, Stored::class.java)?.toDomain()
        } catch (e: JsonParseException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    override suspend fun save(snapshot: CircleAlertSnapshot) {
        val json = gson.toJson(Stored.from(snapshot))
        dataStore.edit { it[SNAPSHOT] = json }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    // ---- stored shape --------------------------------------------------------------------------

    private class Stored(
        val userId: Long = 0,
        val autoSms: Boolean = false,
        val recipients: List<StoredRecipient>? = null,
        val walks: Map<String, StoredWalk>? = null,
        val emergencies: Map<String, StoredEmergency>? = null,
        val deliveries: List<StoredDelivery>? = null,
        val pending: List<StoredDelivery>? = null
    ) {
        fun toDomain() = CircleAlertSnapshot(
            userId = userId,
            autoSms = autoSms,
            recipients = recipients.orEmpty().map { CircleRecipient(it.contactId, it.name.orEmpty(), it.phone.orEmpty()) },
            memory = AlertMemory(
                walks = walks.orEmpty().mapKeys { it.key.toLong() }.mapValues {
                    WalkAlertMemory(it.value.alertsSent, it.value.awaitingAllClear, Instant.ofEpochMilli(it.value.updatedAt))
                },
                emergencies = emergencies.orEmpty().mapKeys { it.key.toLong() }.mapValues {
                    EmergencyAlertMemory(
                        Instant.ofEpochMilli(it.value.startedAt), it.value.startSent, it.value.skipped,
                        it.value.locationFollowUpPending, it.value.endSent, Instant.ofEpochMilli(it.value.updatedAt)
                    )
                }
            ),
            deliveries = deliveries.orEmpty().mapNotNull { it.toDomain() },
            pendingReports = pending.orEmpty().mapNotNull { it.toDomain() }
        )

        companion object {
            fun from(s: CircleAlertSnapshot) = Stored(
                userId = s.userId,
                autoSms = s.autoSms,
                recipients = s.recipients.map { StoredRecipient(it.contactId, it.name, it.phoneNumber) },
                walks = s.memory.walks.map { (id, w) ->
                    id.toString() to StoredWalk(w.alertsSent, w.awaitingAllClear, w.updatedAt.toEpochMilli())
                }.toMap(),
                emergencies = s.memory.emergencies.map { (id, e) ->
                    id.toString() to StoredEmergency(
                        e.startedAt.toEpochMilli(), e.startSent, e.skipped, e.locationFollowUpPending, e.endSent,
                        e.updatedAt.toEpochMilli()
                    )
                }.toMap(),
                deliveries = s.deliveries.map(StoredDelivery::from),
                pending = s.pendingReports.map(StoredDelivery::from)
            )
        }
    }

    private class StoredRecipient(val contactId: Long = 0, val name: String? = null, val phone: String? = null)

    private class StoredWalk(val alertsSent: Int = 0, val awaitingAllClear: Boolean = false, val updatedAt: Long = 0)

    private class StoredEmergency(
        val startedAt: Long = 0,
        val startSent: Boolean = false,
        val skipped: Boolean = false,
        val locationFollowUpPending: Boolean = false,
        val endSent: Boolean = false,
        val updatedAt: Long = 0
    )

    private class StoredDelivery(
        val clientRef: String? = null,
        val targetType: String? = null,
        val targetId: Long = 0,
        val kind: String? = null,
        val contactId: Long? = null,
        val contactName: String? = null,
        val channel: String? = null,
        val status: String? = null,
        val at: Long = 0
    ) {
        fun toDomain(): CircleDelivery? {
            val target = when (targetType) {
                "WALK" -> AlertTarget.Walk(targetId)
                "EMERGENCY" -> AlertTarget.Emergency(targetId)
                else -> return null
            }
            return CircleDelivery(
                clientRef = clientRef ?: return null,
                target = target,
                kind = CircleMessageKind.entries.firstOrNull { it.name == kind } ?: return null,
                contactId = contactId,
                contactName = contactName,
                channel = AlertChannel.entries.firstOrNull { it.name == channel } ?: return null,
                status = DeliveryStatus.entries.firstOrNull { it.name == status } ?: return null,
                at = Instant.ofEpochMilli(at)
            )
        }

        companion object {
            fun from(d: CircleDelivery) = StoredDelivery(
                clientRef = d.clientRef,
                targetType = if (d.target is AlertTarget.Walk) "WALK" else "EMERGENCY",
                targetId = d.target.id,
                kind = d.kind.name,
                contactId = d.contactId,
                contactName = d.contactName,
                channel = d.channel.name,
                status = d.status.name,
                at = d.at.toEpochMilli()
            )
        }
    }

    private companion object {
        val SNAPSHOT = stringPreferencesKey("snapshot_json")
    }
}
