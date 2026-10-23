package com.safesphere.app.feature.social

import com.safesphere.app.feature.social.data.EncryptedPlacesStore
import com.safesphere.app.feature.social.data.PlaceMemory
import com.safesphere.app.feature.social.data.PlacesData
import com.safesphere.app.feature.social.data.PlacesLoad
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.PrivatePlace
import com.safesphere.app.feature.social.domain.PrivatePlaceKind
import com.safesphere.app.testing.FakeExperienceRepository
import com.safesphere.app.testing.FakeKeyWrapper
import com.safesphere.app.testing.FakePlacesStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 13B (Q9): private and recent places are encrypted per account, never readable by another, cleared at sign-out. */
class EncryptedPlacesStoreTest {

    private val dir: File = Files.createTempDirectory("places-test").toFile()
    private val wrapper = FakeKeyWrapper()
    private val store = EncryptedPlacesStore(dir, wrapper)

    private val data = PlacesData(
        privatePlaces = listOf(PrivatePlace("p1", PrivatePlaceKind.HOME, "tf2c1xz")),
        recent = listOf(FakeExperienceRepository.place()),
        lastAudience = Audience.CONNECTIONS
    )

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `round trip`() = runTest {
        assertEquals(PlacesLoad.Empty, store.load(7))
        store.save(7, data)
        assertEquals(PlacesLoad.Loaded(data), store.load(7))
    }

    @Test
    fun `the file is encrypted - no cell, name or kind in it`() = runTest {
        store.save(7, data)
        val raw = File(dir, "places-7.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("tf2c1xz"))
        assertFalse(raw.contains("Marina"))
        assertFalse(raw.contains("HOME"))
    }

    @Test
    fun `another account cannot read it, even renamed`() = runTest {
        store.save(7, data)
        File(dir, "places-7.bin").copyTo(File(dir, "places-8.bin"))
        assertEquals(PlacesLoad.Unreadable, store.load(8))
    }

    @Test
    fun `a damaged file or a lost key is unreadable, never silently empty`() = runTest {
        store.save(7, data)
        File(dir, "places-7.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(PlacesLoad.Unreadable, store.load(7))
        store.save(9, data)
        wrapper.available = false
        assertEquals(PlacesLoad.Unreadable, store.load(9))
    }

    @Test
    fun `clearAll removes every account's file`() = runTest {
        store.save(7, data)
        store.save(8, data)
        store.clearAll()
        assertEquals(PlacesLoad.Empty, store.load(7))
        assertEquals(PlacesLoad.Empty, store.load(8))
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("places-") })
    }

    @Test
    fun `memory - nothing without an account, recent places newest first and capped, unreadable never overwritten`() = runTest {
        var user: Long? = null
        val fake = FakePlacesStore()
        val memory = PlaceMemory(fake, { user }, Dispatchers.Unconfined)
        assertFalse(memory.addPrivate(PrivatePlaceKind.HOME, "tf2c1xz"))      // signed out: nothing is kept
        user = 7
        assertTrue(memory.addPrivate(PrivatePlaceKind.HOME, "tf2c1xz"))
        repeat(25) { memory.rememberUsed(FakeExperienceRepository.place("p$it")) }
        val snapshot = memory.snapshot()
        assertEquals(PlacesData.RECENT_MAX, snapshot.data.recent.size)
        assertEquals("p24", snapshot.data.recent.first().publicId)
        assertEquals(1, snapshot.data.privatePlaces.size)

        fake.load = PlacesLoad.Unreadable
        val saves = fake.saved.size
        assertFalse(memory.addPrivate(PrivatePlaceKind.WORK, "tf2c1xy"))
        assertEquals(saves, fake.saved.size)
        assertTrue(memory.snapshot().privatePlacesUnknown)
    }
}
