package com.safesphere.app.feature.rise.domain

import java.time.LocalDate

/**
 * The person's own record, shown back at the moment of doubt: "You've felt this 4 times before. 3 went better than
 * you expected." Counted only from finished tries that carried one of the SAME self-doubts. "Something wasn't OK"
 * never counts; a safety concern is never "answered" by a replay.
 */
data class Replay(val times: Int, val better: Int, val asExpected: Int, val harder: Int, val notAhead: Int) {
    val didIt: Int get() = better + asExpected + harder

    /** Mostly harder: the honest replay says so and adds nothing persuasive. */
    val mostlyHarder: Boolean get() = harder > better + asExpected
}

/** One line of "Your proof": a step the person DID (whatever it felt like). Date only. */
data class ProofEntry(
    val goalId: Long,
    val domain: RiseDomain,
    val goalWords: String,
    val goalNumber: Int,
    val stepWords: String,
    val day: LocalDate,
    val expectation: Expectation?,
    val outcome: Outcome,
    val first: Boolean,
    val supports: Set<Support>
)

/** "You weren't sure 5 times. 4 went better than you expected." per goal. No headline count, no level, no streak. */
data class GoalSummary(val unsure: Int, val unsureBetter: Int, val done: Int)

object ProofPolicy {

    fun replayFor(journal: RiseJournal, doubts: Set<SelfDoubt>): Replay? {
        if (doubts.isEmpty()) return null
        val matching = journal.goals.flatMap { goal -> goal.steps.flatMap { it.attempts } }
            .filter { it.outcome != null && it.outcome != Outcome.NOT_OK && it.doubts.any(doubts::contains) }
        if (matching.isEmpty()) return null
        return Replay(
            times = matching.size,
            better = matching.count { it.outcome == Outcome.BETTER },
            asExpected = matching.count { it.outcome == Outcome.AS_EXPECTED },
            harder = matching.count { it.outcome == Outcome.HARDER },
            notAhead = matching.count { it.outcome == Outcome.DID_NOT_GO_AHEAD }
        )
    }

    /** Everything the person did, newest first. "Didn't go ahead" and "something wasn't OK" are not listed here. */
    fun proof(journal: RiseJournal): List<ProofEntry> {
        val numbers = numbersOf(journal)
        return journal.goals.flatMap { goal ->
            goal.steps.flatMap { step ->
                step.attempts.filter { it.outcome?.didIt == true }.map { attempt ->
                    ProofEntry(
                        goalId = goal.id, domain = goal.domain, goalWords = goal.words, goalNumber = numbers.getValue(goal.id),
                        stepWords = step.words, day = attempt.endedOn ?: attempt.startedOn, expectation = attempt.expectation,
                        outcome = attempt.outcome!!, first = attempt.first, supports = attempt.supports
                    ) to attempt.id
                }
            }
        }.sortedWith(compareByDescending<Pair<ProofEntry, Long>> { it.first.day }.thenByDescending { it.second }).map { it.first }
    }

    fun summary(goal: Goal): GoalSummary {
        val done = goal.steps.flatMap { it.attempts }.filter { it.outcome?.didIt == true }
        val unsure = done.filter { it.expectation?.doubtful == true }
        return GoalSummary(unsure = unsure.size, unsureBetter = unsure.count { it.outcome == Outcome.BETTER }, done = done.size)
    }

    fun firsts(journal: RiseJournal): Int =
        journal.goals.sumOf { goal -> goal.steps.sumOf { step -> step.attempts.count { it.first && it.outcome?.didIt == true } } }

    /** "Goal 1", "Goal 2"... in the order they were made (quiet mode shows this instead of the person's words). */
    fun numbersOf(journal: RiseJournal): Map<Long, Int> =
        journal.goals.sortedBy { it.id }.mapIndexed { index, goal -> goal.id to index + 1 }.toMap()
}
