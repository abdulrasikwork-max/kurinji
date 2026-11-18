package com.safesphere.backend.account.service;

import com.safesphere.backend.community.service.IdPhotoStore;
import com.safesphere.backend.config.AccountProperties;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.repository.EmergencyEventRepository;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 10A.3: deletes ONE account whose grace period is over, in three steps:
 *
 *   1. check (locked): still due, no ACTIVE emergency, none ended within 24 h (else the date moves later: POSTPONED);
 *      collect the storage keys of the person's (Phase 13B) photo files;
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
            "moderation_actions.subject_user_id", "moderation_appeals.user_id", "device_sessions.user_id",
            // Phase 13A (V20): the social identity.
            "profiles.user_id", "connections.user_low", "connections.user_high", "connection_invites.owner_id",
            "blocks.blocker_id", "blocks.blocked_id", "mutes.muter_id", "mutes.muted_id",
            // Phase N4 (V35): the person's moments, their photos (attached or not), and the daily questions they wrote
            // as an admin (the question stays; its writer is cleared, as the database's ON DELETE SET NULL does).
            "moments.author_id", "moment_media.owner_id", "daily_questions.created_by",
            // Phase N5 (V36): their Same and Say more, their asks and plan places, the private replies they started, every
            // message they wrote, their settings and their Replies marker.
            "moment_signals.user_id", "plan_people.user_id", "conversations.replier_id", "messages.author_id",
            "social_settings.user_id", "replies_opened.user_id",
            // Phase 13D (V23): reports by or about the person, cases about them, their strikes and pause.
            "content_reports.reporter_id", "content_reports.person_id", "content_cases.person_id",
            "social_strikes.user_id", "social_standing.user_id",
            // Phase N2 (V33): a confirmation code in progress (V25 and V26 code tables were dropped by V33).
            "phone_codes.user_id",
            // Phase N3 (V34): the person's membership (its roll number becomes free; a ban stays in `bans`), their
            // verification request and its ID photo file, the community log rows ABOUT them, and a claim they held on
            // someone else's request (cleared, as the database's ON DELETE SET NULL does).
            "memberships.user_id", "verification_requests.user_id", "verification_requests.claimed_by",
            "community_actions.subject_user_id",
            // Phase N6 (V37): the server's Safety Circle texts (and "Test my alerts") of the person, their alert choice, and
            // their name on stop-list entries they added as an admin (the entry stays; cleared, as ON DELETE SET NULL does).
            "circle_alerts.user_id", "alert_settings.user_id", "alert_stops.added_by",
            // Phase N7 (V38): the person's phones and their push queue, their notification switches, the Safety Circle
            // links where they are the owner or the member, and their "Don't ask again" choices in both directions.
            "push_devices.user_id", "push_settings.user_id", "push_messages.user_id", "circle_links.owner_user_id",
            "circle_links.member_user_id", "circle_link_mutes.member_user_id", "circle_link_mutes.owner_user_id");

    /** References that keep the account row (anonymised): the moderation audit trail of decisions this person took. */
    public static final Set<String> KEPT_REFERENCES = Set.of("moderation_actions.actor_id", "moderation_appeals.decided_by",
            // Phase N3 (V34): who verified someone, who banned or lifted a ban, who took a community decision.
            "memberships.verified_by", "bans.banned_by", "bans.lifted_by", "community_actions.actor_id");

    private static final String EMERGENCIES = "(SELECT id FROM emergency_events WHERE user_id = ?)";
    private static final String JOURNEYS = "(SELECT id FROM safe_journeys WHERE user_id = ?)";

    /** Owned rows that go in both cases, children before parents. Each statement takes the user id once per "?". */
    /** Phase N5: the messages that go with a person: theirs, and those of the conversations that go with them. */
    static final String ERASED_MESSAGES = "SELECT id FROM messages WHERE author_id = ? OR conversation_id IN"
            + " (SELECT id FROM conversations WHERE replier_id = ? OR moment_id IN (SELECT id FROM moments"
            + " WHERE author_id = ?))";

    private static final List<String> OWNED_DATA = List.of(
            // Phase N7: the push queue and phones (before their sign-ins), the switches, the circle links (before the
            // contacts they belong to) and the mutes, in both directions.
            "DELETE FROM push_messages WHERE user_id = ? OR device_id IN (SELECT id FROM push_devices WHERE user_id = ?)",
            "DELETE FROM push_devices WHERE user_id = ?",
            "DELETE FROM push_settings WHERE user_id = ?",
            "DELETE FROM circle_links WHERE owner_user_id = ? OR member_user_id = ?",
            "DELETE FROM circle_link_mutes WHERE member_user_id = ? OR owner_user_id = ?",
            "DELETE FROM circle_alerts WHERE user_id = ? OR emergency_id IN " + EMERGENCIES + " OR journey_id IN " + JOURNEYS,
            "DELETE FROM escalation_events WHERE emergency_id IN " + EMERGENCIES + " OR journey_id IN " + JOURNEYS,
            "DELETE FROM emergency_locations WHERE emergency_id IN " + EMERGENCIES,
            "DELETE FROM emergency_events WHERE user_id = ?",
            "DELETE FROM journey_events WHERE journey_id IN " + JOURNEYS,
            "DELETE FROM safe_journeys WHERE user_id = ?",
            "DELETE FROM safety_contacts WHERE user_id = ?",
            // Phase N6: the alert choice; stop-list entries they added keep the number key but lose their name.
            "DELETE FROM alert_settings WHERE user_id = ?",
            "UPDATE alert_stops SET added_by = NULL WHERE added_by = ?",
            // Phase 11 C1: every sign-in ends (an anonymised tombstone keeps no session either).
            "DELETE FROM device_sessions WHERE user_id = ?",
            // Phase N2: codes in progress for the person or their number (a sign-in code names no account).
            "DELETE FROM phone_codes WHERE user_id = ? OR phone_e164 = (SELECT phone_e164 FROM users WHERE id = ?)",
            // Phase 13A: the profile, connections in both directions, invites, and blocks/mutes by or of the person.
            "DELETE FROM connections WHERE user_low = ? OR user_high = ?",
            "DELETE FROM connection_invites WHERE owner_id = ?",
            "DELETE FROM blocks WHERE blocker_id = ? OR blocked_id = ?",
            "DELETE FROM mutes WHERE muter_id = ? OR muted_id = ?",
            // Phase 13D, N4: the person's reports; every case (and its reports) about the person or their moments; the
            // automatic checks of their moments; their strikes and pause.
            "DELETE FROM content_reports WHERE reporter_id = ?",
            "DELETE FROM content_reports WHERE case_id IN (SELECT id FROM content_cases WHERE person_id = ?"
                    + " OR moment_id IN (SELECT id FROM moments WHERE author_id = ?))",
            "DELETE FROM content_cases WHERE person_id = ?"
                    + " OR moment_id IN (SELECT id FROM moments WHERE author_id = ?)",
            "DELETE FROM content_assessments WHERE moment_id IN (SELECT id FROM moments WHERE author_id = ?)",
            // Phase N5: reports, cases and checks of the messages erased below (theirs, and every message of the
            // conversations that go with them).
            "DELETE FROM content_reports WHERE message_id IN (" + ERASED_MESSAGES + ") OR case_id IN"
                    + " (SELECT id FROM content_cases WHERE message_id IN (" + ERASED_MESSAGES + "))",
            "DELETE FROM content_cases WHERE message_id IN (" + ERASED_MESSAGES + ")",
            "DELETE FROM content_assessments WHERE message_id IN (" + ERASED_MESSAGES + ")",
            "DELETE FROM social_strikes WHERE user_id = ?",
            "DELETE FROM social_standing WHERE user_id = ?",
            // Phase N4: the person's moment photos (files already deleted in step 2), their moments (live, ended and
            // kept alike), and their name on the daily questions they wrote.
            // Phase N5: what they said and what was said under their moments (children first); This happened Says of
            // others lose the link to their plans (the database's ON DELETE SET NULL).
            "DELETE FROM messages WHERE author_id = ? OR conversation_id IN (SELECT id FROM conversations"
                    + " WHERE replier_id = ? OR moment_id IN (SELECT id FROM moments WHERE author_id = ?))",
            "DELETE FROM conversations WHERE replier_id = ? OR moment_id IN (SELECT id FROM moments WHERE author_id = ?)",
            "DELETE FROM plan_people WHERE user_id = ? OR moment_id IN (SELECT id FROM moments WHERE author_id = ?)",
            "DELETE FROM moment_signals WHERE user_id = ? OR moment_id IN (SELECT id FROM moments WHERE author_id = ?)",
            "DELETE FROM social_settings WHERE user_id = ?",
            "DELETE FROM replies_opened WHERE user_id = ?",
            "UPDATE moments SET happened_of = NULL WHERE happened_of IN (SELECT id FROM (SELECT id FROM moments"
                    + " WHERE author_id = ?) own)",
            "DELETE FROM moment_media WHERE owner_id = ?"
                    + " OR moment_id IN (SELECT id FROM moments WHERE author_id = ?)",
            "DELETE FROM moments WHERE author_id = ?",
            "UPDATE daily_questions SET created_by = NULL WHERE created_by = ?",
            "DELETE FROM profiles WHERE user_id = ?",
            // Phase N3: the request (its photo file went in step 2), claims held on others' requests, the log rows about
            // the person, the membership.
            "DELETE FROM verification_requests WHERE user_id = ?",
            "UPDATE verification_requests SET claimed_by = NULL, claimed_at = NULL WHERE claimed_by = ?",
            "DELETE FROM community_actions WHERE subject_user_id = ?",
            "DELETE FROM memberships WHERE user_id = ?");

    /** Rows ABOUT the person in the moderation log; deleted with a deleted account (as the V13 cascades do). */
    private static final List<String> MODERATION_ABOUT = List.of(
            "DELETE FROM moderation_appeals WHERE user_id = ?",
            "DELETE FROM moderation_appeals WHERE action_id IN (SELECT id FROM moderation_actions WHERE subject_user_id = ?)",
            "DELETE FROM moderation_actions WHERE subject_user_id = ?");

    private static final Logger log = LoggerFactory.getLogger(AccountEraser.class);

    private final UserRepository users;
    private final EmergencyEventRepository emergencies;
    private final JdbcTemplate jdbc;
    private final AccountProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final MediaStorage media;
    private final IdPhotoStore idPhotos;

    public AccountEraser(UserRepository users, EmergencyEventRepository emergencies, JdbcTemplate jdbc,
                         AccountProperties properties, Clock clock,
                         PlatformTransactionManager transactions, MediaStorage media, IdPhotoStore idPhotos) {
        this.media = media;
        this.idPhotos = idPhotos;
        this.users = users;
        this.emergencies = emergencies;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    public Outcome eraseIfDue(long userId) {
        Check check = tx.execute(status -> check(userId));
        if (check.outcome() != null) return check.outcome();
        for (String key : check.mediaKeys()) {
            try {
                media.delete(key);
            } catch (RuntimeException e) {
                log.warn("Account deletion: a photo file could not be removed ({}); trying again next run",
                        e.getClass().getSimpleName());
                return Outcome.FILES_FAILED;
            }
        }
        for (String key : check.idPhotoKeys()) idPhotos.delete(key); // logs and leaves it to the hourly sweep on failure
        return tx.execute(status -> erase(userId));
    }

    private record Check(Outcome outcome, List<String> mediaKeys, List<String> idPhotoKeys) {
    }

    private Check check(long userId) {
        User user = users.findByIdForUpdate(userId).orElse(null);
        Outcome blocked = blocked(user);
        if (blocked != null) return new Check(blocked, List.of(), List.of());
        // Phase 17E: their profile photo (its key is on the profile).
        List<String> mediaKeys = new java.util.ArrayList<>(jdbc.queryForList(
                "SELECT photo_key FROM profiles WHERE user_id = ? AND photo_key IS NOT NULL",
                String.class, userId));
        // Phase N4: the photos of their moments, and photos uploaded for a moment that was never shared.
        mediaKeys.addAll(jdbc.queryForList("SELECT storage_key FROM moment_media WHERE owner_id = ?"
                + " OR moment_id IN (SELECT id FROM moments WHERE author_id = ?)", String.class, userId, userId));
        // Phase N3: an ID photo waiting for an admin.
        List<String> idPhotos = jdbc.queryForList(
                "SELECT photo_key FROM verification_requests WHERE user_id = ? AND photo_key IS NOT NULL", String.class, userId);
        return new Check(null, mediaKeys, idPhotos);
    }

    private Outcome erase(long userId) {
        User user = users.findByIdForUpdate(userId).orElse(null);
        Outcome blocked = blocked(user);
        if (blocked != null) return blocked; // an SOS started between the two steps: the account stays
        for (String statement : OWNED_DATA) run(statement, userId);
        boolean decidedSomething = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM moderation_actions WHERE actor_id = ?)"
                        + " + (SELECT COUNT(*) FROM moderation_appeals WHERE decided_by = ?)"
                        // Phase N3: community decisions keep their decider (an anonymous tombstone), like moderators.
                        + " + (SELECT COUNT(*) FROM memberships WHERE verified_by = ?)"
                        + " + (SELECT COUNT(*) FROM bans WHERE banned_by = ? OR lifted_by = ?)"
                        + " + (SELECT COUNT(*) FROM community_actions WHERE actor_id = ?)",
                Long.class, userId, userId, userId, userId, userId, userId) > 0;
        if (decidedSomething) {
            user.erase(now());
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
