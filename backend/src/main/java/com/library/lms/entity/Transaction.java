package com.library.lms.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One borrowing of one book by one user.
 *
 * <p>A row is created when a book is issued and updated when it comes back, so
 * the table is a permanent record of who had what and when - not just a list of
 * what is currently out. That history is why a returned transaction is kept
 * rather than deleted.</p>
 *
 * <p>Like {@link User}, this entity was written to fit a table that
 * <b>already existed</b>. Every column name, nullability and type below was read
 * from the live schema, so Hibernate has nothing to alter. That includes
 * {@code fine_amount}, which is not needed yet but is mapped because the column
 * is there - leaving it out would mean the entity could never read or write a
 * value that the table can already hold.</p>
 *
 * <p>All three relationships are deliberately <b>unidirectional</b>: a
 * Transaction knows its Book, its User and its Library, but none of them holds
 * a collection of transactions. A book borrowed for years would otherwise
 * carry an unbounded list that JPA wants to manage, and nothing in the
 * application needs to walk the association in that direction.</p>
 *
 * <p>There is no cascade and no orphanRemoval, which matters more than it looks:
 * with cascade, deleting a transaction record could delete the book or the
 * borrower's account. Transactions reference those rows, they do not own
 * them.</p>
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    /** Primary key, assigned by MySQL's AUTO_INCREMENT as on the other entities. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Row version, maintained by Hibernate for optimistic locking.
     *
     * <p>The same protection {@link Book} carries, for the same reason. Two
     * concurrent returns of one loan would each read {@code ISSUED}, each pass
     * the status check and each put a copy back on the shelf; with a version
     * column the second write matches no row and fails rather than adding a
     * copy the library does not own.</p>
     *
     * <p>Never assigned by hand - Hibernate owns this field.</p>
     */
    @Version
    private Long version;

    /**
     * The book that was borrowed.
     *
     * <p>{@code nullable = false} matches the existing NOT NULL column and its
     * foreign key to {@code books(id)} - a transaction without a book would be
     * meaningless, and the database already refuses one.</p>
     */
    @ManyToOne
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    /**
     * Who borrowed it.
     *
     * <p>Also NOT NULL, with a foreign key to {@code users(id)}. This is the
     * column that made the original Step 29 design unworkable: the table demands
     * a user, so a transaction modelled with only a member's name could never be
     * inserted. Mapping the real relationship resolves that.</p>
     */
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The library this borrowing belongs to.
     *
     * <p>A transaction can already reach a library two ways - through its book
     * and through its user - and nothing makes those two agree. Recording the
     * owner directly gives the row one answer instead of two that may differ,
     * which is what lets a later step scope every query with a single
     * predicate.</p>
     *
     * <p>{@code LAZY}, no cascade, no orphanRemoval, and excluded from
     * {@code toString()} - the same reasoning as the matching field on
     * {@link Book}, {@link Category} and {@link User}. With
     * {@code open-in-view=false} an eager {@code toString()} on a detached
     * Transaction would try to initialise this proxy after the session has
     * closed and throw.</p>
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "library_id", nullable = false)
    private Library library;

    /** The day the book went out. Required. */
    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    /** The day it is due back. Required, and set when the book is issued. */
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /**
     * The day it actually came back.
     *
     * <p>Nullable on purpose: while a book is still out there is no return date
     * yet, and null is the honest way to say "hasn't happened". It is the
     * clearest signal that a transaction is still open.</p>
     */
    @Column(name = "return_date")
    private LocalDate returnDate;

    /**
     * Any fine owed for returning late.
     *
     * <p>Nullable, and nothing calculates it yet - fine handling is a later
     * step. The column is mapped only so the entity matches the table.</p>
     */
    @Column(name = "fine_amount")
    private Double fineAmount;

    /**
     * Where this borrowing has got to.
     *
     * <p>{@code EnumType.STRING} stores the constant's name - "ISSUED" - not its
     * position. The existing column is a MySQL ENUM holding exactly those
     * strings, and ordinal storage would both mismatch the column and silently
     * corrupt every row if the constants were ever reordered.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;
}
