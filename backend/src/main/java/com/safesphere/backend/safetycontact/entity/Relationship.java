package com.safesphere.backend.safetycontact.entity;

/** How the contact is related to the user. Stored as text in the database. */
public enum Relationship {
    FAMILY,
    PARTNER,
    FRIEND,
    COLLEAGUE,
    NEIGHBOR,
    OTHER
}
