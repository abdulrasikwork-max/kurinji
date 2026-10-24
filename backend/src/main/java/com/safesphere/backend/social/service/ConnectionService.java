package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.SocialProperties;
import com.safesphere.backend.social.dto.ConnectionDtos.ConnectResult;
import com.safesphere.backend.social.dto.ConnectionDtos.ConnectStatus;
import com.safesphere.backend.social.dto.ConnectionDtos.InviteCreated;
import com.safesphere.backend.social.dto.ConnectionDtos.InviteItem;
import com.safesphere.backend.social.dto.ConnectionDtos.RequestItem;
import com.safesphere.backend.social.dto.ProfileDtos.PersonSummary;
import com.safesphere.backend.social.entity.Connection;
import com.safesphere.backend.social.entity.ConnectionInvite;
import com.safesphere.backend.social.entity.ConnectionStatus;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.policy.InviteCodes;
import com.safesphere.backend.social.repository.ConnectionInviteRepository;
import com.safesphere.backend.social.repository.ConnectionRepository;
import com.safesphere.backend.social.repository.ProfileRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 13A: mutual connections. One row per pair (V20). A request waits for the other person; if they had already
 * asked (or the caller declined them earlier and now asks), the two are connected at once. A decline is silent: its
 * sender keeps seeing "requested". Removing a connection is silent too. Invite codes connect at once (the inviter agreed
 * by sharing the code), work once, for 7 days, and only their hash is stored.
 *
 * Everything about another person goes through the one visibility rule: someone the caller may not see (blocked either
 * way, disabled account) is "not found", whatever the reason.
 */
@Service
public class ConnectionService {

    static final String NEEDS_PROFILE = "Make your profile first";
    static final String SELF = "You cannot connect with yourself";
    static final String ALREADY_CONNECTED = "You are already connected";
    static final String TOO_MANY_PENDING = "You have many requests waiting. Cancel some first.";
    static final String TOO_MANY_CONNECTIONS = "This would go over the most connections one person can have";
    static final String TOO_MANY_INVITES = "You already have the most active invite codes. Revoke one first.";
    static final String REQUEST_NOT_FOUND = "Request not found";
    static final String CONNECTION_NOT_FOUND = "Connection not found";
    static final String INVITE_NOT_FOUND = "Invite not found";
    static final String CODE_DOES_NOT_WORK = "This invite code does not work";
    static final String CHANGED_AT_THE_SAME_TIME = "Someone else changed this at the same time. Please reload.";

    private final ConnectionRepository connections;
    private final ConnectionInviteRepository invites;
    private final ProfileRepository profiles;
    private final ProfileService profileService;
    private final Relationships relationships;
    private final SocialAccess access;
    private final SocialLimits limits;
    private final SocialProperties properties;
    private final Clock clock;

    public ConnectionService(ConnectionRepository connections, ConnectionInviteRepository invites,
                             ProfileRepository profiles, ProfileService profileService, Relationships relationships,
                             SocialAccess access, SocialLimits limits, SocialProperties properties, Clock clock) {
        this.connections = connections;
        this.invites = invites;
        this.profiles = profiles;
        this.profileService = profileService;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.properties = properties;
        this.clock = clock;
    }

    // ---- requests -----------------------------------------------------------------------------------------------

    @Transactional
    public ConnectResult request(long userId, String profileId) {
        requireMemberWithProfile(userId);
        Profile target = profileService.requireVisible(userId, profileId);
        long targetId = target.getUserId();
        if (targetId == userId) throw new InvalidRequestException(SELF);
        limits.request(userId);

        LocalDateTime now = now();
        Connection existing = lockPair(userId, targetId);
        if (existing != null) {
            if (existing.getStatus() == ConnectionStatus.ACCEPTED) throw new ConflictException(ALREADY_CONNECTED);
            if (existing.getRequesterId() == userId) {
                return new ConnectResult(ConnectStatus.REQUESTED, ProfileService.summary(target)); // asked before
            }
            // The other person asked first (waiting, or declined by the caller earlier): asking back connects.
            requireRoomForConnection(userId, targetId);
            existing.accept(now);
            return new ConnectResult(ConnectStatus.CONNECTED, ProfileService.summary(target));
        }
        if (connections.countOutgoing(userId) >= properties.maxPendingOutgoing()) {
            throw new ConflictException(TOO_MANY_PENDING);
        }
        saveNew(Connection.request(userId, targetId, now));
        return new ConnectResult(ConnectStatus.REQUESTED, ProfileService.summary(target));
    }

    @Transactional(readOnly = true)
    public List<RequestItem> incoming(long userId) {
        access.requireMember(userId);
        return items(userId, connections.findIncoming(userId), c -> c.getRequesterId());
    }

    @Transactional(readOnly = true)
    public List<RequestItem> outgoing(long userId) {
        access.requireMember(userId);
        return items(userId, connections.findOutgoing(userId), Connection::addresseeId);
    }

    @Transactional
    public ConnectResult accept(long userId, String requestId) {
        requireMemberWithProfile(userId);
        Connection request = incomingRequest(userId, requestId);
        long otherId = request.getRequesterId();
        requireRoomForConnection(userId, otherId);
        request.accept(now());
        return new ConnectResult(ConnectStatus.CONNECTED, summaryOf(otherId));
    }

    @Transactional
    public void decline(long userId, String requestId) {
        access.requireMember(userId);
        incomingRequest(userId, requestId).decline(now());
    }

    /** The caller withdraws a request they sent (waiting or silently declined). */
    @Transactional
    public void cancel(long userId, String requestId) {
        access.requireMember(userId);
        Connection request = connections.findByPublicIdForUpdate(requestId)
                .filter(c -> c.getRequesterId() == userId && c.getStatus() != ConnectionStatus.ACCEPTED)
                .orElseThrow(() -> new ResourceNotFoundException(REQUEST_NOT_FOUND));
        connections.delete(request);
    }

    // ---- connections --------------------------------------------------------------------------------------------

    /** The caller's own connections, most recent first. Nobody else can list them. */
    @Transactional(readOnly = true)
    public List<PersonSummary> list(long userId) {
        access.requireMember(userId);
        List<Long> others = connections.findInvolving(userId, ConnectionStatus.ACCEPTED).stream()
                .map(c -> c.otherThan(userId)).toList();
        Map<Long, Profile> byUser = profiles.findByUserIdIn(others).stream()
                .collect(Collectors.toMap(Profile::getUserId, Function.identity()));
        List<PersonSummary> out = new ArrayList<>();
        for (Long other : others) {
            Profile profile = byUser.get(other);
            if (profile != null && relationships.canSeeProfile(userId, other)) out.add(ProfileService.summary(profile));
        }
        return out;
    }

    /** Silent: the other person is not told. */
    @Transactional
    public void remove(long userId, String profileId) {
        access.requireMember(userId);
        Profile other = profiles.findByPublicId(profileId).orElseThrow(() -> new ResourceNotFoundException(CONNECTION_NOT_FOUND));
        Connection connection = lockPair(userId, other.getUserId());
        if (connection == null || connection.getStatus() != ConnectionStatus.ACCEPTED || other.getUserId() == userId) {
            throw new ResourceNotFoundException(CONNECTION_NOT_FOUND);
        }
        connections.delete(connection);
    }

    // ---- invite codes -------------------------------------------------------------------------------------------

    @Transactional
    public InviteCreated createInvite(long userId) {
        requireMemberWithProfile(userId);
        LocalDateTime now = now();
        if (invites.findActive(userId, now).size() >= properties.maxActiveInvites()) {
            throw new ConflictException(TOO_MANY_INVITES);
        }
        limits.invite(userId);
        String code = InviteCodes.newCode();
        ConnectionInvite invite = invites.saveAndFlush(new ConnectionInvite(userId, InviteCodes.hash(code), now,
                now.plus(properties.inviteLifetime())));
        return new InviteCreated(invite.getPublicId(), code, invite.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public List<InviteItem> activeInvites(long userId) {
        access.requireMember(userId);
        return invites.findActive(userId, now()).stream()
                .map(i -> new InviteItem(i.getPublicId(), i.getExpiresAt().toInstant(ZoneOffset.UTC))).toList();
    }

    @Transactional
    public void revokeInvite(long userId, String inviteId) {
        access.requireMember(userId);
        ConnectionInvite invite = invites.findByPublicIdAndOwnerId(inviteId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(INVITE_NOT_FOUND));
        invites.delete(invite);
    }

    /**
     * Connects the caller with the code's owner. Unknown, used, expired, own, and blocked-either-way codes all answer
     * the same 404, so trying codes reveals nothing about them.
     */
    @Transactional
    public ConnectResult redeem(long userId, String rawCode) {
        requireMemberWithProfile(userId);
        limits.redeem(userId);
        String code = InviteCodes.normalize(rawCode);
        if (code == null) throw new ResourceNotFoundException(CODE_DOES_NOT_WORK);
        LocalDateTime now = now();
        ConnectionInvite invite = invites.findByCodeHashForUpdate(InviteCodes.hash(code))
                .filter(i -> i.isUsable(now) && i.getOwnerId() != userId)
                .filter(i -> relationships.canSeeProfile(userId, i.getOwnerId()))
                .orElseThrow(() -> new ResourceNotFoundException(CODE_DOES_NOT_WORK));
        long inviterId = invite.getOwnerId();
        Profile inviter = profiles.findByUserId(inviterId)
                .orElseThrow(() -> new ResourceNotFoundException(CODE_DOES_NOT_WORK));

        Connection existing = lockPair(userId, inviterId);
        if (existing != null && existing.getStatus() == ConnectionStatus.ACCEPTED) {
            throw new ConflictException(ALREADY_CONNECTED);
        }
        requireRoomForConnection(userId, inviterId);
        if (existing != null) {
            existing.accept(now);
        } else {
            saveNew(Connection.fromInvite(inviterId, userId, now));
        }
        invite.markUsed(now);
        return new ConnectResult(ConnectStatus.CONNECTED, ProfileService.summary(inviter));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Phase 13D: new connections (request, accept, invite, redeem) also stop while a suspension is in force (M9). */
    private void requireMemberWithProfile(long userId) {
        access.requireCanConnect(userId);
        if (profiles.findByUserId(userId).isEmpty()) throw new ConflictException(NEEDS_PROFILE);
    }

    /** A request waiting for the caller's answer, from someone the caller may see. */
    private Connection incomingRequest(long userId, String requestId) {
        return connections.findByPublicIdForUpdate(requestId)
                .filter(c -> c.involves(userId) && c.getRequesterId() != userId)
                .filter(c -> c.getStatus() == ConnectionStatus.PENDING)
                .filter(c -> relationships.canSeeProfile(userId, c.getRequesterId()))
                .orElseThrow(() -> new ResourceNotFoundException(REQUEST_NOT_FOUND));
    }

    private void requireRoomForConnection(long a, long b) {
        if (connections.countAccepted(a) >= properties.maxConnections()
                || connections.countAccepted(b) >= properties.maxConnections()) {
            throw new ConflictException(TOO_MANY_CONNECTIONS);
        }
    }

    private Connection lockPair(long a, long b) {
        return connections.findPairForUpdate(Math.min(a, b), Math.max(a, b)).orElse(null);
    }

    /** The pair's unique index turns a simultaneous second row into a clear 409 (the caller reloads). */
    private void saveNew(Connection connection) {
        try {
            connections.saveAndFlush(connection);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(CHANGED_AT_THE_SAME_TIME);
        }
    }

    private List<RequestItem> items(long userId, List<Connection> rows, Function<Connection, Long> otherOf) {
        List<Long> others = rows.stream().map(otherOf).toList();
        Map<Long, Profile> byUser = profiles.findByUserIdIn(others).stream()
                .collect(Collectors.toMap(Profile::getUserId, Function.identity()));
        List<RequestItem> out = new ArrayList<>();
        for (Connection row : rows) {
            long other = otherOf.apply(row);
            Profile profile = byUser.get(other);
            if (profile != null && relationships.canSeeProfile(userId, other)) {
                out.add(new RequestItem(row.getPublicId(), ProfileService.summary(profile)));
            }
        }
        return out;
    }

    private PersonSummary summaryOf(long userId) {
        return profiles.findByUserId(userId).map(ProfileService::summary)
                .orElseThrow(() -> new ResourceNotFoundException(REQUEST_NOT_FOUND));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
