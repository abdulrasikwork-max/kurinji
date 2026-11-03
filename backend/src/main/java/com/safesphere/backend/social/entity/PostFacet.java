package com.safesphere.backend.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Phase 17G-1 (V29): one Explore tag ("Good for...") on one post. A property of the post only: no person, no time. Who may
 * see a tag is exactly who may see its post (the one visibility rule), so tags are only ever read for posts the viewer may
 * read. The codes are the closed list of {@code explore-facets-v1.tsv}; "at most 4 per post, Experiences only" is a service rule.
 */
@Entity
@Table(name = "post_facets",
        uniqueConstraints = @UniqueConstraint(name = "uk_post_facets_post_facet", columnNames = {"post_id", "facet"}))
public class PostFacet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "facet", nullable = false, updatable = false, length = 20)
    private String facet;

    /** Required by JPA. */
    protected PostFacet() {
    }

    public PostFacet(long postId, String facet) {
        this.postId = postId;
        this.facet = facet;
    }

    public Long getId() {
        return id;
    }

    public Long getPostId() {
        return postId;
    }

    public String getFacet() {
        return facet;
    }
}
