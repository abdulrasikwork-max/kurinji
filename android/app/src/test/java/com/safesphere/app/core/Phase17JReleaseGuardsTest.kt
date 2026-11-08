package com.safesphere.app.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17J guards for the release configuration. They scan the build file, the R8 rules, the manifest and the main
 * network config, so that a later edit cannot quietly turn shrinking off, sign a release with the debug key, allow
 * plain HTTP in a release, or let R8 rename the fields Gson reads.
 */
class Phase17JReleaseGuardsTest {

    private val gradle = File("build.gradle.kts").readText(Charsets.UTF_8)
    private val rules = File("proguard-rules.pro").readText(Charsets.UTF_8)
    private val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
    private val network = File("src/main/res/xml/network_security_config.xml").readText(Charsets.UTF_8)

    private fun releaseBlock(): String {
        val start = gradle.indexOf("        release {")
        assertTrue("release block not found", start >= 0)
        return gradle.substring(start, gradle.indexOf("        create(\"releaseCheck\")", start))
    }

    @Test
    fun `release shrinks and keeps its signing out of the repository and off the debug key`() {
        val release = releaseBlock()
        assertTrue(release.contains("isMinifyEnabled = true"))
        assertTrue(release.contains("isShrinkResources = true"))
        assertFalse("release must never use the debug key", release.contains("getByName(\"debug\")"))
        assertTrue("signing only when a keystore was supplied", release.contains("if (releaseSigningConfigured)"))
        listOf("storePassword", "keyPassword").forEach { name ->
            // the build file only READS them from keystore.properties / the environment, never holds a value
            assertFalse(Regex("$name\\s*=\\s*\"").containsMatchIn(gradle))
        }
        assertTrue(gradle.contains("keystore.properties") && gradle.contains("IRL_KEYSTORE_FILE"))
    }

    @Test
    fun `the release server address is separate, https only and never the development one`() {
        assertTrue(gradle.contains("release.api.base.url"))
        assertTrue(releaseBlock().contains("releaseApiBaseUrl"))
        assertFalse(releaseBlock().contains("apiBaseUrl"))
        assertTrue(gradle.contains("uri.scheme == \"https\""))
        assertTrue("a signed release may not carry the placeholder", gradle.contains(".invalid"))
    }

    @Test
    fun `the release check build is for testing only and is not the real application id`() {
        val start = gradle.indexOf("create(\"releaseCheck\")")
        assertTrue(start >= 0)
        val block = gradle.substring(start).substringBefore("compileOptions")
        assertTrue(block.contains("applicationIdSuffix = \".releasecheck\""))
        assertTrue(block.contains("signingConfigs.getByName(\"debug\")"))
    }

    @Test
    fun `r8 keeps every field name Gson and the phone's own files rely on`() {
        assertTrue(rules.contains("-keepclassmembers class com.safesphere.app.** { <fields>; }"))
        assertTrue(rules.contains("-keep class com.safesphere.app.**.data.remote.** { *; }"))
        assertTrue("classes only Gson fills (ErrorBody) were merged away and lost their fields",
            rules.lines().any { it.trim() == "-keep,allowobfuscation class com.safesphere.app.**" })
        assertTrue("TypeToken subclasses lose their type under R8 (crash at launch, found on the emulator)",
            rules.contains("-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken"))
        assertTrue(rules.contains("Signature"))
        assertTrue(rules.contains("InnerClasses"))
    }

    @Test
    fun `the app declares one strict network config and allows no cleartext traffic`() {
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse(manifest.contains("usesCleartextTraffic=\"true\""))
        assertTrue(network.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse(network.contains("cleartextTrafficPermitted=\"true\""))
        assertTrue(network.contains("<certificates src=\"system\" />"))
        assertFalse("no user-installed CAs", network.contains("src=\"user\""))
    }

    @Test
    fun `backups stay off and the debug network config is not in the main source set`() {
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertFalse(File("src/main/res/xml").listFiles()!!.any { it.name.contains("dev_network") })
        assertFalse(network.contains("10.0.2.2"))
    }
}
