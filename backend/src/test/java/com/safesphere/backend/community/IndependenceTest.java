package com.safesphere.backend.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.community.service.Independence;
import com.safesphere.backend.community.service.Independence.Linkage;
import com.safesphere.backend.community.service.Independence.Participant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndependenceTest {

    private record P(Long participant, int weight) implements Participant {
    }

    @Test
    void puppetPattern_isLinked_activeLocals_areNot() {
        assertTrue(Independence.linked(3, 3, 3), "everything they did, they did together");
        assertTrue(Independence.linked(3, 40, 6), "half of the smaller account's activity is shared");
        assertFalse(Independence.linked(3, 10, 10), "regulars sharing a few popular places stay independent");
        assertFalse(Independence.linked(2, 2, 2), "fewer than 3 shared signals is never enough");
    }

    @Test
    void fromRows_buildsASymmetricLinkage() {
        Linkage linkage = Independence.fromRows(List.<Object[]>of(new Object[]{1L, 2L, 3L}, new Object[]{3L, 4L, 3L},
                new Object[]{2L, 5L, 3L}), Map.of(1L, 3L, 2L, 3L, 3L, 20L, 4L, 10L, 5L, 40L));
        assertTrue(linkage.linked(1, 2));
        assertTrue(linkage.linked(2, 1));
        assertFalse(linkage.linked(3, 4), "both do many other things");
        assertTrue(linkage.linked(2, 5), "account 2 does nothing without account 5 (a puppet of a busy owner)");
        assertFalse(linkage.linked(1, 3), "no shared row at all");
        assertFalse(linkage.linked(1, 1));
        assertSame(Linkage.NONE, Independence.fromRows(List.of(), Map.of()));
    }

    @Test
    void select_keepsTheStrongest_andSkipsAnyoneLinkedToAPickedOne() {
        Linkage linkage = (a, b) -> (a == 1 && b == 2) || (a == 2 && b == 1);
        List<P> picked = Independence.select(List.of(new P(1L, 2), new P(2L, 3), new P(3L, 2)), linkage);
        assertEquals(List.of(new P(2L, 3), new P(3L, 2)), picked, "the linked pair counts once, with its best weight");
    }

    @Test
    void select_unknownParticipants_areAlwaysIndependent_butTheSamePersonCountsOnce() {
        assertEquals(2, Independence.select(List.of(new P(null, 2), new P(null, 2)), (a, b) -> true).size());
        assertEquals(1, Independence.select(List.of(new P(5L, 2), new P(5L, 2)), Linkage.NONE).size());
    }
}
