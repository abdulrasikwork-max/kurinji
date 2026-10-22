package com.safesphere.app.feature.rise.domain

import java.time.LocalDate

/** What the person prepared before trying a step (every part optional). */
data class Preparation(
    val expectation: Expectation? = null,
    val doubts: Set<SelfDoubt> = emptySet(),
    val safetyConcern: Boolean = false,
    val supports: Set<Support> = emptySet()
)

enum class RiseRefusal { NO_STEPS, TOO_MANY_GOALS, NOT_FOUND, ALREADY_TRYING, NOT_TRYING, NOT_SAVED }

sealed interface RiseChange {
    data class Done(val journal: RiseJournal, val id: Long? = null) : RiseChange
    data class Refused(val reason: RiseRefusal) : RiseChange
}

/**
 * Pure rules for goals, steps and tries (no Android, no clock: "today" is passed in). Nothing here ranks support,
 * suggests something "bigger", or treats "I didn't go ahead" as anything but the person's call.
 */
object GoalPolicy {
    const val MAX_GOALS = 20
    const val MAX_STEPS = 12
    const val MAX_WORDS = 80

    /** The person's words: trimmed, one line, at most [MAX_WORDS] characters. Empty is allowed for a goal. */
    fun cleanWords(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(MAX_WORDS).trim()

    /** One step per line, empty lines ignored, at most [MAX_STEPS]. */
    fun stepsFrom(lines: String): List<String> = lines.lines().map(::cleanWords).filter { it.isNotEmpty() }.take(MAX_STEPS)

    fun addGoal(journal: RiseJournal, domain: RiseDomain, words: String, stepLines: String, today: LocalDate): RiseChange {
        val steps = stepsFrom(stepLines)
        if (steps.isEmpty()) return RiseChange.Refused(RiseRefusal.NO_STEPS)
        if (journal.goals.size >= MAX_GOALS) return RiseChange.Refused(RiseRefusal.TOO_MANY_GOALS)
        var next = journal.nextId
        val goalId = next++
        val goal = Goal(goalId, domain, cleanWords(words), steps.map { Step(next++, it) }, today)
        return RiseChange.Done(journal.copy(goals = journal.goals + goal, nextId = next), goalId)
    }

    /** "I'm going to try it": the step is marked as being tried. No timer, no reminder, nothing sent. */
    fun startTry(journal: RiseJournal, goalId: Long, stepId: Long, preparation: Preparation, today: LocalDate): RiseChange {
        val step = journal.goal(goalId)?.steps?.firstOrNull { it.id == stepId } ?: return RiseChange.Refused(RiseRefusal.NOT_FOUND)
        if (step.openAttempt != null) return RiseChange.Refused(RiseRefusal.ALREADY_TRYING)
        val attempt = Attempt(
            id = journal.nextId, startedOn = today, expectation = preparation.expectation, doubts = preparation.doubts,
            safetyConcern = preparation.safetyConcern, supports = preparation.supports
        )
        val updated = updateStep(journal, goalId, stepId) { it.copy(attempts = it.attempts + attempt) }
        return RiseChange.Done(updated.copy(nextId = journal.nextId + 1), attempt.id)
    }

    /** "How did it go?". A "first" is only kept for a step that was done. */
    fun recordOutcome(journal: RiseJournal, goalId: Long, stepId: Long, outcome: Outcome, first: Boolean, today: LocalDate): RiseChange {
        val step = journal.goal(goalId)?.steps?.firstOrNull { it.id == stepId } ?: return RiseChange.Refused(RiseRefusal.NOT_FOUND)
        val open = step.openAttempt ?: return RiseChange.Refused(RiseRefusal.NOT_TRYING)
        val finished = open.copy(outcome = outcome, endedOn = today, first = first && outcome.didIt)
        return RiseChange.Done(updateStep(journal, goalId, stepId) { s -> s.copy(attempts = s.attempts.map { if (it.id == open.id) finished else it }) }, open.id)
    }

    fun setAside(journal: RiseJournal, goalId: Long, aside: Boolean): RiseChange {
        if (journal.goal(goalId) == null) return RiseChange.Refused(RiseRefusal.NOT_FOUND)
        return RiseChange.Done(journal.copy(goals = journal.goals.map { if (it.id == goalId) it.copy(aside = aside) else it }))
    }

    fun deleteGoal(journal: RiseJournal, goalId: Long): RiseChange {
        if (journal.goal(goalId) == null) return RiseChange.Refused(RiseRefusal.NOT_FOUND)
        return RiseChange.Done(journal.copy(goals = journal.goals.filterNot { it.id == goalId }))
    }

    /** The step after [stepId] in the person's own order (null after the last one). Never "a bigger one". */
    fun nextStep(goal: Goal, stepId: Long): Step? {
        val index = goal.steps.indexOfFirst { it.id == stepId }
        return if (index < 0) null else goal.steps.getOrNull(index + 1)
    }

    /** Goals in use first (newest first), goals put aside last. */
    fun ordered(journal: RiseJournal): List<Goal> =
        journal.goals.sortedWith(compareBy<Goal> { it.aside }.thenByDescending { it.id })

    private fun updateStep(journal: RiseJournal, goalId: Long, stepId: Long, change: (Step) -> Step): RiseJournal =
        journal.copy(goals = journal.goals.map { goal ->
            if (goal.id != goalId) goal else goal.copy(steps = goal.steps.map { if (it.id == stepId) change(it) else it })
        })
}
