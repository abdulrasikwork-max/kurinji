package com.safesphere.app.core.retired

import java.io.File

/**
 * Phase 13F, decision D9 (docs/EXPERIENCE_PLATFORM_DESIGN.md §15): Rise "Proof" is retired. Its notes lived only on
 * this phone, encrypted per account in `files/rise/`; nothing from Rise was ever on a server. On the first start of a
 * Kurinji build the folder is deleted once, and a marker file asks the app to tell the person, once.
 *
 * Plain file operations on [filesDir], so a test can run it on a temporary folder. Touches nothing else.
 */
class RiseRetirement(private val filesDir: File) {

    private val riseDir get() = File(filesDir, RISE_DIR)
    private val marker get() = File(filesDir, NOTICE_MARKER)

    /** Deletes Rise's folder if it is there. True when something was deleted (the notice is then pending). */
    @Synchronized
    fun retire(): Boolean {
        val dir = riseDir
        if (!dir.exists()) return false
        dir.walkBottomUp().forEach { it.delete() }
        if (dir.exists()) return false // something could not be deleted: try again on the next start, say nothing yet
        marker.writeText("1")
        return true
    }

    /** True until [noticeShown]: Rise's notes were deleted and the person has not been told yet. */
    fun noticePending(): Boolean = marker.exists()

    fun noticeShown() {
        marker.delete()
    }

    companion object {
        const val RISE_DIR = "rise"
        const val NOTICE_MARKER = "rise-retired-notice"
    }
}
