package com.safesphere.app.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 15 (docs/PHASE15.md §5): regressions for the integration defects found in the emulator walk-through. */
class Phase15IntegrationFixesTest {

    @Test
    fun spokenParts_loseTheirOwnFullStop_soTheSentenceHasExactlyOne() {
        assertEquals("Only you see this list", SpokenText.part("Only you see this list."))
        assertEquals("lighthouse end", SpokenText.part("lighthouse end. "))
        assertEquals("Wait", SpokenText.part("Wait..."))
        assertEquals("Connections", SpokenText.part("Connections"))
        assertEquals("", SpokenText.part(null))
        val sentence = "%1\$s. %2\$s.".format(SpokenText.part("Connections"), SpokenText.part("Only you see this list."))
        assertEquals("Connections. Only you see this list.", sentence)
    }

    /** 15-1: a TabLayout background fills the whole tab, so a bare 8 dp oval became a tall ellipse. */
    @Test
    fun onboardingDots_stayDots_insideTheTab() {
        for (name in listOf("onboarding_dot_active", "onboarding_dot_inactive")) {
            val xml = File("src/main/res/drawable/$name.xml").readText(Charsets.UTF_8)
            assertTrue("$name must be a layer-list", xml.contains("<layer-list"))
            assertTrue("$name must centre the dot", xml.contains("android:gravity=\"center\""))
            assertTrue("$name must keep the dot size", xml.contains("android:width=\"@dimen/onboarding_dot_size\"") &&
                xml.contains("android:height=\"@dimen/onboarding_dot_size\""))
        }
    }

    /** 15-2 and 15-3: wiring checks for the post screen (UI code without a ViewModel rule to test). */
    @Test
    fun postScreen_bringsNewFailuresIntoView_andCocoonInvitationOpensBeenHere() {
        val post = File("src/main/java/com/safesphere/app/feature/social/ui/PostFragment.kt").readText(Charsets.UTF_8)
        assertTrue(post.contains("binding.root.smoothScrollTo(0, binding.errorNotice.top)"))
        assertTrue(post.contains("binding.errorNotice.announceForAccessibility(message)"))
        assertTrue(post.contains("PostViewModel.ARG_WRITE_BEEN_HERE"))
        val cocoon = File("src/main/java/com/safesphere/app/feature/social/ui/CocoonFragment.kt").readText(Charsets.UTF_8)
        assertTrue(cocoon.contains("openPost(item.post.publicId, writeBeenHere = true)"))
    }

    /**
     * 15-5: the banner row's visibility was only recomputed when the banner STATE changed, so a deletion asked for (or a
     * Safe Walk started) on a screen without banners stayed hidden on every tab until the next change or restart.
     */
    @Test
    fun sessionBanners_areReappliedOnEveryScreenChange() {
        val activity = File("src/main/java/com/safesphere/app/MainActivity.kt").readText(Charsets.UTF_8)
        val chrome = activity.substringAfter("private fun updateChrome(").substringBefore("\n    }\n")
        assertTrue(chrome.contains("binding.banners.sessionBanners.isVisible = bannersAllowed && anyBannerDue"))
        assertTrue(activity.contains("anyBannerDue = active != null || deletion != null || signIn"))
    }

    /** 15-6: the deletion explanation named only safety data, not the experiences, photos and people the person has. */
    @Test
    fun deletionExplainer_namesTheSocialContentToo() {
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val text = Regex("<string name=\"account_delete_explainer\">([^<]*)</string>").find(strings)!!.groupValues[1]
        for (word in listOf("profile", "experiences", "photos", "perspectives", "connections", "Cocoon", "Evidence Vault")) {
            assertTrue("deletion text should name $word", text.contains(word))
        }
    }

    /** 15-4: a report row said "Shared this week", the words for posts. */
    @Test
    fun yourReports_sayWhenTheReportWasSent_notShared() {
        val screen = File("src/main/java/com/safesphere/app/feature/social/ui/MyReportsFragment.kt").readText(Charsets.UTF_8)
        assertTrue(screen.contains("TimeBandTexts.sent(report.period)"))
        assertTrue(!screen.contains("TimeBandTexts.of("))
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        for (name in listOf("my_reports_sent_this_week", "my_reports_sent_this_month", "my_reports_sent_earlier")) {
            val value = Regex("<string name=\"$name\">([^<]*)</string>").find(strings)?.groupValues?.get(1)
            assertTrue("$name missing", value != null && value.startsWith("Sent ") && !value.contains("Shared"))
        }
    }
}
