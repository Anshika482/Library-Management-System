package com.library.lms.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One library using this system.
 *
 * <p>This is the <b>tenant</b>: the boundary that will eventually separate one
 * library's books, categories, members and loans from another's. Nothing
 * references it yet, and no existing table has changed - the type has to exist
 * before anything can point at it, and introducing it on its own keeps that
 * first move reversible.</p>
 *
 * <p>Three fields, and the shortness is deliberate. An address, a phone number
 * and an active flag are all easy to imagine wanting, and none of them is read
 * by anything that exists: there is no library profile to display and no way to
 * close a library, since the foreign keys arriving in later steps will prevent
 * one from being deleted while it holds data. Fields added before there is a
 * caller for them are fields nobody maintains.</p>
 *
 * <p><b>The name is globally unique</b>, and that is not an oversight about
 * scoping. A library is the outermost scope in this system; there is no wider
 * container for it to be unique within. Two tenants sharing a name would leave
 * an administrator with no way to tell them apart.</p>
 */
@Entity
@Table(name = "libraries")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Library {

    /** Primary key, assigned by MySQL's AUTO_INCREMENT as on every other entity. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * What this library is called. Required and unique across the system.
     *
     * <p>100 characters matches {@link Category#getName()} rather than the 255
     * used for a username: a library name is read by people, and a limit that
     * invites an essay is not a useful one.</p>
     */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /**
     * When this library was registered.
     *
     * <p>NOT NULL, so a value must exist by the time the row is written.
     * {@link #onCreate()} supplies it, which means callers never have to
     * remember.</p>
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Stamps {@link #createdAt} just before a new row is inserted.
     *
     * <p>The same shape as {@link User#onCreate()}, and for the same two
     * reasons. {@code @PrePersist} runs on INSERT only, so a later save cannot
     * rewrite a library's registration date. The null check matters as well:
     * assigning unconditionally would overwrite a timestamp a caller had
     * deliberately set, which would quietly corrupt imported or migrated rows
     * whose real creation date is known.</p>
     *
     * <p>{@code protected} because JPA is the only thing that should call it.</p>
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
