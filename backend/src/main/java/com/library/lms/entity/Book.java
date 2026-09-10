package com.library.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A single book title held by the library.
 *
 * <p>This class is an <b>entity</b>: one Java object here corresponds to one row
 * in the {@code books} table. Because {@code spring.jpa.hibernate.ddl-auto=update}
 * is set in application.properties, Hibernate reads the annotations below and
 * creates (or alters) that table for us the next time the application starts -
 * we never have to write CREATE TABLE by hand.</p>
 *
 * <p><b>Copies vs. availability.</b> {@code totalCopies} is how many physical
 * copies the library owns; {@code availableCopies} is how many are currently on
 * the shelf rather than borrowed. Keeping both lets us answer "can this be
 * issued?" with a single field check. The borrowing logic that decrements
 * {@code availableCopies} belongs in the service layer, not here.</p>
 */
@Entity
@Table(
        name = "books",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_books_library_isbn",
                columnNames = {"library_id", "isbn"}))
// --- Lombok: these five annotations are replaced by real code at compile time ---
@Getter                 // a getXxx() for every field
@Setter                 // a setXxx() for every field
@ToString               // a readable toString(), handy in logs and debugging
@NoArgsConstructor      // required by JPA: Hibernate builds objects with new Book()
@AllArgsConstructor     // convenient for tests and for creating a fully populated Book
public class Book {

    /**
     * Primary key.
     *
     * <p>{@code GenerationType.IDENTITY} maps to MySQL's AUTO_INCREMENT: the
     * database assigns the number, so a brand new Book has a null id until it
     * is saved. That null is exactly how JPA tells "not yet persisted" apart
     * from "already in the table".</p>
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Row version, maintained by Hibernate for optimistic locking.
     *
     * <p>Every update now carries {@code AND version = ?} in its WHERE clause.
     * Two requests that read the same copy counts and both try to write will
     * therefore collide: the first commits and bumps the version, the second
     * matches no row and fails instead of silently overwriting. That silent
     * overwrite is precisely how one copy could be issued twice - both callers
     * read {@code availableCopies = 1}, and the second write simply replaced the
     * first rather than conflicting with it.</p>
     *
     * <p>Never assigned by hand. Hibernate sets it on insert and increments it
     * on every update; the setter exists only because Lombok generates one for
     * every field, and a test needs it to build a deliberately stale copy.</p>
     */
    @Version
    private Long version;

    /** Title of the book. Required, so the column is NOT NULL. */
    @Column(nullable = false, length = 200)
    private String title;

    /** Author's name. Required. */
    @Column(nullable = false, length = 150)
    private String author;

    /**
     * International Standard Book Number.
     *
     * <p>{@code unique = true} makes Hibernate add a UNIQUE constraint, so the
     * database itself refuses a duplicate ISBN even if the application code
     * forgets to check. 20 characters comfortably fits both ISBN-10 and
     * ISBN-13, with or without hyphens.</p>
     */
    @Column(nullable = false, length = 20)
    private String isbn;

    /**
     * The shelf this book sits on.
     *
     * <p>{@code @ManyToOne} says many books point at one category, which is
     * exactly the real-world relationship. {@code @JoinColumn} names the column
     * that holds the link: {@code category_id}, a foreign key into
     * {@code categories(id)}, replacing the old free-text {@code category}
     * column.</p>
     *
     * <p>The column stays <b>nullable</b> because a category was optional
     * before and must remain so - books that predate this change have no
     * category_id yet, and a NOT NULL constraint would make them invalid rows.</p>
     *
     * <p>Fetching is left at the {@code @ManyToOne} default, which is EAGER.
     * That is the right choice here: {@code spring.jpa.open-in-view=false} closes
     * the database session when the service returns, so a lazy category would
     * already be unreachable by the time we read its name to build the response.</p>
     */
    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    /**
     * The library that owns this copy of the title.
     *
     * <p>This is the tenant boundary for the catalogue. Once every book carries
     * one, a caller from one library will not be able to read, edit or delete
     * another library's stock, because every query will be scoped by this value
     * rather than by an id the caller supplied.</p>
     *
     * <p><b>Required.</b> The column arrived nullable because a NOT NULL column
     * with no default cannot be added to a populated table under
     * {@code STRICT_TRANS_TABLES}; the 25 existing rows were then backfilled to
     * the default library and the column tightened. Both sides now agree that
     * every book belongs to a library.</p>
     *
     * <p>{@code optional = false} as well as {@code nullable = false}: the
     * {@code @JoinColumn} setting describes the column, while {@code optional}
     * tells Hibernate the association is always present, so it can plan an inner
     * join and reject a book with no library before the statement reaches the
     * database rather than after it returns as a constraint violation.</p>
     *
     * <p>{@code LAZY}, unlike the EAGER {@link Category} above, and the
     * difference is deliberate. The category is read on every response to supply
     * its name; the library is not part of any response, so fetching it with
     * each book would be work nothing consumes.</p>
     *
     * <p>{@code @ToString.Exclude} because with {@code open-in-view=false} the
     * session closes at the end of the service layer, so a {@code toString()} on
     * a detached Book would try to initialise this proxy with nothing to
     * initialise it from and throw. A log line should never fail a request.</p>
     *
     * <p>No cascade and no orphanRemoval: a library outlives its books, and
     * saving or deleting a book must never reach across to the tenant that owns
     * it.</p>
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "library_id", nullable = false)
    private Library library;

    /** How many copies the library owns in total. */
    @Column(name = "total_copies", nullable = false)
    private Integer totalCopies;

    /** How many of those copies are currently free to be issued. */
    @Column(name = "available_copies", nullable = false)
    private Integer availableCopies;
}
