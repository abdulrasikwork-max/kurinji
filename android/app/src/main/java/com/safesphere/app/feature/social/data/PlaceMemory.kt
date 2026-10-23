package com.safesphere.app.feature.social.data

import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.PrivatePlace
import com.safesphere.app.feature.social.domain.PrivatePlaceKind
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the screens get: the data, and whether the private places could be read at all. */
data class PlaceMemorySnapshot(val data: PlacesData, val unreadable: Boolean) {
    /**
     * Fail closed: when the private places cannot be read, the app cannot tell what is near them, so every place is
     * treated as private (City or No place only) until the person resets the list.
     */
    val privatePlacesUnknown: Boolean get() = unreadable
}

/**
 * Phase 13B (Q9): the signed-in person's private places, recently used places and last audience, through the encrypted
 * [PlacesStore]. Nothing here is ever uploaded. Signed out = nothing to read (null account). One writer at a time.
 */
class PlaceMemory(
    private val store: PlacesStore,
    private val currentUserId: () -> Long?,
    private val io: CoroutineDispatcher
) {
    private val lock = Mutex()

    suspend fun snapshot(): PlaceMemorySnapshot = withContext(io) {
        val userId = currentUserId() ?: return@withContext PlaceMemorySnapshot(PlacesData(), false)
        when (val load = store.load(userId)) {
            PlacesLoad.Empty -> PlaceMemorySnapshot(PlacesData(), false)
            is PlacesLoad.Loaded -> PlaceMemorySnapshot(load.data, false)
            PlacesLoad.Unreadable -> PlaceMemorySnapshot(PlacesData(), true)
        }
    }

    suspend fun addPrivate(kind: PrivatePlaceKind, cell: String) = edit { it.copy(privatePlaces = it.privatePlaces + PrivatePlace(UUID.randomUUID().toString(), kind, cell)) }

    suspend fun removePrivate(id: String) = edit { data -> data.copy(privatePlaces = data.privatePlaces.filterNot { it.id == id }) }

    suspend fun rememberUsed(place: Place) = edit { data ->
        data.copy(recent = (listOf(place) + data.recent.filterNot { it.publicId == place.publicId }).take(PlacesData.RECENT_MAX))
    }

    suspend fun rememberAudience(audience: Audience) = edit { it.copy(lastAudience = audience) }

    /** Starts again from an empty list (the way out of an unreadable file). */
    suspend fun reset() = lock.withLock {
        withContext(io) { currentUserId()?.let { store.save(it, PlacesData()) } }
    }

    suspend fun clearAll() = lock.withLock { withContext(io) { store.clearAll() } }

    /** Never overwrites an unreadable file (that would silently drop the person's private places). */
    private suspend fun edit(change: (PlacesData) -> PlacesData): Boolean = lock.withLock {
        withContext(io) {
            val userId = currentUserId() ?: return@withContext false
            val current = when (val load = store.load(userId)) {
                PlacesLoad.Empty -> PlacesData()
                is PlacesLoad.Loaded -> load.data
                PlacesLoad.Unreadable -> return@withContext false
            }
            store.save(userId, change(current))
            true
        }
    }
}
