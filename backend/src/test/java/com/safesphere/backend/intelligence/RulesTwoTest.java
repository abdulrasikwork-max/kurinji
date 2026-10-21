package com.safesphere.backend.intelligence;

import static com.safesphere.backend.community.entity.SignalCategory.BROKEN_PATH;
import static com.safesphere.backend.community.entity.SignalCategory.HARASSMENT;
import static com.safesphere.backend.community.entity.SignalCategory.ISOLATED_STRETCH;
import static com.safesphere.backend.community.entity.SignalCategory.POOR_LIGHTING;
import static com.safesphere.backend.community.entity.SignalCategory.THEFT_OR_SNATCHING;
import static com.safesphere.backend.community.service.SignalPolicy.Confidence.HIGH;
import static com.safesphere.backend.community.service.SignalPolicy.Confidence.MODERATE;
import static com.safesphere.backend.community.service.SignalPolicy.Freshness.EARLIER;
import static com.safesphere.backend.community.service.SignalPolicy.Freshness.THIS_WEEK;
import static com.safesphere.backend.community.service.SignalPolicy.Freshness.TODAY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.service.Independence.Linkage;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalPolicy.Freshness;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPatterns;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPicture;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Evidence;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Model;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Outlook;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase 9D-2: the rules-2 candidate (SHADOW by default). It differs from the active rules-1.1 in ONE rule: "take extra
 * care" because of two confirmed concerns also needs three INDEPENDENT people across them. Everything else, including
 * every other way to "take extra care", is unchanged.
 */
class RulesTwoTest {

    static final String AREA = "tdr1v9";
    static final String BLOCK = "tdr1v9q";
    static final String OTHER_BLOCK = "tdr1v9r";

    private static Evidence ev(SignalCategory category, String cell, Confidence confidence, Freshness freshness,
                               Long... people) {
        return new Evidence(category, cell, confidence, freshness, false, Set.of(people));
    }

    private static CellPicture picture(Model model, Linkage people, CellPatterns patterns, Evidence... evidence) {
        return AreaIntelligencePolicy.cells(model, List.of(AREA), List.of(evidence), Map.of(AREA, patterns), people)
                .get(0);
    }

    private static Outlook two(Linkage people, Evidence... evidence) {
        return picture(Model.RULES_2, people, CellPatterns.NONE, evidence).outlook();
    }

    private static Outlook active(Evidence... evidence) {
        return picture(Model.RULES_1_1, Linkage.NONE, CellPatterns.NONE, evidence).outlook();
    }

    @Test
    void aPairOfPeople_reachesTakeCare_notExtraCare() {
        Evidence[] pair = {ev(POOR_LIGHTING, BLOCK, MODERATE, THIS_WEEK, 1L, 2L),
                ev(ISOLATED_STRETCH, OTHER_BLOCK, MODERATE, THIS_WEEK, 1L, 2L)};
        assertEquals(Outlook.TAKE_EXTRA_CARE, active(pair), "the active rules (as Phase 8): extra care from two people");
        assertEquals(Outlook.TAKE_CARE, two(Linkage.NONE, pair), "rules-2: still a warning, never \"fine\"");
    }

    @Test
    void aThirdIndependentPerson_reachesExtraCare_aLinkedThirdDoesNot() {
        Evidence[] three = {ev(POOR_LIGHTING, BLOCK, MODERATE, THIS_WEEK, 1L, 2L),
                ev(ISOLATED_STRETCH, OTHER_BLOCK, MODERATE, THIS_WEEK, 1L, 3L)};
        assertEquals(Outlook.TAKE_EXTRA_CARE, two(Linkage.NONE, three));
        Linkage threeIsTwosPuppet = (a, b) -> (a == 2 && b == 3) || (a == 3 && b == 2);
        assertEquals(Outlook.TAKE_CARE, two(threeIsTwosPuppet, three), "accounts that act together count once");
    }

    @Test
    void peopleASignalCountedSeparately_countSeparatelyHereToo() {
        // On the first signal 2 and 3 were counted as two people although Phase 7 links them: a moderator found that
        // place's support fine (Phase 9C.1, Option B). rules-2 must not undo that human decision.
        Linkage linked = (a, b) -> (a == 2 && b == 3) || (a == 3 && b == 2);
        assertEquals(Outlook.TAKE_EXTRA_CARE, two(linked, ev(POOR_LIGHTING, BLOCK, MODERATE, THIS_WEEK, 2L, 3L),
                ev(ISOLATED_STRETCH, OTHER_BLOCK, MODERATE, THIS_WEEK, 1L, 2L)));
    }

    @Test
    void everyOtherRouteToExtraCare_isUnchanged() {
        assertEquals(Outlook.TAKE_EXTRA_CARE, two(Linkage.NONE, ev(HARASSMENT, BLOCK, HIGH, TODAY, 1L, 2L, 3L)),
                "a strongly confirmed incident today");
        assertEquals(Outlook.TAKE_EXTRA_CARE, picture(Model.RULES_2, Linkage.NONE, new CellPatterns(true, false),
                ev(THEFT_OR_SNATCHING, BLOCK, MODERATE, THIS_WEEK, 1L, 2L)).outlook(), "where incidents recur");
    }

    @Test
    void agingConditions_andUnconfirmedSignals_neverCountTowardsTheThirdPerson() {
        assertEquals(Outlook.TAKE_CARE, two(Linkage.NONE, ev(POOR_LIGHTING, BLOCK, MODERATE, THIS_WEEK, 1L, 2L),
                ev(ISOLATED_STRETCH, OTHER_BLOCK, MODERATE, THIS_WEEK, 1L, 2L),
                ev(BROKEN_PATH, OTHER_BLOCK, MODERATE, EARLIER, 3L, 4L)), "an aging condition is not part of rule (b)");
        assertEquals(Outlook.TAKE_CARE, two(Linkage.NONE, ev(POOR_LIGHTING, BLOCK, MODERATE, THIS_WEEK, 1L, 2L),
                ev(ISOLATED_STRETCH, OTHER_BLOCK, MODERATE, THIS_WEEK, 1L, 2L),
                ev(BROKEN_PATH, OTHER_BLOCK, Confidence.LOW, TODAY, 5L)), "nor is one person's unconfirmed report");
    }

    @Test
    void rulesTwo_isNeverMoreCautiousThanTheActiveRules_andOnlyDiffersOnRuleB() {
        Evidence[][] cases = {
                {ev(POOR_LIGHTING, BLOCK, MODERATE, TODAY, 1L, 2L)},
                {ev(HARASSMENT, BLOCK, MODERATE, TODAY, 1L, 2L), ev(POOR_LIGHTING, OTHER_BLOCK, MODERATE, TODAY, 1L, 2L)},
                {ev(HARASSMENT, BLOCK, HIGH, TODAY, 1L, 2L, 3L)},
                {}};
        for (Evidence[] c : cases) {
            CellPicture one = picture(Model.RULES_1_1, Linkage.NONE, CellPatterns.NONE, c);
            CellPicture two = picture(Model.RULES_2, Linkage.NONE, CellPatterns.NONE, c);
            assertEquals(true, two.concern().compareTo(one.concern()) <= 0);
            assertEquals(one.information(), two.information());
            assertEquals(one.concernCategories(), two.concernCategories());
        }
    }
}
