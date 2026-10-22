package com.safesphere.app.feature.rise.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import com.safesphere.app.R
import com.safesphere.app.databinding.FragmentRiseProofBinding
import com.safesphere.app.feature.rise.domain.ProofPolicy
import com.safesphere.app.feature.rise.domain.RiseJournal
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Your proof": every step the person did, newest first, with its date, what they expected and how it went. Per goal,
 * "You weren't sure 5 times. 4 went better than you expected." No headline total, no level, no streak, no comparison.
 * Quiet mode shows "A step in Learn" instead of the person's words.
 */
class RiseProofFragment : RiseScreenFragment<FragmentRiseProofBinding>(FragmentRiseProofBinding::inflate) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectReady(::render)
    }

    private fun render(journal: RiseJournal) {
        val quiet = journal.quietMode
        val numbers = ProofPolicy.numbersOf(journal)
        binding.summaryContainer.removeAllViews()
        journal.goals.forEach { goal ->
            val summary = RiseTexts.summary(resources, ProofPolicy.summary(goal)) ?: return@forEach
            binding.summaryContainer.addCard(title = RiseTexts.goalTitle(resources, goal, numbers.getValue(goal.id), quiet), body = summary)
        }

        val entries = ProofPolicy.proof(journal)
        binding.proofEmpty.isVisible = entries.isEmpty()
        binding.proofContainer.removeAllViews()
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        entries.forEach { entry ->
            val domain = getString(RiseTexts.domain(entry.domain))
            val what = if (quiet) getString(R.string.rise_proof_step_quiet, domain) else entry.stepWords
            val outcome = getString(RiseTexts.outcome(entry.outcome))
            val went = entry.expectation?.let { getString(R.string.rise_proof_expected, getString(RiseTexts.expectation(it)), outcome) }
                ?: getString(R.string.rise_proof_no_expectation, outcome)
            val lines = buildList {
                add(went)
                if (entry.supports.isNotEmpty()) {
                    add(getString(R.string.rise_proof_with, entry.supports.joinToString(", ") { getString(RiseTexts.support(it)) }))
                }
                if (entry.first) add(getString(R.string.rise_proof_first))
            }
            binding.proofContainer.addCard(
                title = what,
                subtitle = getString(R.string.rise_proof_line, entry.day.format(format), domain),
                body = lines.joinToString("\n")
            )
        }
    }
}
