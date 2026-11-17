package com.safesphere.app.core.retired

import java.io.File

/**
 * Phase N4 (docs/PHASE_N4.md §10): places, cities and video left the app with the old social screens. What they kept
 * on this phone is deleted once, on the first start of a build without them:
 *
 *   files/places/                                   the encrypted private and recent places (Q9), per account
 *   files/datastore/viewer_preferences.preferences_pb   the saved Home city and the "Collapse strong language" switch
 *   cache/video-upload/                             work files of video uploads
 *
 * and, through [forgetKey], the Keystore key that wrapped the places file (nothing else used it since the Evidence
 * Vault was retired in N1; the session has its own key). Nothing here was ever about other people. Plain file
 * operations, so a test can run it on temporary folders. Safe to run on every start: afterwards there is nothing to do.
 */
class OldSocialRetirement(
    private val filesDir: File,
    private val cacheDir: File,
    private val forgetKey: () -> Unit
) {

    /** True when everything is gone (also when there was nothing). */
    @Synchronized
    fun retire(): Boolean {
        val targets = listOf(File(filesDir, PLACES_DIR), File(File(filesDir, DATASTORE_DIR), VIEWER_FILE),
            File(cacheDir, VIDEO_DIR))
        targets.filter { it.exists() }.forEach { target -> target.walkBottomUp().forEach { it.delete() } }
        val done = targets.none { it.exists() }
        // The key goes last and only when its file is gone; a failure is tried again on the next start.
        if (done) runCatching { forgetKey() }
        return done
    }

    companion object {
        const val PLACES_DIR = "places"
        const val DATASTORE_DIR = "datastore"
        const val VIEWER_FILE = "viewer_preferences.preferences_pb"
        const val VIDEO_DIR = "video-upload"
    }
}
