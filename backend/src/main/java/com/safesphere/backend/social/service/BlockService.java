package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.social.dto.ProfileDtos.PersonSummary;
import com.safesphere.backend.social.entity.Block;
import com.safesphere.backend.social.entity.Mute;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.repository.BlockRepository;
import com.safesphere.backend.social.repository.ConnectionRepository;
import com.safesphere.backend.social.repository.MuteRepository;
import com.safesphere.backend.social.repository.ProfileRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 13A: blocks and mutes, both private to their owner and never told to the other person.
 *
 * A block makes the two invisible to each other in both directions (the one Visibility rule) and, in the same
 * transaction, removes their connection and any request between them, whoever sent it. Blocking is a protective act, so
 * it works on anyone whose profile id the caller has, even someone who already blocked them. A mute only stops the
 * caller seeing someone's content (from 13B on); the muted person is not affected.
 */
@Service
public class BlockService {

    static final String SELF_BLOCK = "You cannot block yourself";
    static final String SELF_MUTE = "You cannot mute yourself";
    static final String NOT_FOUND = "Person not found";

    private final BlockRepository blocks;
    private final MuteRepository mutes;
    private final ConnectionRepository connections;
    private final ProfileRepository profiles;
    private final SocialAccess access;
    private final Clock clock;

    public BlockService(BlockRepository blocks, MuteRepository mutes, ConnectionRepository connections,
                        ProfileRepository profiles, SocialAccess access, Clock clock) {
        this.blocks = blocks;
        this.mutes = mutes;
        this.connections = connections;
        this.profiles = profiles;
        this.access = access;
        this.clock = clock;
    }

    @Transactional
    public void block(long userId, String profileId) {
        access.requireMember(userId);
        long otherId = target(profileId);
        if (otherId == userId) throw new InvalidRequestException(SELF_BLOCK);
        connections.findPairForUpdate(Math.min(userId, otherId), Math.max(userId, otherId))
                .ifPresent(connections::delete);
        if (blocks.findByBlockerIdAndBlockedId(userId, otherId).isEmpty()) {
            try {
                blocks.saveAndFlush(new Block(userId, otherId, now()));
            } catch (DataIntegrityViolationException e) {
                // The same block arrived twice at once: it exists, which is what was asked.
            }
        }
    }

    @Transactional
    public void unblock(long userId, String profileId) {
        access.requireMember(userId);
        long otherId = target(profileId);
        Block block = blocks.findByBlockerIdAndBlockedId(userId, otherId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        blocks.delete(block);
    }

    @Transactional(readOnly = true)
    public List<PersonSummary> blocked(long userId) {
        access.requireMember(userId);
        return summaries(blocks.findByBlockerIdOrderByCreatedAtDesc(userId).stream().map(Block::getBlockedId).toList());
    }

    @Transactional
    public void mute(long userId, String profileId) {
        access.requireMember(userId);
        long otherId = target(profileId);
        if (otherId == userId) throw new InvalidRequestException(SELF_MUTE);
        if (mutes.findByMuterIdAndMutedId(userId, otherId).isEmpty()) {
            try {
                mutes.saveAndFlush(new Mute(userId, otherId, now()));
            } catch (DataIntegrityViolationException e) {
                // Arrived twice at once: it exists.
            }
        }
    }

    @Transactional
    public void unmute(long userId, String profileId) {
        access.requireMember(userId);
        long otherId = target(profileId);
        Mute mute = mutes.findByMuterIdAndMutedId(userId, otherId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        mutes.delete(mute);
    }

    @Transactional(readOnly = true)
    public List<PersonSummary> muted(long userId) {
        access.requireMember(userId);
        return summaries(mutes.findByMuterIdOrderByCreatedAtDesc(userId).stream().map(Mute::getMutedId).toList());
    }

    private long target(String profileId) {
        return profiles.findByPublicId(profileId).map(Profile::getUserId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    /** The caller's own list, in its order; people without a profile any more are left out. */
    private List<PersonSummary> summaries(List<Long> userIds) {
        Map<Long, Profile> byUser = profiles.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(Profile::getUserId, Function.identity()));
        return userIds.stream().map(byUser::get).filter(p -> p != null).map(ProfileService::summary).toList();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
