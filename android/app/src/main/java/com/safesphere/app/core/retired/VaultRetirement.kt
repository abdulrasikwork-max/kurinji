package com.safesphere.app.core.retired

import java.io.File

/**
 * Phase N1 (decision N-9, docs/PHASE_N1.md): the Evidence Vault and audio in an SOS are retired. What the vault kept on
 * this phone is deleted once, on the first start of a build without it:
 *
 *   files/evidence/           the encrypted items, their index (with the sealed item keys), custody anchors, recovery keys
 *   files/evidence-capture/   captures that were never sealed (plain files)
 *   cache/evidence-view/      plain copies made for viewing
 *
 * The person is told once, and only when the vault really held something of theirs (an encrypted item or an unsealed
 * capture): the old build created the empty folders on every phone, and a notice about nothing would only confuse.
 *
 * Plain file operations on [filesDir] and [cacheDir], so a test can run it on temporary folders. Touches nothing else.
 */
class VaultRetirement(private val filesDir: File, private val cacheDir: File) {

    private val vaultDir get() = File(filesDir, VAULT_DIR)
    private val captureDir get() = File(filesDir, CAPTURE_DIR)
    private val viewDir get() = File(cacheDir, VIEW_DIR)
    private val marker get() = File(filesDir, NOTICE_MARKER)

    /** Deletes the vault's folders if they are there. True when something of the person's was deleted (notice pending). */
    @Synchronized
    fun retire(): Boolean {
        val dirs = listOf(vaultDir, captureDir, viewDir)
        if (dirs.none { it.exists() }) return false
        val heldSomething = vaultDir.listFiles().orEmpty().any { it.name.endsWith(ITEM_SUFFIX) } ||
            captureDir.listFiles().orEmpty().isNotEmpty()
        dirs.forEach { dir -> dir.walkBottomUp().forEach { it.delete() } }
        if (dirs.any { it.exists() }) {
            // Something could not be deleted: try again on the next start. If items were among it, remember to say so.
            if (heldSomething) marker.writeText("1")
            return false
        }
        if (heldSomething) marker.writeText("1")
        return heldSomething
    }

    /** True until [noticeShown]: vault items were deleted from this phone and the person has not been told yet. */
    fun noticePending(): Boolean = marker.exists() && listOf(vaultDir, captureDir, viewDir).none { it.exists() }

    fun noticeShown() {
        marker.delete()
    }

    companion object {
        const val VAULT_DIR = "evidence"
        const val CAPTURE_DIR = "evidence-capture"
        const val VIEW_DIR = "evidence-view"
        const val ITEM_SUFFIX = ".ssev"
        const val NOTICE_MARKER = "vault-retired-notice"
    }
}
