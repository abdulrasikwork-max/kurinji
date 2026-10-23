package com.safesphere.backend.account.service;

import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.config.AccountProperties;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.evidence.repository.EvidenceItemRepository;
import com.safesphere.backend.evidence.service.EvidenceStorage;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 10A.3: deletes ONE account whose grace period is over, in three steps:
 *
 *   1. check (locked): still due, no ACTIVE emergency, none ended within 24 h (else the date moves later: POSTPONED);
 *      collect the storage keys of the person's evidence files and (Phase 13B) photo files;
 *   2. delete those files (outside any transaction; a failure stops here and the job tries again next run,
 *      so no row is deleted while one of its files is still on disk);
 *   3. in one transaction: check again, delete every row the person owns, children first (the same rows the database
 *      cascades remove; written out so the test database, which has no foreign keys, behaves the same), then either
 *      delete the account row or, when the moderation audit log refers to it as the deciding moderator (V13
 *      actor_id / decided_by deliberately have no cascade), turn it into an anonymous tombstone (User.erase).
 *
 * Nothing is logged about the person (no id, name or e-mail): the job logs counts only.
 */
@Service
public class AccountEraser {

    public enum Outcome { NOT_DUE, POSTPONED, DELETED, ANONYMISED, FILES_FAILED }

    /**
     * Every (table, column) that refers to users.id, and how erasure treats it. AccountEraserCoverageTest compares this
     * with the migrations, so a future table that refers to a person cannot be forgotten here.
     */
    public static final Set<String> DELETED_REFERENCES = Set.of(
            "safety_contacts.user_id", "emergency_events.user_id", "safe_journeys.user_id",
            "evidence_custody_events.user_id", "evidence_recovery_envelopes.user_id", "evidence_recovery_failures.user_id",
            "signal_contributions.user_id", "contributor_standing.user_id", "signal_flags.user_id",
            "moderation_actions.subject_user_id", "moderation_appeals.user_id", "device_sessions.user_id",
            // Phase 13A (V20): the social identity.
            "profiles.user_id", "connections.user_low", "connections.user_high", "connection_invites.owner_id",
            "blocks.blocker_id", "blocks.blocked_id", "mutes.muter_id", "mutes.muted_id",
            // Phase 13B (V21): experiences, their photos and perspectives. Places record no person (Q12).
            "posts.author_id", "post_media.owner_id", "comments.author_id");

    /** References that keep the account row (anonymised): the moderation audit trail of decisions this person took. */
    public static final Set<String> KEPT_REFERENCES = Set.of("moderation_actions.actor_id", "moderation_appeals.decided_by");

    private static final String EMERGENCIES = "(SELECT id FROM emergency_events WHERE user_id = ?)";
    private static final String JOURNEYS = "(SELECT id FROM safe_journeys WHERE user_id = ?)";

    /** Owned rows that go in both cases, children before parents. Each statement takes the user id once per "?". */
    private static final List<String> OWNED_DATA = List.of(
            "DELETE FROM evidence_custody_events WHERE user_id = ?",
            "DELETE FROM evidence_custody_events WHERE evidence_id IN (SELECT id FROM evidence_items WHERE emergency_id IN "
                    + EMERGENCIES + ")",
            "DELETE FROM evidence_items WHERE emergency_id IN " + EMERGENCIES,
            "DELETE FROM evidence_recovery_envelopes WHERE user_id = ?",
            "DELETE FROM evidence_recovery_failures WHERE user_id = ?",
            "DELETE FROM circle_alerts WHERE emergency_id IN " + EMERGENCIES + " OR journey_id IN " + JOURNEYS,
            "DELETE FROM escalation_events WHERE emergency_id IN " + EMERGENCIES + " OR journey_id IN " + JOURNEYS,
            "DELETE FROM emergency_locations WHERE emergency_id IN " + EMERGENCIES,
            "DELETE FROM emergency_events WHERE user_id = ?",
            "DELETE FROM journey_events WHERE journey_id IN " + JOURNEYS,
            "DELETE FROM safe_journeys WHERE user_id = ?",
            "DELETE FROM safety_contacts WHERE user_id = ?",
            "DELETE FROM signal_flags WHERE user_id = ?",
            "DELETE FROM contributor_standing WHERE user_id = ?",
            // Phase 11 C1: every sign-in ends (an anonymised tombstone keeps no session either).
            "DELETE FROM device_sessions WHERE user_id = ?",
            // Phase 13A: the profile, connections in both directions, invites, and blocks/mutes by or of the person.
            "DELETE FROM connections WHERE user_low = ? OR user_high = ?",
            "DELETE FROM connection_invites WHERE owner_id = ?",
            "DELETE FROM blocks WHERE blocker_id = ? OR blocked_id = ?",
            "DELETE FROM mutes WHERE muter_id = ? OR muted_id = ?",
            // Phase 13B: replies to the person's perspectives, every perspective on their posts, their own
            // perspectives, their photos (files already deleted in step 2), then their posts. The derived table is
            // needed because MySQL cannot read the table it deletes from in a plain subquery.
            "DELETE FROM comments WHERE parent_id IN (SELECT id FROM (SELECT id FROM comments WHERE author_id = ?) AS own)",
            "DELETE FROM comments WHERE post_id IN (SELECT id FROM posts WHERE author_id = ?) AND parent_id IS NOT NULL",
            "DELETE FROM comments WHERE post_id IN (SELECT id FROM posts WHERE author_id = ?)",
            "DELETE FROM comments WHERE author_id = ?",
            "DELETE FROM post_media WHERE owner_id = ?",
            "DELETE FROM posts WHERE author_id = ?",
            "DELETE FROM profiles WHERE user_id = ?");

    /** Rows ABOUT the person in the moderation log; deleted with a deleted account (as the V13 cascades do). */
    private static final List<String> MODERATION_ABOUT = List.of(
            "DELETE FROM moderation_appeals WHERE user_id = ?",
            "DELETE FROM moderation_appeals WHERE action_id IN (SELECT id FROM moderation_actions WHERE subject_user_id = ?)",
            "DELETE FROM moderation_actions WHERE subject_user_id = ?");

    private static final Logger log = LoggerFactory.getLogger(AccountEraser.class);

    private final UserRepository users;
    private final EmergencyEventRepository emergencies;
    private final EvidenceItemRepository evidence;
    private final EvidenceStorage storage;
    private final CommunityService community;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AccountProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final MediaStorage media;
    private final SecureRandom random = new SecureRandom();

    public AccountEraser(UserRepository users, EmergencyEventRepository emergencies, EvidenceItemRepository evidence,
                         EvidenceStorage storage, CommunityService community, JdbcTemplate jdbc,
                         PasswordEncoder passwordEncoder, AccountProperties properties, Clock clock,
                         PlatformTransactionManager transactions, MediaStorage media) {
        this.media = media;
        this.users = users;
        this.emergencies = emergencies;
        this.evidence = evidence;
        this.storage = storage;
        this.community = community;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    public Outcome eraseIfDue(long userId) {
        Check check = tx.execute(status -> check(userId));
        if (check.outcome() != null) return check.outcome();
        for (String key : check.storageKeys()) {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.warn("Account deletion: an evidence file could not be removed ({}); trying again next run",
                        e.getClass().getSimpleName());
                return Outcome.FILES_FAILED;
            }
        }
        for (String key : check.mediaKeys()) {
            try {
                media.delete(key);
            } catch (RuntimeException e) {
                log.warn("Account deletion: a photo file could not be removed ({}); trying again next run",
                        e.getClass().getSimpleName());
                return Outcome.FILES_FAILED;
            }
        }
        return tx.execute(status -> erase(userId));
    }

    private record Check(Outcome outcome, List<String> storageKeys, List<String> mediaKeys) {
    }

    private Check check(long userId) {
        User user = users.findByIdForUpdate(userId).orElse(null);
        Outcome blocked = blocked(user);
        if (blocked != null) return new Check(blocked, List.of(), List.of());
        // Phase 13B: every photo the person uploaded (attached to their posts or not); a photo is always its owner's.
        List<String> mediaKeys = jdbc.queryForList("SELECT storage_key FROM post_media WHERE owner_id = ?", String.class,
                userId);
        return new Check(null, evidence.storageKeysOf(userId), mediaKeys);
    }

    private Outcome erase(long userId) {
        User user = users.findByIdForUpdate(userId).orElse(null);
        Outcome blocked = blocked(user);
        if (blocked != null) return blocked; // an SOS started between the two steps: the account stays
        community.eraseContributor(userId);
        for (String statement : OWNED_DATA) run(statement, userId);
        boolean decidedSomething = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM moderation_actions WHERE actor_id = ?)"
                        + " + (SELECT COUNT(*) FROM moderation_appeals WHERE decided_by = ?)",
                Long.class, userId, userId) > 0;
        if (decidedSomething) {
            byte[] secret = new byte[32];
            random.nextBytes(secret);
            user.erase(passwordEncoder.encode(Base64.getEncoder().encodeToString(secret)), now());
            users.saveAndFlush(user);
            return Outcome.ANONYMISED;
        }
        for (String statement : MODERATION_ABOUT) run(statement, userId);
        users.delete(user);
        users.flush();
        return Outcome.DELETED;
    }

    /** Null when the account may be deleted now; otherwise why not (and a later date when an emergency is recent). */
    private Outcome blocked(User user) {
        LocalDateTime now = now();
        if (user == null || user.isErased() || user.getDeletionDueAt() == null || user.getDeletionDueAt().isAfter(now)) {
            return Outcome.NOT_DUE;
        }
        if (emergencies.existsByUserIdAndStatus(user.getId(), EmergencyStatus.ACTIVE)) {
            user.postponeDeletion(now.plus(properties.deletionProtection()));
            users.saveAndFlush(user);
            return Outcome.POSTPONED;
        }
        LocalDateTime lastEnded = emergencies.lastEndedAt(user.getId()).orElse(null);
        if (lastEnded != null && now.isBefore(lastEnded.plus(properties.deletionProtection()))) {
            user.postponeDeletion(lastEnded.plus(properties.deletionProtection()));
            users.saveAndFlush(user);
            return Outcome.POSTPONED;
        }
        return null;
    }

    private void run(String statement, long userId) {
        int placeholders = (int) statement.chars().filter(c -> c == '?').count();
        Object[] arguments = new Object[placeholders];
        java.util.Arrays.fill(arguments, userId);
        jdbc.update(statement, arguments);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
