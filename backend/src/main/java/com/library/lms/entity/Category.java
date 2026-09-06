package com.library.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Table(name = "categories")
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
    @Column(nullable = false, unique = true, length = 100)
    private String name;
}
