package com.library.lms.entity;

/**
 * The access levels a {@link User} can hold.
 *
 * <p>The three names are fixed by the existing {@code users.role} column, which
 * is a MySQL {@code ENUM('ROLE_ADMIN','ROLE_LIBRARIAN','ROLE_MEMBER')}. They
 * must match those strings exactly, character for character, because the entity
 * stores the constant's <i>name</i> - see the {@code @Enumerated(STRING)} on
 * {@link User#getRole()}. Renaming a constant here would make existing rows
 * unreadable.</p>
 *
 * <p>The {@code ROLE_} prefix is Spring Security's convention for an authority.
 * Nothing in this project uses it yet - there is no security layer - but
 * keeping the prefix means the values will drop straight in when there is,
 * without a migration of the stored data.</p>
 */
public enum Role {

    /** Full access, including managing other users. */
    ROLE_ADMIN,

    /** Manages the catalogue and issues or returns books. */
    ROLE_LIBRARIAN,

    /** Borrows books. */
    ROLE_MEMBER
}
