package com.safesphere.backend.safetycontact.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * One trusted person in a user's Safety Circle. Maps to "safety_contacts" (migration V2).
 * userId is a plain column: the database foreign key guarantees it points at a real user and
 * deletes the contact when the user is deleted. Timestamps hold UTC.
 */
@Entity
@Table(name = "safety_contacts")
public class SafetyContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Relationship relationship;

    @Column(nullable = false)
    private int priority;

    @Column(name = "is_primary", nullable = false)
    private boolean primaryContact;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Required by JPA. */
    protected SafetyContact() {
    }

    public SafetyContact(Long userId) {
        this.userId = userId;
    }

    /** Copies the editable fields in one place, so create and update cannot drift apart. */
    public void apply(String name, String phoneNumber, String email, Relationship relationship,
                      int priority, boolean primaryContact, boolean enabled) {
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.email = email;
        this.relationship = relationship;
        this.priority = priority;
        this.primaryContact = primaryContact;
        this.enabled = enabled;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getEmail() {
        return email;
    }

    public Relationship getRelationship() {
        return relationship;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isPrimaryContact() {
        return primaryContact;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
