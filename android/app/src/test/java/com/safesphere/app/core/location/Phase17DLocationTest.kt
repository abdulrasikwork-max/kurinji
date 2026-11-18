package com.safesphere.app.core.location

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17D: the one-action policy and a source scan that keeps the location code on the shared path. N6 (D22): the
 * best-fix search (`preciseFix`) and the walk's location stream are gone with the walk's tracking; location is read
 * ONLY in an SOS (and once by the shared helpers), never by "On my way".
 */
class Phase17DLocationTest {

    @Test
    fun `the one action follows the facts, and an upgrade that came back approximate goes to settings`() {
        fun need(access: LocationAccess, on: Boolean = true, precise: Boolean = false, denied: Boolean = false, declined: Boolean = false) =
            LocationNeeds.of(access, on, precise, denied, declined)
        assertEquals(LocationNeed.ALLOW, need(LocationAccess.NONE))
        assertEquals(LocationNeed.OPEN_APP_SETTINGS, need(LocationAccess.NONE, denied = true))
        assertEquals(LocationNeed.TURN_ON, need(LocationAccess.PRECISE, on = false))
        assertEquals(LocationNeed.NONE, need(LocationAccess.PRECISE))
        assertEquals("approximate is enough to look", LocationNeed.NONE, need(LocationAccess.APPROXIMATE))
        assertEquals(LocationNeed.UPGRADE_PRECISE, need(LocationAccess.APPROXIMATE, precise = true))
        assertEquals(LocationNeed.UPGRADE_PRECISE_IN_SETTINGS, need(LocationAccess.APPROXIMATE, precise = true, declined = true))
        assertEquals(LocationNeed.TURN_ON, need(LocationAccess.APPROXIMATE, on = false, precise = true))
        assertEquals(LocationNeed.ALLOW, need(LocationAccess.NONE, on = false, precise = true))
    }

    private val root = File("src/main/java/com/safesphere/app")
    private fun sources() = root.walkTopDown().filter { it.isFile && it.extension == "kt" }

    private fun File.rel() = relativeTo(root).path.replace('\\', '/')

    @Test
    fun `screens ask for location only through the shared LocationAsk, except the SOS screen`() {
        val offenders = sources()
            .filter { it.readText(Charsets.UTF_8).contains("RequestMultiplePermissions") }
            .map { it.rel() }
            .filterNot { it == "core/ui/LocationHelp.kt" || it == "feature/emergency/ui/EmergencyFragment.kt" }
            .toList()
        assertTrue("own location launchers in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `only the SOS holder and the provider read a location stream (N6 - On my way reads none)`() {
        val allowed = setOf(
            "core/location/FusedLocationProvider.kt", "core/location/LocationProvider.kt", "core/location/OneFix.kt",
            "feature/emergency/location/EmergencyLocationSharing.kt"
        )
        val offenders = sources()
            .filter { Regex("""\.(updates|freshUpdates)\(""").containsMatchIn(it.readText(Charsets.UTF_8)) }
            .map { it.rel() }
            .filterNot { it in allowed }
            .toList()
        assertTrue("direct location streams in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `On my way, plans and the social side never touch location, a geocoder or a map link`() {
        val forbidden = Regex("""LocationProvider|LocationFix|GeoPoint|Geocoder|GeoHash|maps\.google|google\.com/maps""")
        val offenders = sources()
            .filter { f -> listOf("feature/safewalk/", "feature/going/", "feature/moments/", "feature/replies/")
                .any { f.rel().startsWith(it) } }
            .filter { forbidden.containsMatchIn(it.readText(Charsets.UTF_8)) }
            .map { it.rel() }
            .toList()
        assertTrue("location in: $offenders", offenders.isEmpty())
        val geocoder = sources().filter { "android.location.Geocoder" in it.readText(Charsets.UTF_8) }.map { it.rel() }.toList()
        assertTrue("no Geocoder anywhere (it sent typed text to Google): $geocoder", geocoder.isEmpty())
    }
}
