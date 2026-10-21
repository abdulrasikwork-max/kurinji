package com.safesphere.backend.intelligence.service;

import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.entity.SignalCategory.SignalGroup;
import com.safesphere.backend.community.service.Independence;
import com.safesphere.backend.community.service.Independence.Linkage;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalPolicy.Freshness;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Safety Intelligence, rule set "rules-1" (Phase 8, docs/PHASE8_DESIGN.md §5-§6). Pure and deterministic, so every
 * rule is a unit test (AreaIntelligencePolicyTest). It turns the place signals that people can ALREADY see into an
 * area picture on two separate axes, never collapsed into one number:
 *
 *   CONCERN      how much concern is CONFIRMED (by independent people)       NONE_CONFIRMED | SOME | ELEVATED
 *   INFORMATION  how much the community knows here at all                    NONE | LITTLE | SOME | MORE
 *
 * so "nobody reported anything" (NO_INFORMATION) can never be read as "safe". Per signal:
 *   evidence class  LOW = UNCONFIRMED, MODERATE = CONFIRMED (>= 2 independent people), HIGH = STRONG (>= 3)
 *   relevance       incident: TODAY = CURRENT, otherwise RECENT; condition / haven: EARLIER = AGING, otherwise CURRENT
 *                   (freshness is the INDEPENDENT freshness, so linked accounts cannot keep an incident "today")
 * Per ~1 km area cell (C = CONFIRMED or STRONG incidents and conditions):
 *   ELEVATED  a STRONG incident that is CURRENT, or >= 2 of C that are not AGING, or a confirmed incident in an area
 *             whose incidents are RECURRING (pattern memory, 8C)
 *   SOME      at least one of C
 *   information: known = confirmed concerns + min(confirmed havens, 4) (havens cannot make an area look well known:
 *             lure guard); NONE when nothing at all, LITTLE when known <= 1, SOME up to 4, MORE from 5
 *   conflict  a confirmed concern that is disputed, or a confirmed WELL_LIT_BUSY in the same ~150 m block as a confirmed
 *             POOR_LIGHTING / ISOLATED_STRETCH. Both are shown; nothing is netted: a positive never cancels a concern.
 * A neighbourhood or region is the MAXIMUM of its cells, never a sum (a busy area must not look worse for being busy).
 * One person (or one linked group) can never raise a concern level: every rule above needs a CONFIRMED signal.
 * The picture never says "safe"; it carries reason CODES, and the phone writes the words.
 *
 * Phase 9D (docs/PHASE9.md §19-§20) adds two rule sets on top (see [Model]):
 *   rules-1.1 (ACTIVE)   safe places ALONE say where help is, not whether there are concerns: without a confirmed
 *                        concern they count at most once (information at most LITTLE). Measured: two accounts and two
 *                        fake safe places turned "no information" into "no confirmed concerns" (9A, S6). It only ever
 *                        makes a picture LESS reassuring, so it ships.
 *   rules-2 (CANDIDATE)  rules-1.1, and ELEVATED by "two confirmed concerns" also needs three INDEPENDENT people across
 *                        them (two accounts reached "take extra care" in an hour, S2). It makes a picture LESS
 *                        cautious for honest pairs too, and there is no real-world evidence yet on how often each
 *                        happens, so it runs in SHADOW (counted, never shown) until the user decides.
 */
public final class AreaIntelligencePolicy {

    /**
     * The rule sets. The ACTIVE one is sent with every picture as modelVersion, so the phone (and a viva) knows which
     * rules produced it. Every one uses the same codes, so the phone needs no change between them.
     */
    public enum Model {
        /** Phase 8's rules-1 plus the havens-alone cap (9D-3). */
        RULES_1_1("rules-1.1"),
        /** rules-1.1 plus the breadth rule for "take extra care" (9D-2, SHADOW by default). */
        RULES_2("rules-2");

        private final String version;

        Model(String version) {
            this.version = version;
        }

        public String version() {
            return version;
        }
    }

    public enum Outlook { NO_INFORMATION, LITTLE_INFORMATION, NO_CONFIRMED_CONCERNS, TAKE_CARE, TAKE_EXTRA_CARE }

    public enum Concern { NONE_CONFIRMED, SOME, ELEVATED }

    public enum Information { NONE, LITTLE, SOME, MORE }

    public enum EvidenceClass { UNCONFIRMED, CONFIRMED, STRONG }

    public enum Relevance { CURRENT, RECENT, AGING }

    /** Why the picture is what it is. Aggregate facts about places only; the order is the reading order. */
    public enum Reason {
        CONFIRMED_INCIDENT_TODAY,
        CONFIRMED_INCIDENT_THIS_WEEK,
        CONFIRMED_CONDITION,
        CONDITIONS_MAY_HAVE_CHANGED,
        CONFLICTING_REPORTS,
        RECURRING_CONCERNS,
        UNCONFIRMED_REPORTS,
        SAFE_PLACES_NEARBY,
        LITTLE_INFORMATION
    }

    /**
     * One VISIBLE signal as the rules need it. [cell] is its 7-character cell, used ONLY to find contradictions between
     * CONFIRMED signals (which are public at that precision anyway); it is never returned from here. [people] (Phase
     * 9D) are the opaque keys of its INDEPENDENT supporters, used only to count people across signals (rules-2); never
     * returned either.
     */
    public record Evidence(SignalCategory category, String cell, Confidence confidence, Freshness freshness,
                           boolean disputed, Set<Long> people) {

        public Evidence(SignalCategory category, String cell, Confidence confidence, Freshness freshness,
                        boolean disputed) {
            this(category, cell, confidence, freshness, disputed, Set.of());
        }

        public String areaCell() {
            return cell.substring(0, 6);
        }

        public SignalGroup group() {
            return category.group();
        }

        public EvidenceClass evidenceClass() {
            return switch (confidence) {
                case LOW -> EvidenceClass.UNCONFIRMED;
                case MODERATE -> EvidenceClass.CONFIRMED;
                case HIGH -> EvidenceClass.STRONG;
            };
        }

        public boolean confirmed() {
            return evidenceClass() != EvidenceClass.UNCONFIRMED;
        }

        public boolean concern() {
            return group() != SignalGroup.HAVEN;
        }

        public Relevance relevance() {
            if (group() == SignalGroup.INCIDENT) {
                return freshness == Freshness.TODAY ? Relevance.CURRENT : Relevance.RECENT;
            }
            return freshness == Freshness.EARLIER ? Relevance.AGING : Relevance.CURRENT;
        }
    }

    /** What the pattern memory (8C) says about one area cell. Only RECURRING ever leaves the server, as a reason. */
    public record CellPatterns(boolean incidentsRecurring, boolean conditionsRecurring) {
        public static final CellPatterns NONE = new CellPatterns(false, false);

        public boolean anyRecurring() {
            return incidentsRecurring || conditionsRecurring;
        }
    }

    /** The picture of one ~1 km area cell. */
    public record CellPicture(String cell, Outlook outlook, Concern concern, Information information,
                              List<Reason> reasons, List<SignalCategory> concernCategories, boolean conflicting) {
    }

    /** The picture of a neighbourhood: the maximum of its cells, plus every cell for the radar. */
    public record Picture(Outlook outlook, Concern concern, Information information, List<Reason> reasons,
                          List<SignalCategory> concernCategories, boolean conflicting, String highestConcernCell,
                          List<CellPicture> areas) {
    }

    static final int HAVENS_COUNTED_AT_MOST = 4;
    /** rules-1.1: without a confirmed concern, safe places count at most this often (so information stays LITTLE). */
    static final int HAVENS_ALONE_COUNTED_AT_MOST = 1;
    static final int ELEVATED_MIN_CONFIRMED = 2;
    /** rules-2: independent people needed across the confirmed concerns behind "take extra care" by rule (b). */
    static final int EXTRA_CARE_MIN_PEOPLE = 3;
    static final int SOME_INFORMATION_MIN = 2;
    static final int MORE_INFORMATION_MIN = 5;

    /** Categories that contradict WELL_LIT_BUSY when both are confirmed in the same block. */
    private static final Set<SignalCategory> CONTRADICT_WELL_LIT =
            EnumSet.of(SignalCategory.POOR_LIGHTING, SignalCategory.ISOLATED_STRETCH);

    private AreaIntelligencePolicy() {
    }

    /** Pictures for [cells] under the active rules (rules-1.1), without people linkage (pure tests). */
    public static List<CellPicture> cells(List<String> cells, Collection<Evidence> evidence,
                                          Map<String, CellPatterns> patterns) {
        return cells(Model.RULES_1_1, cells, evidence, patterns, Linkage.NONE);
    }

    /**
     * Pictures for [cells] in the given order (evidence outside them is ignored). [people] is Phase 7's people-level
     * support linkage (rules-2 counts people across signals with it).
     */
    public static List<CellPicture> cells(Model model, List<String> cells, Collection<Evidence> evidence,
                                          Map<String, CellPatterns> patterns, Linkage people) {
        Map<String, List<Evidence>> byCell = group(cells, evidence);
        List<CellPicture> result = new ArrayList<>(cells.size());
        for (String cell : cells) {
            result.add(cell(model, cell, byCell.get(cell), patterns.getOrDefault(cell, CellPatterns.NONE), people));
        }
        return result;
    }

    /** The neighbourhood picture under the active rules (rules-1.1), without people linkage (pure tests). */
    public static Picture neighbourhood(String focus, List<String> cells, Collection<Evidence> evidence,
                                        Map<String, CellPatterns> patterns) {
        return neighbourhood(Model.RULES_1_1, focus, cells, evidence, patterns, Linkage.NONE);
    }

    /**
     * The neighbourhood picture around [focus] (the viewer's own cell, which wins ties for "highest concern").
     * Concern = the highest cell; information is counted over the whole neighbourhood (havens still capped).
     */
    public static Picture neighbourhood(Model model, String focus, List<String> cells, Collection<Evidence> evidence,
                                        Map<String, CellPatterns> patterns, Linkage people) {
        List<CellPicture> pictures = cells(model, cells, evidence, patterns, people);
        Map<String, List<Evidence>> byCell = group(cells, evidence);
        List<Evidence> all = byCell.values().stream().flatMap(List::stream).toList();
        boolean anyRecurring = cells.stream().anyMatch(c -> patterns.getOrDefault(c, CellPatterns.NONE).anyRecurring());

        Concern concern = Concern.NONE_CONFIRMED;
        String highest = null;
        for (CellPicture picture : orderedFocusFirst(focus, pictures)) {
            if (picture.concern().compareTo(concern) > 0) {
                concern = picture.concern();
                highest = picture.cell();
            }
        }
        Information information = information(all, anyRecurring);
        boolean conflicting = pictures.stream().anyMatch(CellPicture::conflicting);

        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        pictures.forEach(p -> reasons.addAll(p.reasons()));
        reasons.remove(Reason.LITTLE_INFORMATION); // re-decided for the neighbourhood as a whole
        if (information == Information.LITTLE) reasons.add(Reason.LITTLE_INFORMATION);
        if (reasons.contains(Reason.CONFIRMED_INCIDENT_TODAY)) reasons.remove(Reason.CONFIRMED_INCIDENT_THIS_WEEK);

        EnumSet<SignalCategory> categories = EnumSet.noneOf(SignalCategory.class);
        pictures.forEach(p -> categories.addAll(p.concernCategories()));

        return new Picture(outlook(concern, information), concern, information, List.copyOf(reasons),
                List.copyOf(categories), conflicting, highest, List.copyOf(pictures));
    }

    // ---- One cell -------------------------------------------------------------------------------------------------

    static CellPicture cell(String cell, List<Evidence> inCell, CellPatterns patterns) {
        return cell(Model.RULES_1_1, cell, inCell, patterns, Linkage.NONE);
    }

    static CellPicture cell(Model model, String cell, List<Evidence> inCell, CellPatterns patterns, Linkage people) {
        List<Evidence> evidence = inCell == null ? List.of() : inCell;
        List<Evidence> confirmedConcerns = evidence.stream().filter(e -> e.concern() && e.confirmed()).toList();

        boolean strongIncidentNow = confirmedConcerns.stream().anyMatch(e -> e.group() == SignalGroup.INCIDENT
                && e.evidenceClass() == EvidenceClass.STRONG && e.relevance() == Relevance.CURRENT);
        List<Evidence> notAging = confirmedConcerns.stream().filter(e -> e.relevance() != Relevance.AGING).toList();
        boolean several = notAging.size() >= ELEVATED_MIN_CONFIRMED
                && (model != Model.RULES_2 || independentPeople(notAging, people) >= EXTRA_CARE_MIN_PEOPLE);
        boolean recurringIncident = patterns.incidentsRecurring()
                && confirmedConcerns.stream().anyMatch(e -> e.group() == SignalGroup.INCIDENT);
        Concern concern = strongIncidentNow || several || recurringIncident
                ? Concern.ELEVATED
                : confirmedConcerns.isEmpty() ? Concern.NONE_CONFIRMED : Concern.SOME;

        Information information = information(evidence, patterns.anyRecurring());
        boolean conflicting = conflicting(evidence);

        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        for (Evidence e : confirmedConcerns) {
            if (e.group() == SignalGroup.INCIDENT) {
                reasons.add(e.relevance() == Relevance.CURRENT
                        ? Reason.CONFIRMED_INCIDENT_TODAY : Reason.CONFIRMED_INCIDENT_THIS_WEEK);
            } else {
                reasons.add(Reason.CONFIRMED_CONDITION);
                if (e.relevance() == Relevance.AGING) reasons.add(Reason.CONDITIONS_MAY_HAVE_CHANGED);
            }
        }
        if (reasons.contains(Reason.CONFIRMED_INCIDENT_TODAY)) reasons.remove(Reason.CONFIRMED_INCIDENT_THIS_WEEK);
        if (conflicting) reasons.add(Reason.CONFLICTING_REPORTS);
        if (patterns.anyRecurring()) reasons.add(Reason.RECURRING_CONCERNS);
        if (evidence.stream().anyMatch(e -> e.concern() && !e.confirmed())) reasons.add(Reason.UNCONFIRMED_REPORTS);
        if (evidence.stream().anyMatch(e -> !e.concern() && e.confirmed())) reasons.add(Reason.SAFE_PLACES_NEARBY);
        if (information == Information.LITTLE) reasons.add(Reason.LITTLE_INFORMATION);

        EnumSet<SignalCategory> categories = EnumSet.noneOf(SignalCategory.class);
        confirmedConcerns.forEach(e -> categories.add(e.category()));

        return new CellPicture(cell, outlook(concern, information), concern, information, List.copyOf(reasons),
                List.copyOf(categories), conflicting);
    }

    /**
     * rules-2: how many INDEPENDENT people stand behind these signals together. Each signal's own independent
     * supporters, every person once, and accounts that keep acting together (Phase 7) once, except two people a signal
     * already counted as separate people (on a signal a moderator found fine, Phase 9C.1 Option B, that is a human
     * decision rules-2 must not undo). Greedy and deterministic (smallest key first), like Independence.select.
     */
    static int independentPeople(List<Evidence> evidence, Linkage people) {
        Set<Long> everyone = new TreeSet<>();
        evidence.forEach(e -> everyone.addAll(e.people()));
        Linkage across = (a, b) -> people.linked(a, b)
                && evidence.stream().noneMatch(e -> e.people().contains(a) && e.people().contains(b));
        List<Person> persons = everyone.stream().map(Person::new).toList();
        return Independence.select(persons, across).size();
    }

    private record Person(Long participant) implements Independence.Participant {
        @Override
        public int weight() {
            return 0;
        }
    }

    static Information information(List<Evidence> evidence, boolean recurring) {
        long concerns = evidence.stream().filter(e -> e.concern() && e.confirmed()).count();
        long havens = evidence.stream().filter(e -> !e.concern() && e.confirmed()).count();
        // rules-1.1 (9D-3): safe places say where help is, not whether there are concerns. Without a confirmed concern
        // they never make an area look known (two accounts' fake havens made "no information" read "no confirmed
        // concerns", 9A S6). With one, they still add to how much is known (a concern sets the outlook anyway).
        long known = concerns + Math.min(havens, concerns == 0 ? HAVENS_ALONE_COUNTED_AT_MOST : HAVENS_COUNTED_AT_MOST);
        boolean anything = known > 0 || recurring || evidence.stream().anyMatch(e -> !e.confirmed());
        if (!anything) return Information.NONE;
        if (known < SOME_INFORMATION_MIN) return Information.LITTLE;
        if (known < MORE_INFORMATION_MIN) return Information.SOME;
        return Information.MORE;
    }

    static Outlook outlook(Concern concern, Information information) {
        return switch (concern) {
            case ELEVATED -> Outlook.TAKE_EXTRA_CARE;
            case SOME -> Outlook.TAKE_CARE;
            case NONE_CONFIRMED -> switch (information) {
                case NONE -> Outlook.NO_INFORMATION;
                case LITTLE -> Outlook.LITTLE_INFORMATION;
                case SOME, MORE -> Outlook.NO_CONFIRMED_CONCERNS;
            };
        };
    }

    /** Disagreement among CONFIRMED signals only (an unconfirmed signal has no block anyone may reason about). */
    static boolean conflicting(List<Evidence> evidence) {
        if (evidence.stream().anyMatch(e -> e.concern() && e.confirmed() && e.disputed())) return true;
        Set<String> wellLitBlocks = new HashSet<>();
        for (Evidence e : evidence) {
            if (e.confirmed() && e.category() == SignalCategory.WELL_LIT_BUSY) wellLitBlocks.add(e.cell());
        }
        return evidence.stream().anyMatch(e -> e.confirmed() && CONTRADICT_WELL_LIT.contains(e.category())
                && wellLitBlocks.contains(e.cell()));
    }

    private static Map<String, List<Evidence>> group(List<String> cells, Collection<Evidence> evidence) {
        Set<String> wanted = new HashSet<>(cells);
        Map<String, List<Evidence>> byCell = new HashMap<>();
        for (Evidence e : evidence) {
            if (wanted.contains(e.areaCell())) byCell.computeIfAbsent(e.areaCell(), k -> new ArrayList<>()).add(e);
        }
        return byCell;
    }

    private static List<CellPicture> orderedFocusFirst(String focus, List<CellPicture> pictures) {
        Map<String, CellPicture> ordered = new LinkedHashMap<>();
        pictures.stream().filter(p -> p.cell().equals(focus)).forEach(p -> ordered.put(p.cell(), p));
        pictures.forEach(p -> ordered.putIfAbsent(p.cell(), p));
        return List.copyOf(ordered.values());
    }
}
