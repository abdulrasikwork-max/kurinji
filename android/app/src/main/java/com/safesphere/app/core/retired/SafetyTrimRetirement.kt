package com.safesphere.app.core.retired

import java.io.File

/**
 * Phase N6 (docs/PHASE_N6.md §4, D21): shake SOS and the SOS options screen left the app, and the phone no longer texts
 * anyone by itself. What only they kept on this phone is deleted once, on the first start of a build without them:
 *
 *   files/datastore/sos_options.preferences_pb   the per-account shake switch and the old audio choice
 *
 * The automatic-SMS switch and alert memory lived inside the circle-alert record; the coordinator rewrites that record
 * without them (CircleAlertCoordinator, on the first sign-in after the update). Plain file operations, so a test can run
 * it on a temporary folder. Safe on every start: afterwards there is nothing to do.
 */
class SafetyTrimRetirement(private val filesDir: File) {

    /** True when everything is gone (also when there was nothing). */
    @Synchronized
    fun retire(): Boolean {
        val target = File(File(filesDir, DATASTORE_DIR), SOS_OPTIONS_FILE)
        if (target.exists()) target.delete()
        return !target.exists()
    }

    companion object {
        const val DATASTORE_DIR = "datastore"
        const val SOS_OPTIONS_FILE = "sos_options.preferences_pb"
    }
}
