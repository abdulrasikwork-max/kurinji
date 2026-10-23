package com.safesphere.backend.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 13B (V21): a place people write about. A name, a category and a ~150 m geohash cell; no coordinates and no
 * creator (Q12), only the UTC day it was created. One place per (cell, normalised name).
 */
@Entity
@Table(name = "places", uniqueConstraints = {
        @UniqueConstraint(name = "uk_places_public_id", columnNames = "public_id"),
        @UniqueConstraint(name = "uk_places_cell_name", columnNames = {"cell", "normalized_name"})})
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false, length = 36)
    private String publicId;

    @Column(nullable = false, updatable = false, length = 60)
    private String name;

    @Column(name = "normalized_name", nullable = false, updatable = false, length = 60)
    private String normalizedName;

    @Column(nullable = false, updatable = false, length = 20)
    private String category;

    @Column(nullable = false, updatable = false, length = 7)
    private String cell;

    @Column(name = "area_cell", nullable = false, updatable = false, length = 6)
    private String areaCell;

    @Column(name = "created_on", nullable = false, updatable = false)
    private LocalDate createdOn;

    @Column(name = "merged_into")
    private Long mergedInto;

    /** Required by JPA. */
    protected Place() {
    }

    public Place(String name, String normalizedName, String category, String cell, LocalDate createdOn) {
        this.publicId = UUID.randomUUID().toString();
        this.name = name;
        this.normalizedName = normalizedName;
        this.category = category;
        this.cell = cell;
        this.areaCell = cell.substring(0, 6);
        this.createdOn = createdOn;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getName() {
        return name;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public String getCategory() {
        return category;
    }

    public String getCell() {
        return cell;
    }

    public String getAreaCell() {
        return areaCell;
    }

    public LocalDate getCreatedOn() {
        return createdOn;
    }

    public Long getMergedInto() {
        return mergedInto;
    }
}
