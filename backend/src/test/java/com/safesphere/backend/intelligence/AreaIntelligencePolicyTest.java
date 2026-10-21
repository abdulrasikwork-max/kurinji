package com.safesphere.backend.intelligence;

import static com.safesphere.backend.community.entity.SignalCategory.BROKEN_PATH;
import static com.safesphere.backend.community.entity.SignalCategory.FOLLOWING;
import static com.safesphere.backend.community.entity.SignalCategory.HARASSMENT;
import static com.safesphere.backend.community.entity.SignalCategory.HELP_POINT;
import static com.safesphere.backend.community.entity.SignalCategory.ISOLATED_STRETCH;
import static com.safesphere.backend.community.entity.SignalCategory.OPEN_LATE_STAFFED;
import static com.safesphere.backend.community.entity.SignalCategory.POOR_LIGHTING;
import static com.safesphere.backend.community.entity.SignalCategory.THEFT_OR_SNATCHING;
import static com.safesphere.backend.community.entity.SignalCategory.UNSAFE_CROSSING;
import static com.safesphere.backend.community.entity.SignalCategory.WELL_LIT_BUSY;
import static com.safesphere.backend.community.service.SignalPolicy.Confidence.HIGH;
import static com.safesphere.backend.community.service.SignalPolicy.Confidence.LOW;
import static com.safesphere.backend.community.service.SignalPolicy.Confidence.MODERATE;
import static com.safesphere.backend.community.service.SignalPolicy.Freshness.EARLIER;
import static com.safesphere.backend.community.service.SignalPolicy.Freshness.THIS_WEEK;
import static com.safesphere.backend.community.service.SignalPolicy.Freshness.TODAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalPolicy.Freshness;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPatterns;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPicture;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Concern;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Evidence;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Information;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Outlook;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Picture;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 8 rules-1, one test per worked example of docs/PHASE8_DESIGN.md §5.8 plus the "do less" invariants (§6.4);
 * since Phase 9D the active rules are rules-1.1 (example 7 changed on purpose; rules-2 is in RulesTwoTest).
 */
class AreaIntelligencePolicyTest {

    static final String AREA = "tdr1v9";
    static final String BLOCK = "tdr1v9q";
    static final String OTHER_BLOCK = "tdr1v9r";
    static final List<String> NEIGHBOURHOOD = List.copyOf(GeoCell.neighbourhood(AREA));

    private static Evidence ev(SignalCategory category, String cell, Confidence confidence, Freshness freshness) {
        return new Evidence(category, cell, confidence, freshness, false);
    }

    private static CellPicture one(Evidence... evidence) {
        return one(CellPatterns.NONE, evidence);
    }

    private static CellPicture one(CellPatterns patterns, Evidence... evidence) {
        return AreaIntelligencePolicy.cells(List.of(AREA), List.of(evidence), Map.of(AREA, patterns)).get(0);
    }

    private static Picture around(Evidence... evidence) {
        return AreaIntelligencePolicy.neighbourhood(AREA, NEIGHBOURHOOD, List.of(evidence), Map.of());
    }

    // ---- Worked examples (§5.8) ---------------------------------------------------------------------------------

    @Test
    void example1_nothingReported_isNoInformation_neverSafe() {
        CellPicture p = one();
        assertEquals(Outlook.NO_INFORMATION, p.outlook());
        assertEquals(Information.NONE, p.information());
        assertEquals(Concern.NONE_CONFIRMED, p.concern());
        assertTrue(p.reasons().isEmpty());
        assertEquals(Outlook.NO_INFORMATION, around().outlook(), "an empty neighbourhood is unknown, not safe");
    }

    @Test
    void example2_oneUnconfirmedReport_isLittleInformation_withoutConcern() {
        CellPicture p = one(ev(POOR_LIGHTING, BLOCK, LOW, TODAY));
        assertEquals(Outlook.LITTLE_INFORMATION, p.outlook());
        assertEquals(Concern.NONE_CONFIRMED, p.concern());
        assertEquals(List.of(Reason.UNCONFIRMED_REPORTS, Reason.LITTLE_INFORMATION), p.reasons());
        assertTrue(p.concernCategories().isEmpty(), "an unconfirmed category is not named as a concern");
    }

    @Test
    void example3_twoRecentConfirmedConditions_takeExtraCare_withTheirCategories() {
        CellPicture p = one(ev(POOR_LIGHTING, BLOCK, MODERATE, THIS_WEEK), ev(ISOLATED_STRETCH, OTHER_BLOCK, MODERATE, THIS_WEEK));
        assertEquals(Outlook.TAKE_EXTRA_CARE, p.outlook());
        assertEquals(List.of(POOR_LIGHTING, ISOLATED_STRETCH), p.concernCategories());
        assertEquals(List.of(Reason.CONFIRMED_CONDITION), p.reasons());
        assertEquals(Information.SOME, p.information());
    }

    @Test
    void example4_strongIncidentToday_takeExtraCare() {
        CellPicture p = one(ev(HARASSMENT, BLOCK, HIGH, TODAY));
        assertEquals(Outlook.TAKE_EXTRA_CARE, p.outlook());
        assertEquals(List.of(Reason.CONFIRMED_INCIDENT_TODAY, Reason.LITTLE_INFORMATION), p.reasons());
    }

    @Test
    void example5_strongIncidentOnlyThisWeekIndependently_isTakeCare() {
        // The evidence carries INDEPENDENT freshness (SignalPolicyTest: linked accounts cannot make it TODAY).
        CellPicture p = one(ev(HARASSMENT, BLOCK, HIGH, THIS_WEEK));
        assertEquals(Outlook.TAKE_CARE, p.outlook());
        assertEquals(List.of(Reason.CONFIRMED_INCIDENT_THIS_WEEK, Reason.LITTLE_INFORMATION), p.reasons());
    }

    @Test
    void example6_aHavenNeverCancelsAConcern_inTheSameBlockItIsAConflict() {
        CellPicture p = one(ev(POOR_LIGHTING, BLOCK, MODERATE, TODAY), ev(WELL_LIT_BUSY, BLOCK, MODERATE, TODAY));
        assertEquals(Outlook.TAKE_CARE, p.outlook(), "the concern keeps its level");
        assertTrue(p.conflicting());
        assertTrue(p.reasons().contains(Reason.CONFLICTING_REPORTS));
        assertTrue(p.reasons().contains(Reason.SAFE_PLACES_NEARBY));
    }

    @Test
    void example7_aFloodOfHavens_cannotMakeAnAreaLookKnown() {
        // Phase 9D (rules-1.1): Phase 8 read this as NO_CONFIRMED_CONCERNS (information SOME); two accounts were enough
        // for that (9A S6). Safe places say where help is, not whether there are concerns.
        List<Evidence> havens = new ArrayList<>();
        for (int i = 0; i < 9; i++) havens.add(ev(i % 2 == 0 ? HELP_POINT : OPEN_LATE_STAFFED, BLOCK, HIGH, TODAY));
        CellPicture p = AreaIntelligencePolicy.cells(List.of(AREA), havens, Map.of()).get(0);
        assertEquals(Outlook.LITTLE_INFORMATION, p.outlook());
        assertEquals(Information.LITTLE, p.information(), "havens alone count once (rules-1.1 lure guard)");
        assertEquals(List.of(Reason.SAFE_PLACES_NEARBY, Reason.LITTLE_INFORMATION), p.reasons(),
                "the safe places are still named");
        assertEquals(Outlook.LITTLE_INFORMATION, around(havens.toArray(Evidence[]::new)).outlook(), "and for the whole");
    }

    @Test
    void withAConfirmedConcern_havensStillAddToHowMuchIsKnown_andNeverLowerTheConcern() {
        List<Evidence> evidence = new ArrayList<>(List.of(ev(POOR_LIGHTING, BLOCK, MODERATE, TODAY)));
        for (int i = 0; i < 4; i++) evidence.add(ev(HELP_POINT, OTHER_BLOCK, MODERATE, TODAY));
        CellPicture p = AreaIntelligencePolicy.cells(List.of(AREA), evidence, Map.of()).get(0);
        assertEquals(Outlook.TAKE_CARE, p.outlook());
        assertEquals(Information.MORE, p.information(), "1 concern + 4 havens (at most 4, as in Phase 8)");
    }

    @Test
    void example8_anAgingCondition_isStillACare_butMayHaveChanged() {
        CellPicture p = one(ev(BROKEN_PATH, BLOCK, MODERATE, EARLIER));
        assertEquals(Outlook.TAKE_CARE, p.outlook());
        assertTrue(p.reasons().contains(Reason.CONDITIONS_MAY_HAVE_CHANGED));
    }

    @Test
    void example11_aConfirmedIncidentWhereIncidentsRecur_takeExtraCare() {
        CellPicture p = one(new CellPatterns(true, false), ev(THEFT_OR_SNATCHING, BLOCK, MODERATE, THIS_WEEK));
        assertEquals(Outlook.TAKE_EXTRA_CARE, p.outlook());
        assertTrue(p.reasons().contains(Reason.RECURRING_CONCERNS));
    }

    @Test
    void example12_aPatternAlone_neverRaisesTheLevel() {
        CellPicture p = one(new CellPatterns(true, true));
        assertEquals(Concern.NONE_CONFIRMED, p.concern());
        assertEquals(Outlook.LITTLE_INFORMATION, p.outlook());
        assertEquals(List.of(Reason.RECURRING_CONCERNS, Reason.LITTLE_INFORMATION), p.reasons());
        CellPicture conditionOnly = one(new CellPatterns(false, true), ev(POOR_LIGHTING, BLOCK, MODERATE, TODAY));
        assertEquals(Concern.SOME, conditionOnly.concern(), "a recurring CONDITION pattern does not elevate");
    }

    // ---- "When uncertain, do less" (§6.4) ------------------------------------------------------------------------

    @Test
    void unconfirmedReports_neverRaiseConcern_howeverMany() {
        List<Evidence> many = new ArrayList<>();
        for (SignalCategory c : List.of(HARASSMENT, FOLLOWING, THEFT_OR_SNATCHING, POOR_LIGHTING, ISOLATED_STRETCH,
                UNSAFE_CROSSING, BROKEN_PATH)) {
            many.add(ev(c, BLOCK, LOW, TODAY));
        }
        CellPicture p = AreaIntelligencePolicy.cells(List.of(AREA), many, Map.of()).get(0);
        assertEquals(Concern.NONE_CONFIRMED, p.concern());
        assertEquals(Information.LITTLE, p.information());
    }

    @Test
    void agingConditions_cannotElevate() {
        CellPicture p = one(ev(POOR_LIGHTING, BLOCK, HIGH, EARLIER), ev(ISOLATED_STRETCH, OTHER_BLOCK, HIGH, EARLIER));
        assertEquals(Concern.SOME, p.concern());
    }

    @Test
    void contradiction_needsTheSameBlock_andConfirmedSignals() {
        assertFalse(one(ev(POOR_LIGHTING, BLOCK, MODERATE, TODAY), ev(WELL_LIT_BUSY, OTHER_BLOCK, MODERATE, TODAY))
                .conflicting(), "different blocks of one area can differ");
        assertFalse(one(ev(POOR_LIGHTING, BLOCK, MODERATE, TODAY), ev(WELL_LIT_BUSY, BLOCK, LOW, TODAY))
                .conflicting(), "an unconfirmed signal has no block to reason about");
        assertTrue(one(ev(ISOLATED_STRETCH, BLOCK, MODERATE, TODAY), ev(WELL_LIT_BUSY, BLOCK, HIGH, TODAY)).conflicting());
    }

    @Test
    void aDisputedConfirmedConcern_isConflicting_aDisputedUnconfirmedOneIsNot() {
        assertTrue(one(new Evidence(POOR_LIGHTING, BLOCK, MODERATE, TODAY, true)).conflicting());
        assertFalse(one(new Evidence(POOR_LIGHTING, BLOCK, LOW, TODAY, true)).conflicting());
    }

    @Test
    void anIncidentToday_hidesTheThisWeekReason() {
        CellPicture p = one(ev(HARASSMENT, BLOCK, MODERATE, TODAY), ev(FOLLOWING, OTHER_BLOCK, MODERATE, THIS_WEEK));
        assertTrue(p.reasons().contains(Reason.CONFIRMED_INCIDENT_TODAY));
        assertFalse(p.reasons().contains(Reason.CONFIRMED_INCIDENT_THIS_WEEK));
    }

    @Test
    void informationBands() {
        assertEquals(Information.LITTLE, one(ev(HELP_POINT, BLOCK, MODERATE, TODAY)).information());
        List<Evidence> five = List.of(ev(POOR_LIGHTING, BLOCK, MODERATE, EARLIER), ev(BROKEN_PATH, BLOCK, MODERATE, EARLIER),
                ev(UNSAFE_CROSSING, BLOCK, MODERATE, EARLIER), ev(ISOLATED_STRETCH, OTHER_BLOCK, MODERATE, EARLIER),
                ev(HELP_POINT, OTHER_BLOCK, MODERATE, TODAY));
        assertEquals(Information.MORE, AreaIntelligencePolicy.cells(List.of(AREA), five, Map.of()).get(0).information());
    }

    // ---- Neighbourhood (§5.4: maximum, never a sum) ------------------------------------------------------------------

    @Test
    void neighbourhood_isTheMaximumOfItsCells_neverASum() {
        List<Evidence> oneEach = new ArrayList<>();
        for (String area : NEIGHBOURHOOD) oneEach.add(ev(POOR_LIGHTING, area + "q", MODERATE, TODAY));
        Picture p = AreaIntelligencePolicy.neighbourhood(AREA, NEIGHBOURHOOD, oneEach, Map.of());
        assertEquals(Concern.SOME, p.concern(), "nine places with one concern each are not an elevated area");
        assertEquals(Information.MORE, p.information(), "but much is known");
        assertEquals(9, p.areas().size());
    }

    @Test
    void neighbourhood_namesTheHighestCell_theViewersOwnWinsTies_noneWhenNothingIsConfirmed() {
        String north = GeoCell.encode(GeoCell.centre(AREA)[0] + 0.006, GeoCell.centre(AREA)[1], 6);
        assertTrue(NEIGHBOURHOOD.contains(north));
        Picture higherElsewhere = around(ev(POOR_LIGHTING, BLOCK, MODERATE, TODAY),
                ev(HARASSMENT, north + "q", HIGH, TODAY));
        assertEquals(Concern.ELEVATED, higherElsewhere.concern());
        assertEquals(north, higherElsewhere.highestConcernCell());

        Picture tie = around(ev(POOR_LIGHTING, north + "q", MODERATE, TODAY), ev(BROKEN_PATH, BLOCK, MODERATE, TODAY));
        assertEquals(AREA, tie.highestConcernCell());

        assertNull(around(ev(HELP_POINT, BLOCK, MODERATE, TODAY)).highestConcernCell());
    }

    @Test
    void neighbourhood_decidesLittleInformationForTheWhole() {
        String north = GeoCell.encode(GeoCell.centre(AREA)[0] + 0.006, GeoCell.centre(AREA)[1], 6);
        Picture p = around(ev(POOR_LIGHTING, BLOCK, MODERATE, TODAY), ev(BROKEN_PATH, north + "q", MODERATE, TODAY));
        assertEquals(Information.SOME, p.information());
        assertFalse(p.reasons().contains(Reason.LITTLE_INFORMATION), "each cell alone knows little; together, some");
        assertEquals(List.of(POOR_LIGHTING, BROKEN_PATH), p.concernCategories());
    }

    @Test
    void evidenceOutsideTheCells_isIgnored() {
        Picture p = around(ev(HARASSMENT, GeoCell.encode(13.3, 77.59, 7), HIGH, TODAY));
        assertEquals(Outlook.NO_INFORMATION, p.outlook());
    }
}
