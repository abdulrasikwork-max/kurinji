package com.safesphere.app.feature.community.domain

/**
 * Phase 17F (D-9): what the one-step Nearby report offers. Only a closed list of place conditions, grouped the way the
 * radar and the area summary group them (a concern, a condition to take care of, a safe place). There is deliberately
 * no free text, no photo and no way to name a person: a place signal describes a place (Phase 6).
 */
object ReportChoices {

    /** Every [SignalCategory] exactly once, in the order of the groups. */
    val groups: List<Pair<SignalGroup, List<SignalCategory>>> =
        SignalGroup.entries.map { group -> group to SignalCategory.entries.filter { it.group == group } }
            .filter { it.second.isNotEmpty() }
}
