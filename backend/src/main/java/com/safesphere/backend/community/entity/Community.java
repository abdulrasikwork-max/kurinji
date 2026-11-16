package com.safesphere.backend.community.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * Phase N3 (V34): a community IRL recognises. Nobody owns it; it has no channels and no roles of its own. Rows are
 * written only by migrations (MIET in V34); no endpoint creates one ("never implement early": other communities).
 */
@Entity
@Table(name = "communities", uniqueConstraints = @UniqueConstraint(name = "uk_communities_code", columnNames = "code"))
public class Community {

    /** The first (and at launch the only) community. */
    public static final String MIET = "MIET";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16, updatable = false)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Community() {
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
