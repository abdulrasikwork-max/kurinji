package com.safesphere.backend.safetycontact.service;

import com.safesphere.backend.auth.phone.PhoneNumbers;
import com.safesphere.backend.common.event.Tell;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.safetycontact.entity.CircleLink;
import com.safesphere.backend.safetycontact.entity.CircleLinkMute;
import com.safesphere.backend.safetycontact.entity.SafetyContact;
import com.safesphere.backend.safetycontact.repository.CircleLinkMuteRepository;
import com.safesphere.backend.safetycontact.repository.CircleLinkRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * N7 (D36 = N-128): "Also in the IRL app". When a person (the owner) saves a contact whose number is a verified IRL account
 * (the member), that member alone gets a request; the owner learns NOTHING until the member accepts (then the contact row
 * says so). A changed number or a removed contact ends the link; either side can end it; "Don't ask again" holds for that
 * owner. An accepted member also gets the owner's SOS in the app, after the text (AlertQueue). Safety: never needs a
 * community membership, never touched by a social restriction.
 */
@Service
public class CircleLinkService {

    /** Requests one owner can cause per 24 h (adding many numbers must not become a way to ping people). */
    static final int REQUESTS_PER_DAY = 10;

    public record Request(String id, String ownerName) {
    }

    private final CircleLinkRepository links;
    private final CircleLinkMuteRepository mutes;
    private final UserRepository users;
    private final com.safesphere.backend.safetycontact.repository.SafetyContactRepository contacts;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final java.util.Map<Long, java.util.Deque<LocalDateTime>> requestsMade = new java.util.HashMap<>();

    public CircleLinkService(CircleLinkRepository links, CircleLinkMuteRepository mutes, UserRepository users,
                             com.safesphere.backend.safetycontact.repository.SafetyContactRepository contacts,
                             ApplicationEventPublisher events, Clock clock) {
        this.contacts = contacts;
        this.links = links;
        this.mutes = mutes;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    /** Called after a contact was created or changed (same transaction). */
    @Transactional
    public void contactSaved(long ownerId, SafetyContact contact) {
        String number = PhoneNumbers.normalize(contact.getPhoneNumber());
        Optional<CircleLink> existing = links.findByContactId(contact.getId());
        User member = number == null ? null : users.findByPhoneE164AndPhoneVerifiedAtIsNotNull(number)
                .filter(u -> u.isEnabled() && !u.isErased()).orElse(null);
        if (existing.isPresent()) {
            if (member != null && member.getId().equals(existing.get().getMemberUserId())) return; // same person
            links.delete(existing.get());
            links.flush();
        }
        if (member == null || member.getId() == ownerId) return;
        if (mutes.existsById(new CircleLinkMute.Key(member.getId(), ownerId))) return;
        LocalDateTime now = now();
        if (!takeRequest(ownerId, now)) return; // silently: the owner never learns whether a number has IRL
        CircleLink link = links.saveAndFlush(new CircleLink(contact.getId(), ownerId, member.getId(), now));
        events.publishEvent(Tell.to(Tell.Kind.CIRCLE_REQUEST, member.getId(), link.getPublicId()));
    }

    /** Called before a contact is deleted (the database cascade does the same; the test database has none). */
    @Transactional
    public void contactDeleted(long contactId) {
        links.deleteByContactId(contactId);
    }

    /** The owner's contacts whose member accepted (only these show "Also in the IRL app"). */
    @Transactional(readOnly = true)
    public Set<Long> acceptedContactIds(long ownerId) {
        return links.findByOwnerUserIdAndStatus(ownerId, CircleLink.Status.ACCEPTED).stream().filter(this::live)
                .map(CircleLink::getContactId).collect(Collectors.toSet());
    }

    /** The member who accepted a contact's link, if any (for the SOS push). */
    @Transactional(readOnly = true)
    public Optional<Long> acceptedMemberOf(long contactId) {
        return links.findByContactId(contactId).filter(l -> l.getStatus() == CircleLink.Status.ACCEPTED).filter(this::live)
                .map(CircleLink::getMemberUserId);
    }

    /** Is [memberId] an accepted member of [ownerId]'s circle? (The member's view of an SOS.) */
    @Transactional(readOnly = true)
    public boolean isAcceptedMember(long memberId, long ownerId) {
        return links.findByOwnerUserIdAndStatus(ownerId, CircleLink.Status.ACCEPTED).stream()
                .anyMatch(l -> l.getMemberUserId() == memberId && live(l));
    }

    @Transactional(readOnly = true)
    public List<Request> requests(long memberId) {
        return views(links.findByMemberUserIdAndStatusOrderByCreatedAtDesc(memberId, CircleLink.Status.PENDING));
    }

    @Transactional(readOnly = true)
    public List<Request> circlesIn(long memberId) {
        return views(links.findByMemberUserIdAndStatusOrderByCreatedAtDesc(memberId, CircleLink.Status.ACCEPTED));
    }

    @Transactional
    public void accept(long memberId, String id) {
        CircleLink link = own(memberId, id);
        if (link.getStatus() == CircleLink.Status.PENDING) link.accept(now());
    }

    /** "Not now" (the owner learns nothing) or "Don't ask again" (also nothing; later contacts make no request). */
    @Transactional
    public void decline(long memberId, String id, boolean dontAskAgain) {
        CircleLink link = own(memberId, id);
        if (dontAskAgain && !mutes.existsById(new CircleLinkMute.Key(memberId, link.getOwnerUserId()))) {
            mutes.save(new CircleLinkMute(memberId, link.getOwnerUserId(), now()));
        }
        links.delete(link);
    }

    /** The member leaves a circle they accepted (the owner's contact simply stops saying "Also in the IRL app"). */
    @Transactional
    public void leave(long memberId, String id) {
        links.delete(own(memberId, id));
    }

    private CircleLink own(long memberId, String id) {
        return links.findByPublicIdAndMemberUserId(id == null ? "" : id.trim(), memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Request not found"));
    }

    private List<Request> views(List<CircleLink> rows) {
        return rows.stream().filter(this::live).map(l -> new Request(l.getPublicId(),
                users.findById(l.getOwnerUserId()).filter(u -> !u.isErased()).map(User::getName).orElse(null)))
                .filter(r -> r.ownerName() != null).toList();
    }

    /**
     * Requests made per owner in the last 24 h, counted when made (not from the rows: deleting and re-adding a contact
     * must not reset it). In memory, like the other per-person limits; a restart forgets it.
     */
    private synchronized boolean takeRequest(long ownerId, LocalDateTime now) {
        java.util.Deque<LocalDateTime> made = requestsMade.computeIfAbsent(ownerId, k -> new java.util.ArrayDeque<>());
        while (!made.isEmpty() && made.peekFirst().isBefore(now.minusHours(24))) made.pollFirst();
        if (made.size() >= REQUESTS_PER_DAY) return false;
        made.addLast(now);
        return true;
    }

    /**
     * A link counts only while the member's verified number is still the contact's number (a member who moved to
     * another number, or an owner who changed the contact, ends it without any hook elsewhere) and the member's account
     * is usable.
     */
    private boolean live(CircleLink link) {
        SafetyContact contact = contacts.findById(link.getContactId()).orElse(null);
        if (contact == null) return false;
        String number = PhoneNumbers.normalize(contact.getPhoneNumber());
        return number != null && users.findById(link.getMemberUserId())
                .filter(u -> u.isEnabled() && !u.isErased() && u.isPhoneVerified() && number.equals(u.getPhoneE164()))
                .isPresent();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
