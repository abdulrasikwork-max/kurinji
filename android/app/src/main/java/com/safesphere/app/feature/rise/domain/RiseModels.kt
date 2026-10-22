package com.safesphere.app.feature.rise.domain

import java.time.LocalDate

/**
 * Rise "Proof" (Phase 10B, docs/PHASE10_DESIGN.md, results in docs/PHASE11.md): a private, phone-only cycle
 * Doubt -> Prepare -> Try -> Proof -> Next. Everything here lives only in the encrypted file on this phone; nothing
 * is sent anywhere. Codes are closed lists; the person's own words are optional and never leave the phone.
 */

/** Life domains (closed list). No health, relationship, religion or sexuality domain on purpose: "Something else". */
enum class RiseDomain { GO, LEARN, WORK, LEAD, MOVE, MONEY, DECIDE, BE_ME, OTHER }

/** "How do you think this will go?" (optional). */
enum class Expectation { CANT, NOT_SURE, PROBABLY_FINE, CONFIDENT;

    /** The person doubted it: the case where "it went better than you expected" is evidence against the doubt. */
    val doubtful: Boolean get() = this == CANT || this == NOT_SURE
}

/** Self-doubts, shown in the first person. They are predictions to test. Never gendered, never naming a stereotype. */
enum class SelfDoubt { CAN_I, ON_MY_OWN, NOT_FOR_SOMEONE_LIKE_ME, WHAT_OTHERS_THINK, NOT_GOOD_ENOUGH_YET, NEVER_DONE_THIS }

/** Supports the person may choose. Unranked: none of them is "less" than another, and using one is never a step back. */
enum class Support { WITH_SOMEONE, TIME_I_CHOOSE, WAY_I_KNOW, PRACTISE_FIRST, SMALLER_VERSION, SOMEONE_KNOWS }

/**
 * "How did it go?". [NOT_OK] = "Something happened that wasn't OK": never a failure, never "harder", never proof,
 * never used to persuade anyone of anything.
 */
enum class Outcome { BETTER, AS_EXPECTED, HARDER, DID_NOT_GO_AHEAD, NOT_OK;

    /** The step was done (whatever it felt like): this is proof. */
    val didIt: Boolean get() = this == BETTER || this == AS_EXPECTED || this == HARDER
}

/**
 * One try of a step. [safetyConcern] = "I'm worried about my safety getting there or being there": respected, never
 * "disproven", never part of a replay. Dates only (no time of day).
 */
data class Attempt(
    val id: Long,
    val startedOn: LocalDate,
    val expectation: Expectation? = null,
    val doubts: Set<SelfDoubt> = emptySet(),
    val safetyConcern: Boolean = false,
    val supports: Set<Support> = emptySet(),
    val outcome: Outcome? = null,
    val endedOn: LocalDate? = null,
    val first: Boolean = false
) {
    val open: Boolean get() = outcome == null
}

data class Step(val id: Long, val words: String, val attempts: List<Attempt> = emptyList()) {
    val openAttempt: Attempt? get() = attempts.lastOrNull()?.takeIf { it.open }
    val timesDone: Int get() = attempts.count { it.outcome?.didIt == true }
}

/** [words] may be empty ("Keep it vague if you like"). [aside] = put aside for now (kept, not shown first). */
data class Goal(
    val id: Long,
    val domain: RiseDomain,
    val words: String,
    val steps: List<Step>,
    val createdOn: LocalDate,
    val aside: Boolean = false
)

/**
 * The whole Rise record of ONE account on this phone.
 * @param quietMode lists show "Goal 2 · Learn" instead of the person's words (for a phone others may look at)
 * @param lockWithScreenLock opening Rise needs the phone's screen lock
 */
data class RiseJournal(
    val goals: List<Goal> = emptyList(),
    val introSeen: Boolean = false,
    val quietMode: Boolean = false,
    val lockWithScreenLock: Boolean = false,
    val nextId: Long = 1
) {
    fun goal(id: Long): Goal? = goals.firstOrNull { it.id == id }

    override fun toString(): String = "RiseJournal(goals=${goals.size}, quiet=$quietMode, lock=$lockWithScreenLock)"
}
