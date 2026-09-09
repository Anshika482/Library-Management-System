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
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A shelf label such as "Programming" or "Fiction".
 *
 * <p>Until now a category was just a piece of text repeated on every book row.
 * That works until you want to rename a category, list the ones that exist, or
 * stop two spellings of the same shelf ("Sci-Fi" and "Science Fiction") drifting
 * apart. Giving categories their own table - <b>normalising</b> them - fixes all
 * three: the name is stored once, and each book points at it.</p>
 *
 * <p>{@code unique = true} on the name is what enforces "stored once". The
 * database itself will refuse a second "Programming" row.</p>
 */
@Entity
@Table(
        name = "categories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_categories_library_name",
                columnNames = {"library_id", "name"}))
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    /** Primary key, assigned by MySQL's AUTO_INCREMENT exactly as on Book. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The display name of the category.
     *
     * <p>Length 100 matches the old {@code books.category} column, so every
     * existing value fits without truncation.</p>
     */
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * The library this category belongs to.
     *
     * <p>Categories are tenant-owned. Every library will want its own
     * "Fiction", and today they cannot have one: {@code categories.name}
     * carries a <b>global</b> unique index, so the second library to try would
     * be refused by the database. Listing them is no better - the current
     * endpoint returns every row to every caller. Both are fixed by scoping,
     * and this column is the first half of it.</p>
     *
     * <p><b>Required.</b> The column arrived nullable because a NOT NULL
     * column with no default cannot be added to a populated table under
     * {@code STRICT_TRANS_TABLES}; the existing rows were then backfilled and
     * the column tightened. Both sides now agree that every category belongs to
     * a library.</p>
     *
     * <p>{@code optional = false} as well as {@code nullable = false}: the
     * {@code @JoinColumn} setting describes the column, while {@code optional}
     * tells Hibernate the association is always present, so it can plan an
     * inner join and reject a category with no library before the statement
     * reaches the database rather than after it returns as a constraint
     * violation.</p>
     *
     * <p>{@code LAZY}, no cascade, no orphanRemoval, and excluded from
     * {@code toString()} - the same reasoning as the matching field on
     * {@link User}. With {@code open-in-view=false} an eager {@code toString()}
     * on a detached Category would try to initialise this proxy after the
     * session has closed and throw.</p>
     */
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "library_id", nullable = false)
    private Library library;
}
