package com.safesphere.app.feature.evidence.domain

import java.security.SecureRandom
import java.text.Normalizer

/** Why a recovery passphrase is not accepted (each has its own explanation on screen). */
enum class PassphraseProblem {
    TOO_SHORT,
    /** Contains the account email's name part or the person's name. */
    PERSONAL,
    /** Equal to (or contains) the account password: the server sees that password at every login. */
    SAME_AS_PASSWORD,
    /** On the list of the 10 000 most common passwords, or one character / a short pattern repeated. */
    COMMON,
    /** The two entries differ. */
    MISMATCH
}

/**
 * Rules for the recovery passphrase (Phase 5 §11). Pure. The passphrase protects the only key the server cannot
 * guess for free: whoever holds the server's database can try passphrases offline, ~15 000 guesses per second per GPU
 * at 600 000 PBKDF2 iterations (estimate). So: long, not personal, not the account password, not a common password.
 * A generated 5-word passphrase (~64.6 bits) is offered first. No strength "score" is shown: it would be a guess.
 */
class PassphrasePolicy(private val commonPasswords: Set<String> = emptySet()) {

    /** The first problem found, or null when the passphrase is acceptable. Nothing here keeps a copy. */
    fun check(
        passphrase: CharArray,
        confirmation: CharArray,
        email: String?,
        name: String?,
        accountPassword: CharArray?
    ): PassphraseProblem? {
        val value = normalize(passphrase)
        if (value.codePointCount(0, value.length) < MIN_LENGTH) return PassphraseProblem.TOO_SHORT
        val folded = value.lowercase()
        val compact = folded.filterNot { it.isWhitespace() }
        if (personalParts(email, name).any { compact.contains(it) }) return PassphraseProblem.PERSONAL
        if (accountPassword != null && accountPassword.isNotEmpty()) {
            val password = normalize(accountPassword).lowercase().filterNot { it.isWhitespace() }
            if (password.isNotEmpty() && (compact == password || compact.contains(password))) {
                return PassphraseProblem.SAME_AS_PASSWORD
            }
        }
        if (folded in commonPasswords || compact in commonPasswords || isRepetitive(compact)) return PassphraseProblem.COMMON
        if (value != normalize(confirmation)) return PassphraseProblem.MISMATCH
        return null
    }

    /** Pieces of the email name part and the name, at least 3 letters (shorter pieces match too much by chance). */
    private fun personalParts(email: String?, name: String?): List<String> {
        val local = email?.substringBefore('@').orEmpty()
        return (local.split('.', '_', '-', '+') + local + name.orEmpty().split(' ', '-', '.'))
            .map { it.lowercase().trim() }
            .filter { it.length >= 3 }
            .distinct()
    }

    /** One character repeated, or a block of up to 4 characters repeated ("abcabcabcabc", "1234123412341234"). */
    private fun isRepetitive(value: String): Boolean {
        if (value.isEmpty()) return true
        for (block in 1..4) {
            if (value.length % block == 0 && value.length > block && value == value.take(block).repeat(value.length / block)) {
                return true
            }
        }
        return value.toSet().size <= 3
    }

    companion object {
        const val MIN_LENGTH = 15

        /** Same normalisation as the key derivation (RecoveryKeyCrypto): NFC, surrounding whitespace removed. */
        fun normalize(chars: CharArray): String = Normalizer.normalize(java.nio.CharBuffer.wrap(chars), Normalizer.Form.NFC).trim()
    }
}

/**
 * Suggests a passphrase of [WORDS] words drawn uniformly with SecureRandom from the EFF large wordlist (7 776 words,
 * log2(7776) ≈ 12.9 bits each, ≈ 64.6 bits for five). Words are lower-case and separated by single spaces.
 */
class PassphraseGenerator(private val words: List<String>, private val random: SecureRandom = SecureRandom()) {

    init {
        require(words.size >= 1024) { "Word list is too short" }
    }

    fun generate(): CharArray {
        val text = (1..WORDS).joinToString(" ") { words[random.nextInt(words.size)] }
        return text.toCharArray()
    }

    companion object {
        const val WORDS = 5

        /** Parses the EFF file format ("11111<TAB>abacus" per line). */
        fun parseEffList(lines: Sequence<String>): List<String> =
            lines.mapNotNull { line -> line.trim().split('\t', ' ').lastOrNull()?.takeIf { it.isNotBlank() } }
                .map { it.lowercase() }
                .distinct()
                .toList()
    }
}
