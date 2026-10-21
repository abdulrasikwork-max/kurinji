package com.safesphere.backend.evaluation.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.ModerationState;
import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.community.service.CoordinationPolicy.Group;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.community.service.SignalVisibility.VisibleSet;
import com.safesphere.backend.community.service.TrustService;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPatterns;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPicture;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Model;
import com.safesphere.backend.intelligence.service.AreaIntelligenceService;
import com.safesphere.backend.intelligence.service.AreaModelShadow;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOrigin;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import com.safesphere.backend.moderation.service.CoordinationCheck;
import com.safesphere.backend.moderation.service.ModerationService;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 9A (docs/PHASE9_DESIGN.md §10.1): drives synthetic attackers and honest people through the REAL API (MockMvc,
 * JWT, validation, every limit) on the in-memory test database, with the virtual clock and the real steps of the
 * clean-up job. There is no second model of the rules, so a scenario measures what the server actually does.
 *
 * Everything is synthetic. Places are in open ocean (around 12.5 S, 135.5 W), so no scenario says anything about a real
 * neighbourhood. The harness counts what an ATTACKER spends (accounts, accepted and refused writes, waits forced by the
 * burst limit); honest people and viewers are not counted.
 */
final class AttackHarness {

    static final double ORIGIN_LAT = -12.5;
    static final double ORIGIN_LNG = -135.5;
    /** Longer than the longest publication delay (30 min): after this, everything sent so far counts. */
    static final Duration SETTLE = Duration.ofMinutes(31);
    /** How old a "prepared" account is: past the 24 h probation. */
    static final Duration PREPARED = Duration.ofDays(2);
    private static final String GEOHASH = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final Duration BURST_WINDOW = Duration.ofMinutes(10);

    private final CommunityClient api;
    private final ObjectMapper objectMapper;
    private final MutableClock clock;
    private final JdbcTemplate jdbc;
    private final CommunityService community;
    private final ModerationService moderation;
    private final TrustService trust;
    private final PlaceSignalRepository signals;
    private final SignalContributionRepository contributions;
    private final ModerationCaseRepository cases;
    private final CoordinationCheck coordination;
    private final AreaModelShadow shadow;
    private final Instant start;
    private final Set<Long> attackers = new HashSet<>();
    private final Set<String> viewerAreas = new HashSet<>();
    private Account viewer;

    private int writesAccepted;
    private int writesRefused;
    private int burstWaits;

    AttackHarness(MockMvc mockMvc, ObjectMapper objectMapper, MutableClock clock, JdbcTemplate jdbc,
                  CommunityService community, ModerationService moderation, TrustService trust,
                  PlaceSignalRepository signals, SignalContributionRepository contributions,
                  ModerationCaseRepository cases, CoordinationCheck coordination, AreaModelShadow shadow) {
        this.coordination = coordination;
        this.shadow = shadow;
        this.api = new CommunityClient(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.jdbc = jdbc;
        this.community = community;
        this.moderation = moderation;
        this.trust = trust;
        this.signals = signals;
        this.contributions = contributions;
        this.cases = cases;
        this.start = clock.instant();
    }

    // ---- Places (synthetic, open ocean) -------------------------------------------------------------------------------

    /** A ~1 km area cell on a grid 0.05 degrees (~5.5 km) apart: two grid areas are never in one neighbourhood. */
    static String area(int row, int column) {
        return GeoCell.encode(ORIGIN_LAT + row * 0.05, ORIGIN_LNG + column * 0.05, GeoCell.AREA_PRECISION);
    }

    /** The k-th ~150 m block of an area cell (k = 0..31). */
    static String block(String area, int k) {
        return area + GEOHASH.charAt(k);
    }

    /** Phase 9D: the blocks that share a SIDE with [cell], in a fixed order. */
    static List<String> sides(String cell) {
        return new TreeSet<>(GeoCell.neighbourhood(cell)).stream()
                .filter(c -> GeoCell.adjacency(cell, c) == GeoCell.Adjacency.EDGE).toList();
    }

    /** Phase 9D: the blocks that touch [cell] only at a CORNER. */
    static List<String> corners(String cell) {
        return new TreeSet<>(GeoCell.neighbourhood(cell)).stream()
                .filter(c -> GeoCell.adjacency(cell, c) == GeoCell.Adjacency.CORNER).toList();
    }

    // ---- People ---------------------------------------------------------------------------------------------------

    /** An attacker's account, prepared in advance (past the 24 h probation). Counted as attacker effort. */
    Account attacker() throws Exception {
        Account account = account(PREPARED);
        attackers.add(account.id());
        return account;
    }

    /** An attacker's account created NOW: its contributions and flags count only after the probation. */
    Account freshAttacker() throws Exception {
        Account account = account(Duration.ZERO);
        attackers.add(account.id());
        return account;
    }

    /** An honest person (prepared account). Not counted as attacker effort. */
    Account honest() throws Exception {
        return account(PREPARED);
    }

    /** A moderator (the role is set in the database, as an administrator's appointment would). Not attacker effort. */
    Account moderator() throws Exception {
        Account account = account(PREPARED);
        jdbc.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", account.id());
        return account;
    }

    /** The moderator finds every open COORDINATION case fine (DISMISS, NO_VIOLATION). Returns how many were accepted. */
    int dismissOpenCoordinationCases(Account moderator) throws Exception {
        int dismissed = 0;
        for (var c : cases.findAll()) {
            if (c.getOrigin() != CaseOrigin.COORDINATION || c.getStatus() != CaseStatus.OPEN) continue;
            int status = api.postAs(moderator, "/api/moderation/cases/" + c.getPublicId() + "/actions",
                    Map.of("action", "DISMISS", "reason", "NO_VIOLATION")).andReturn().getResponse().getStatus();
            if (status == 200) dismissed++;
        }
        return dismissed;
    }

    /**
     * Registers through the API, then sets the account's creation time relative to the VIRTUAL clock. The server takes
     * created_at from the real system clock (User.onCreate), so without this an account made after the clock moved
     * forward would look older than it is and skip its probation.
     */
    private Account account(Duration age) throws Exception {
        Account account = api.account();
        LocalDateTime created = now().minus(age);
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.valueOf(created), account.id());
        return account;
    }

    // ---- Writes (each one is a real API call; attacker writes are counted) ------------------------------------------

    int report(Account who, String category, String cell) throws Exception {
        return write(who, () -> api.report(who, category, cell));
    }

    int confirm(Account who, String category, String cell) throws Exception {
        String id = publicId(category, cell);
        return write(who, () -> api.contribute(who, id, "CONFIRM", cell));
    }

    int dispute(Account who, String category, String cell) throws Exception {
        String id = publicId(category, cell);
        return write(who, () -> api.contribute(who, id, "NO_LONGER", cell));
    }

    int flag(Account who, String category, String cell, String reason) throws Exception {
        String id = publicId(category, cell);
        return write(who, () -> api.flag(who, id, reason, cell));
    }

    int withdraw(Account who, String category, String cell) throws Exception {
        String id = publicId(category, cell);
        return write(who, () -> api.deleteAs(who, "/api/community/mine/" + id));
    }

    /** The body of the last refused call, for checking WHICH limit answered. */
    String lastRefusal;

    /** When true (default), a burst refusal is waited out like a patient script; false measures the refusal itself. */
    boolean waitOutBursts = true;

    @FunctionalInterface
    interface Call {
        ResultActions perform() throws Exception;
    }

    /** Performs a write; a refusal by the burst limit (10 per 10 min) is waited out once, as a patient script would. */
    private int write(Account who, Call call) throws Exception {
        MvcResult result = call.perform().andReturn();
        if (waitOutBursts && result.getResponse().getStatus() == 429
                && result.getResponse().getContentAsString().contains("very quickly")) {
            burstWaits += attackers.contains(who.id()) ? 1 : 0;
            clock.advance(BURST_WINDOW);
            result = call.perform().andReturn();
        }
        int status = result.getResponse().getStatus();
        if (status >= 400) lastRefusal = result.getResponse().getContentAsString();
        if (attackers.contains(who.id())) {
            if (status < 300) writesAccepted++; else writesRefused++;
        }
        return status;
    }

    // ---- Time and the clean-up job ----------------------------------------------------------------------------------

    void advance(Duration duration) {
        clock.advance(duration);
    }

    /** Waits out the publication delay, then runs the clean-up job once (as the real 15-minute job would). */
    void settle() {
        clock.advance(SETTLE);
        job();
    }

    /** The steps of CommunityPurgeJob that matter here (the bean itself is switched off in tests). */
    void job() {
        community.purge();
        moderation.maintain();
    }

    LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    // ---- What a viewer sees (through the API, like the phone) ---------------------------------------------------------

    /** The /area answer for an area, as a viewer standing there would get it (viewers rotate to stay within limits). */
    JsonNode view(String area) throws Exception {
        if (viewer == null || (!viewerAreas.contains(area) && viewerAreas.size() >= 10)) {
            viewer = api.account();
            viewerAreas.clear();
        }
        viewerAreas.add(area);
        return api.area(viewer, area);
    }

    /** The outlook of exactly this area cell (from the picture's per-cell list), or NO_PICTURE. */
    String outlook(String area) throws Exception {
        JsonNode cell = cellPicture(area);
        return cell == null ? "NO_PICTURE" : cell.get("outlook").asText();
    }

    JsonNode cellPicture(String area) throws Exception {
        JsonNode picture = view(area).get("picture");
        if (picture == null || picture.isNull()) return null;
        for (JsonNode cell : picture.get("areas")) {
            if (cell.get("cell").asText().equals(area)) return cell;
        }
        return null;
    }

    /** The /area answer exactly as [who] gets it (their own "yourContribution" included): what a prober sees. */
    JsonNode areaAs(Account who, String area) throws Exception {
        return api.area(who, area);
    }

    /** [who]'s own "your reports" list, through the API. */
    JsonNode mine(Account who) throws Exception {
        return json(api.getAs(who, "/api/community/mine").andReturn());
    }

    /** The neighbourhood picture a viewer standing in [area] gets. */
    JsonNode picture(String area) throws Exception {
        return view(area).get("picture");
    }

    /** The signal as a viewer of its area sees it, or null when it is not visible to others. */
    JsonNode visible(String category, String cell) throws Exception {
        PlaceSignal signal = signals.findByCellAndCategory(cell, SignalCategory.valueOf(category)).orElse(null);
        if (signal == null) return null;
        for (JsonNode s : view(cell.substring(0, GeoCell.AREA_PRECISION)).get("signals")) {
            if (s.get("id").asText().equals(signal.getPublicId())) return s;
        }
        return null;
    }

    // ---- What the server decided (direct checks, as a test may) --------------------------------------------------------

    boolean exists(String category, String cell) {
        return signals.findByCellAndCategory(cell, SignalCategory.valueOf(category)).isPresent();
    }

    ModerationState moderationOf(String category, String cell) {
        return signals.findByCellAndCategory(cell, SignalCategory.valueOf(category)).orElseThrow().getModeration();
    }

    boolean linked(Account a, Account b) {
        return trust.snapshot(contributions.findAll(), now()).support().linked(a.id(), b.id());
    }

    /** How many pairs among [people] Phase 7 links (one snapshot for all of them). */
    int linkedPairs(List<Account> people) {
        var support = trust.snapshot(contributions.findAll(), now()).support();
        int linked = 0;
        for (int i = 0; i < people.size(); i++) {
            for (int j = i + 1; j < people.size(); j++) {
                if (support.linked(people.get(i).id(), people.get(j).id())) linked++;
            }
        }
        return linked;
    }

    /** A read through the real API (status + body), e.g. for scraping measurements. */
    MvcResult get(Account who, String url) throws Exception {
        return api.getAs(who, url).andReturn();
    }

    JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    long openCases(CaseOrigin origin) {
        return cases.findAll().stream().filter(c -> c.getOrigin() == origin && c.getStatus() == CaseStatus.OPEN).count();
    }

    SupportSnapshot support() {
        return SupportSnapshot.of(community, trust, signals, now());
    }

    /**
     * Phase 9D: the pictures of these area cells under a given PRODUCTION rule set (rules-1.1 is what viewers get;
     * rules-2 is the SHADOW candidate), from exactly the visible set and people linkage the server would use.
     */
    List<CellPicture> pictures(Model model, List<String> areas, Map<String, CellPatterns> patterns) {
        VisibleSet set = community.visibleSetIn(areas, now());
        return AreaIntelligencePolicy.cells(model, areas, AreaIntelligenceService.evidence(set.signals()), patterns,
                set.people());
    }

    String outlookUnder(Model model, String area, CellPatterns patterns) {
        return pictures(model, List.of(area), Map.of(area, patterns)).get(0).outlook().name();
    }

    /** Phase 9D: one run of the production rules-2 shadow comparison (SHADOW, the shipped default). */
    AreaModelShadow.Comparison shadow() {
        return shadow.run(now());
    }

    /** What the PRODUCTION coord-1 rules find right now (Phase 9C). */
    List<Group> coordinationGroups() {
        return coordination.groups(now());
    }

    /** One run of the production coordination check in the configured mode (SHADOW in these tests). */
    CoordinationCheck.Run coordinationRun() {
        return coordination.run(now());
    }

    /** Signal ids are random UUIDs; an attacker gets them from one area look-up. The harness reads them directly. */
    private String publicId(String category, String cell) {
        return signals.findByCellAndCategory(cell, SignalCategory.valueOf(category)).map(PlaceSignal::getPublicId)
                .orElse("00000000-0000-0000-0000-000000000000");
    }

    // ---- The attacker's bill ------------------------------------------------------------------------------------------

    int attackerAccounts() {
        return attackers.size();
    }

    String effort() {
        String waits = burstWaits == 0 ? "" : ", " + burstWaits + " wait(s) forced by the burst limit";
        return attackers.size() + " account(s), " + writesAccepted + " write(s) accepted, " + writesRefused + " refused"
                + waits;
    }

    /** Virtual time since the harness was created (real seconds of the test run included, so rounded to minutes). */
    String elapsed() {
        long minutes = Duration.between(start, clock.instant()).toMinutes();
        long days = minutes / (24 * 60);
        long hours = (minutes % (24 * 60)) / 60;
        long rest = minutes % 60;
        if (days > 0) return days + " d " + hours + " h";
        if (hours > 0) return hours + " h " + rest + " min";
        return rest + " min";
    }
}
