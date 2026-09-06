package com.library.lms.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A person who can use the library system.
 *
 * <p>Unlike {@link Book} and {@link Category}, this entity was written to fit a
 * table that <b>already existed</b> and already holds rows. Every mapping below
 * was chosen to match the live {@code users} schema exactly, so Hibernate has
 * nothing to alter: same column names, same nullability, same lengths. Mapping
 * to existing data is the opposite exercise from designing a fresh table - the
 * database is the authority here, not the Java.</p>
 *
 * <p>The class is deliberately a plain entity for now. There is no repository,
 * service, controller or DTO, and no security layer - this step only teaches
 * JPA what the existing rows mean.</p>
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class User {

    /** Primary key, assigned by MySQL's AUTO_INCREMENT as on the other entities. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Login name. Required and unique.
     *
     * <p>{@code length = 255} looks generous for a username, and it is - but it
     * is what the column already is. Declaring anything shorter would not shrink
     * it, because {@code ddl-auto=update} never narrows an existing column; it
     * would only make the Java disagree with the database.</p>
     */
    @Column(nullable = false, unique = true, length = 255)
    private String username;

    /** Email address. Required and unique, so it can also identify an account. */
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    /**
     * The password <b>hash</b> - never a plaintext password.
     *
     * <p>The existing rows hold 60-character BCrypt hashes, which is exactly
     * what {@code varchar(255)} is sized for. This field must never be returned
     * from an API; there is no DTO yet precisely because there is no endpoint
     * that should expose it.</p>
     *
     * <p>{@code @ToString.Exclude} keeps it out of the generated
     * {@code toString()}. Without it, any log line printing a User - or a
     * {@link Transaction}, which holds one - would write the hash to disk, and
     * log files are copied, shared and shipped to places a password store is
     * not. A hash is not plaintext, but it is still credential material and
     * offline cracking is a real attack; it does not belong in a log.</p>
     */
    @ToString.Exclude
    @Column(nullable = false, length = 255)
    private String password;

    /** Display name. Optional - the column allows NULL, so the field does too. */
    @Column(name = "full_name", length = 255)
    private String fullName;

    /**
     * What this user is allowed to do.
     *
     * <p>{@code EnumType.STRING} stores the constant's name - "ROLE_ADMIN" -
     * rather than its position. That matters here for two reasons: the existing
     * column is a MySQL ENUM holding those exact strings, and ordinal storage
     * would silently corrupt every row if a constant were ever reordered.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /**
     * When the account was created.
     *
     * <p>The column is NOT NULL, so a value must exist by the time the row is
     * written. {@link #onCreate()} below supplies it automatically on insert,
     * which means callers never have to remember - but they may still set it
     * explicitly, and that value is respected.</p>
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Stamps {@link #createdAt} just before a new row is inserted.
     *
     * <p>{@code @PrePersist} runs once, on INSERT only - never on an update - so
     * a user's creation time cannot be rewritten by a later save.</p>
     *
     * <p>The null check matters. Assigning unconditionally would overwrite a
     * timestamp the caller had deliberately set, which would quietly corrupt
     * imported or migrated data whose real creation date is known. Setting it
     * only when absent means the entity fills the gap without ever overruling
     * an explicit value.</p>
     *
     * <p>{@code protected} because JPA is the only thing that should call it;
     * it is not part of the entity's public API.</p>
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
