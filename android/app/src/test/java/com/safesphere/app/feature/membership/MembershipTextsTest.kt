package com.safesphere.app.feature.membership

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase N3 (A1): what the membership screens may say and do, read from the sources. Verification is never a safety
 * claim, a ban never touches safety, the camera is used without the CAMERA permission, the ID photo file lives only in
 * one cache folder, codes and roll numbers are screen-protected, and the social code never reads membership code.
 */
class MembershipTextsTest {

    private val strings: Map<String, String> by lazy {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] } +
            PLURAL.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val membershipStrings: Map<String, String>
        get() = strings.filterKeys { key -> PREFIXES.any { key.startsWith(it) } }

    @Test
    fun `the texts exist and never call a person or a place safe, verified safe, or trusted`() {
        assertTrue(membershipStrings.size > 60)
        for ((key, text) in membershipStrings) {
            val lower = text.lowercase()
            val withoutSafety = lower.replace("safe walk", "").replace("safety circle", "")
            assertFalse("$key: $text", Regex("\\bsafe(r|st)?\\b").containsMatchIn(withoutSafety))
            for (word in listOf("trusted member", "guarantee", "background check", "police", "aadhaar", "report")) {
                assertFalse("$key mentions $word", lower.contains(word))
            }
        }
    }

    @Test
    fun `a ban and the waiting time say that sign-in and the safety tools keep working`() {
        val unchanged = strings.getValue("membership_safety_unchanged")
        for (part in listOf("Sign-in", "SOS", "On my way", "Safety Circle")) assertTrue(part, unchanged.contains(part))
        assertTrue(strings.getValue("membership_intro").contains("SOS"))
        val screen = source("feature/membership/ui/MembershipFragment.kt")
        assertTrue(screen.contains("R.string.membership_banned") && screen.contains("R.string.membership_safety_unchanged"))
    }

    @Test
    fun `the person is told every refusal, end and ban with a reason, and the name correction`() {
        val labels = source("feature/membership/ui/MembershipLabels.kt")
        for (code in listOf("NAME_MISMATCH", "ROLL_MISMATCH", "NOT_THE_PERSON", "NOT_MIET_ID", "UNREADABLE",
                "ALREADY_MEMBER", "HARASSMENT", "HATE", "THREATS", "SEXUAL_CONTENT", "IMPERSONATION", "FALSE_ID",
                "PRIVACY", "REPEATED")) assertTrue(code, labels.contains("\"$code\""))
        assertTrue(strings.getValue("membership_name_corrected").contains("%1\$s"))
        assertTrue(strings.getValue("membership_refused").contains("%1\$s"))
    }

    @Test
    fun `the code and roll-number screens are screen-protected and the roll number is never kept`() {
        for (file in listOf("feature/membership/ui/MembershipFragment.kt", "feature/membership/ui/RelinkFragment.kt")) {
            assertTrue(file, source(file).contains("FLAG_SECURE"))
        }
        val viewModel = source("feature/membership/ui/MembershipViewModel.kt")
        val state = viewModel.substringAfter("data class MembershipUiState(").substringBefore(") {")
        assertFalse("no roll number field in the state", state.contains("roll:") || state.contains("rollNumber"))
        for (layout in listOf("fragment_membership.xml", "fragment_relink.xml")) {
            val xml = File("src/main/res/layout/$layout").readText(Charsets.UTF_8)
            assertTrue("$layout: no autofill of the roll number", xml.contains("android:importantForAutofill=\"no\""))
        }
    }

    @Test
    fun `the camera app is used without the CAMERA permission and only into its own cache folders`() {
        val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertFalse(manifest.contains("android.permission.CAMERA\""))
        assertTrue(manifest.contains("\${applicationId}.camera"))
        val provider = manifest.substringAfter("androidx.core.content.FileProvider").substringBefore("</provider>")
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("@xml/camera_paths"))
        // N4 (A2): exactly two cache folders: the ID photo (N3) and a moment's photo. Nothing else is shared.
        val paths = File("src/main/res/xml/camera_paths.xml").readText(Charsets.UTF_8)
        assertEquals("two cache folders only", 2, Regex("<[a-z-]+-path\\b").findAll(paths).count())
        assertEquals(2, Regex("<cache-path\\b").findAll(paths).count())
        assertTrue(paths.contains("path=\"id-photo/\"") && paths.contains("path=\"moment-photo/\""))
        assertFalse(File("src/main/res/xml/id_photo_paths.xml").exists())
        val files = source("feature/membership/data/IdPhotoFiles.kt")
        assertTrue(files.contains("TakePicture") && files.contains("fun sweep()"))
        val viewModel = source("feature/membership/ui/MembershipViewModel.kt")
        assertTrue("deleted sent or not", viewModel.contains("finally {\n                photos.discard(shot)"))
    }

    @Test
    fun `social and safety code never read membership code, and social offers Get verified by navigation only`() {
        val root = File("src/main/java/com/safesphere/app/feature")
        for (feature in listOf("social", "moments", "safewalk", "emergency", "safetycircle", "circlealert", "safety")) {
            File(root, feature).walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                assertFalse("${file.name} imports membership", file.readText(Charsets.UTF_8).contains("feature.membership"))
            }
        }
        // N4: Today (with its view model) replaced Home; Create joins this list with its screen.
        val today = source("feature/moments/ui/TodayFragment.kt") + source("feature/moments/ui/TodayViewModel.kt")
        assertTrue("Today", today.contains("needsMembership()") && today.contains("R.id.membershipFragment"))
        for (screen in listOf("ConnectionsFragment", "PersonProfileFragment")) {
            val code = source("feature/social/ui/$screen.kt")
            assertTrue(screen, code.contains("needsMembership()") && code.contains("R.id.membershipFragment"))
        }
    }

    @Test
    fun `staff screens are screen-protected, keep the ID photo in memory only, and say what a ban leaves untouched`() {
        for (screen in listOf("DecideFragment", "StaffListFragment", "StaffActionsFragment")) {
            assertTrue(screen, source("feature/membership/ui/staff/$screen.kt").contains("FLAG_SECURE"))
        }
        val decide = source("feature/membership/ui/staff/DecideFragment.kt") +
            source("feature/membership/ui/staff/DecisionViewModel.kt") + source("feature/membership/data/RemoteStaffRepository.kt")
        for (writer in listOf("FileOutputStream", "openFileOutput", "cacheDir", "filesDir", "imageLoader", "MediaStore", "DataStore")) {
            assertFalse("the ID photo is never written or cached: $writer", decide.contains(writer))
        }
        assertTrue(decide.contains("decodeByteArray"))
        val bans = strings.getValue("staff_bans_intro")
        for (part in listOf("Sign-in", "SOS", "On my way", "Safety Circle", "sees the reason")) assertTrue(part, bans.contains(part))
        assertTrue(strings.getValue("staff_verifiers_intro").contains("not listed anywhere else"))
        for ((key, text) in strings.filterKeys { it.startsWith("staff_") }) {
            val rest = text.lowercase().replace("safe walk", "").replace("safety circle", "")
            assertFalse("$key: $text", Regex("\\bsafe(r|st)?\\b").containsMatchIn(rest))
        }
        // The roll number is typed by the person deciding: no answer the app reads carries one.
        val api = source("feature/membership/data/remote/StaffApi.kt")
        val answers = Regex("data class (\\w+Dto)\\(([^)]*)\\)").findAll(api)
            .filter { it.groupValues[1] in setOf("RequestDetailDto", "VerifierDto", "BanDto", "LogDto", "DecisionResultDto") }
        assertEquals(5, answers.count())
        assertTrue(answers.none { it.groupValues[2].contains("roll", ignoreCase = true) && !it.groupValues[2].contains("onRollNumber") })
    }

    private fun source(path: String) = File("src/main/java/com/safesphere/app/$path").readText(Charsets.UTF_8)

    private companion object {
        val PREFIXES = listOf("membership_", "you_membership_", "relink_", "register_moving", "title_membership",
            "action_get_verified")
        val STRING = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val PLURAL = Regex("<plurals name=\"([a-z0-9_]+)\"[^>]*>(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
    }
}
