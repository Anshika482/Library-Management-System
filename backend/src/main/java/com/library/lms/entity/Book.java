package com.library.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
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
@Table(name = "books")
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
    @Column(nullable = false, unique = true, length = 20)
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

    /** How many copies the library owns in total. */
    @Column(name = "total_copies", nullable = false)
    private Integer totalCopies;

    /** How many of those copies are currently free to be issued. */
    @Column(name = "available_copies", nullable = false)
    private Integer availableCopies;
}
