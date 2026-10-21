package com.safesphere.backend.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.service.CoordinationPolicy;
import com.safesphere.backend.community.service.CoordinationPolicy.Backing;
import com.safesphere.backend.community.service.CoordinationPolicy.Group;
import com.safesphere.backend.community.service.CoordinationPolicy.Rule;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.community.service.Independence.Linkage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * coord-1 (Phase 9C), the pure rules. The same cases the 9A simulation measured end to end (docs/PHASE9.md §3): rings
 * and padded pairs are found; one active person whose reports strangers confirm, a hostel and neighbours walking
 * their own area are not.
 */
class CoordinationPolicyTest {

    private final List<Backing> backings = new ArrayList<>();
    private long nextId = 1;

    private long signal(SignalCategory category, String areaCell, boolean confirmed, Long... people) {
        long id = nextId++;
        backings.add(new Backing(id, category, areaCell, confirmed, Set.of(people)));
        return id;
    }

    /** Area cells ~5.5 km apart: never two in one neighbourhood. */
    private static String spread(int i) {
        return GeoCell.encode(-12.5 + (i / 4) * 0.05, -135.5 + (i % 4) * 0.05, GeoCell.AREA_PRECISION);
    }

    private static final long[][] RING_OF_FOUR = {{1, 2}, {1, 3}, {1, 4}, {2, 3}, {2, 4}, {3, 4}};

    // ---- R1 support core ----------------------------------------------------------------------------------------------

    @Test
    void aRingThatNeverRepeatsAPair_isOneSupportCore() {
        for (int i = 0; i < RING_OF_FOUR.length; i++) {
            signal(SignalCategory.POOR_LIGHTING, spread(i), true, RING_OF_FOUR[i][0], RING_OF_FOUR[i][1]);
        }
        List<Group> groups = CoordinationPolicy.groups(backings, Linkage.NONE);
        assertEquals(1, groups.size());
        assertEquals(Rule.SUPPORT_CORE, groups.get(0).rule());
        assertEquals(Set.of(1L, 2L, 3L, 4L), groups.get(0).people());
        assertEquals(6, groups.get(0).signals().size());
        assertEquals(6, groups.get(0).areaCells());
    }

    @Test
    void onePowerUserWhoseReportsStrangersConfirm_isNoGroup() {
        for (int i = 0; i < 6; i++) signal(SignalCategory.POOR_LIGHTING, spread(i), true, 1L, 100L + i);
        assertTrue(CoordinationPolicy.groups(backings, Linkage.NONE).isEmpty());
    }

    @Test
    void aGroupInsideOneNeighbourhood_isLeftAlone() {
        String area = spread(0);
        for (long[] pair : RING_OF_FOUR) signal(SignalCategory.POOR_LIGHTING, area, true, pair[0], pair[1]);
        assertTrue(CoordinationPolicy.groups(backings, Linkage.NONE).isEmpty(), "a hostel is not a ring");
        assertTrue(CoordinationPolicy.withinOneNeighbourhood(List.of(area, GeoCell.neighbourhood(area).iterator().next())));
        assertFalse(CoordinationPolicy.withinOneNeighbourhood(List.of(spread(0), spread(5))));
    }

    @Test
    void popularSignals_areIgnored_soPaddingChangesNothing() {
        long[][] pairs = {{1, 2}, {1, 3}, {2, 3}, {1, 2}, {1, 3}, {2, 3}};
        for (int i = 0; i < pairs.length; i++) signal(SignalCategory.ISOLATED_STRETCH, spread(i), true, pairs[i][0], pairs[i][1]);
        signal(SignalCategory.WELL_LIT_BUSY, spread(10), true, 1L, 2L, 3L, 50L, 51L);
        assertEquals(1, CoordinationPolicy.groups(backings, Linkage.NONE).size());
    }

    @Test
    void aPairPulledIntoARingThroughOnePlace_isInTheCore_butNotAMember() {
        for (int i = 0; i < RING_OF_FOUR.length; i++) {
            signal(SignalCategory.POOR_LIGHTING, spread(i), true, RING_OF_FOUR[i][0], RING_OF_FOUR[i][1]);
        }
        signal(SignalCategory.BROKEN_PATH, spread(8), true, 1L, 70L, 71L); // ring member 1 confirms an honest pair's place
        Group group = CoordinationPolicy.groups(backings, Linkage.NONE).get(0);
        assertEquals(Set.of(1L, 2L, 3L, 4L, 70L, 71L), group.people(), "both now have two partners: the 2-core takes them in");
        assertEquals(Set.of(1L, 2L, 3L, 4L), group.members(), "one shared place does not make anyone a member");
    }

    @Test
    void everyMemberOfAnExclusivePair_backsSeveralOfItsPlaces() {
        for (int i = 0; i < 3; i++) signal(SignalCategory.POOR_LIGHTING, spread(i), true, 1L, 2L);
        assertEquals(Set.of(1L, 2L), CoordinationPolicy.groups(backings, Linkage.NONE).get(0).members());
    }

    @Test
    void unconfirmedSignals_doNotMakeACoreFinding() {
        for (int i = 0; i < RING_OF_FOUR.length; i++) {
            signal(SignalCategory.POOR_LIGHTING, spread(i), i < 3, RING_OF_FOUR[i][0], RING_OF_FOUR[i][1]);
        }
        assertTrue(CoordinationPolicy.groups(backings, Linkage.NONE).isEmpty(), "3 confirmed < 4");
    }

    // ---- R2 exclusive pair ------------------------------------------------------------------------------------------

    @Test
    void aPairThatAloneBacksThreeSpreadSignals_isFound_unlessPhase7AlreadyLinksIt() {
        for (int i = 0; i < 3; i++) signal(SignalCategory.POOR_LIGHTING, spread(i), true, 1L, 2L);
        List<Group> groups = CoordinationPolicy.groups(backings, Linkage.NONE);
        assertEquals(1, groups.size());
        assertEquals(Rule.EXCLUSIVE_PAIR, groups.get(0).rule());
        assertTrue(CoordinationPolicy.groups(backings, (a, b) -> a != b).isEmpty(), "a linked pair is Phase 7's job");
    }

    @Test
    void twoExclusiveSignals_areBelowTheThreshold() {
        for (int i = 0; i < 2; i++) signal(SignalCategory.POOR_LIGHTING, spread(i), true, 1L, 2L);
        assertTrue(CoordinationPolicy.groups(backings, Linkage.NONE).isEmpty());
    }

    // ---- The case goes on the signal doing the most harm --------------------------------------------------------------

    @Test
    void theCaseTarget_isWhereTheGroupDoesMostHarm_thenConcernsBeforeHavens_thenIncidentsFirst() {
        String crowded = spread(0);
        signal(SignalCategory.HELP_POINT, spread(1), true, 1L, 2L);
        long condition = signal(SignalCategory.POOR_LIGHTING, crowded, true, 1L, 2L);
        long incident = signal(SignalCategory.HARASSMENT, crowded, true, 1L, 2L);
        Group group = CoordinationPolicy.groups(backings, Linkage.NONE).get(0);
        assertEquals(incident, group.target(), "two signals in one area beat one elsewhere; an incident first");
        assertTrue(group.contains(condition));
    }

    @Test
    void theRulesArePure_andCannotTouchAnything() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "com", "safesphere", "backend", "community",
                "service", "CoordinationPolicy.java"));
        for (String forbidden : new String[] {"Repository", "@Service", "@Component", "@Transactional", "Clock",
                "LocalDateTime.now"}) {
            assertFalse(source.contains(forbidden), "CoordinationPolicy must stay pure: " + forbidden);
        }
    }
}
