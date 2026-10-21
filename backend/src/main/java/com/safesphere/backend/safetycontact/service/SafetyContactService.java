package com.safesphere.backend.safetycontact.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.safetycontact.dto.SafetyContactRequest;
import com.safesphere.backend.safetycontact.dto.SafetyContactResponse;
import com.safesphere.backend.safetycontact.entity.SafetyContact;
import com.safesphere.backend.safetycontact.repository.SafetyContactRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rules of the Safety Circle. Every method receives the owner's id (taken from the JWT by the
 * controller) and only ever touches rows with that user_id.
 *
 * Rules: at most MAX_CONTACTS people; one phone number once per user; at most one primary contact;
 * a disabled contact cannot be primary.
 */
@Service
public class SafetyContactService {

    static final int MAX_CONTACTS = 10;
    private static final String NOT_FOUND = "Safety contact not found";
    private static final String DUPLICATE_PHONE = "This phone number is already in your Safety Circle";

    private final SafetyContactRepository repository;

    public SafetyContactService(SafetyContactRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<SafetyContactResponse> list(long userId) {
        return repository.findByUserIdOrderByPrimaryContactDescPriorityAscNameAsc(userId).stream()
                .map(SafetyContactResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public SafetyContactResponse get(long userId, long contactId) {
        return SafetyContactResponse.from(findOwned(userId, contactId));
    }

    @Transactional
    public SafetyContactResponse create(long userId, SafetyContactRequest request) {
        if (repository.countByUserId(userId) >= MAX_CONTACTS) {
            throw new ConflictException("Your Safety Circle is full (maximum " + MAX_CONTACTS + " people)");
        }
        String phone = PhoneNumbers.normalize(request.phoneNumber());
        if (repository.existsByUserIdAndPhoneNumber(userId, phone)) {
            throw new ConflictException(DUPLICATE_PHONE);
        }

        boolean enabled = isEnabled(request);
        boolean primary = isPrimary(request, enabled);
        if (primary) {
            repository.clearPrimary(userId); // free the "primary" slot BEFORE inserting the new one
        }

        SafetyContact contact = new SafetyContact(userId);
        contact.apply(request.name().trim(), phone, cleanEmail(request.email()),
                request.relationship(), request.priority(), primary, enabled);
        return SafetyContactResponse.from(save(contact));
    }

    @Transactional
    public SafetyContactResponse update(long userId, long contactId, SafetyContactRequest request) {
        SafetyContact contact = findOwned(userId, contactId);

        String phone = PhoneNumbers.normalize(request.phoneNumber());
        if (repository.existsByUserIdAndPhoneNumberAndIdNot(userId, phone, contactId)) {
            throw new ConflictException(DUPLICATE_PHONE);
        }

        boolean enabled = isEnabled(request);
        boolean primary = isPrimary(request, enabled);
        if (primary) {
            repository.clearPrimaryExcept(userId, contactId);
        }

        contact.apply(request.name().trim(), phone, cleanEmail(request.email()),
                request.relationship(), request.priority(), primary, enabled);
        return SafetyContactResponse.from(save(contact));
    }

    @Transactional
    public void delete(long userId, long contactId) {
        repository.delete(findOwned(userId, contactId));
    }

    /** Loads a contact ONLY if it belongs to this user; someone else's contact looks like "not found". */
    private SafetyContact findOwned(long userId, long contactId) {
        return repository.findByIdAndUserId(contactId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    private SafetyContact save(SafetyContact contact) {
        try {
            // Flush now so a clash with a database rule shows up here, not later at commit.
            return repository.saveAndFlush(contact);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("The contact could not be saved. Please try again");
        }
    }

    private static boolean isEnabled(SafetyContactRequest request) {
        return request.enabled() == null || request.enabled();
    }

    /** A disabled contact can never be primary. */
    private static boolean isPrimary(SafetyContactRequest request, boolean enabled) {
        return enabled && Boolean.TRUE.equals(request.primary());
    }

    private static String cleanEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim();
    }
}
