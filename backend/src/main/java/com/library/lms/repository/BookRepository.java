package com.library.lms.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.Book;

/**
 * Data-access layer for {@link Book}.
 *
 * <p>Notice that this is an <b>interface</b> with no implementation: we never
 * write a class that implements it. At startup Spring Data JPA finds this
 * interface, generates a proxy class in memory and registers it as a bean, so
 * the service layer can simply inject a {@code BookRepository} and call these
 * methods.</p>
 *
 * <p>Extending {@code JpaRepository<Book, Long>} - where {@code Book} is the
 * entity and {@code Long} is the type of its {@code @Id} - already provides
 * {@code save()}, {@code findById()}, {@code findAll()}, {@code deleteById()},
 * {@code count()} and paging/sorting support for free. Only the two queries
 * below are specific to this project.</p>
 */
/**
 * <p>It also extends {@code JpaSpecificationExecutor}, which adds
 * {@code findAll(Specification, Pageable)} and friends. That is what lets the
 * service combine optional filters - see {@link BookSpecifications} - while
 * still leaving the filtering, counting and paging to the database.</p>
 */
@Repository // optional for a Spring Data interface, but it states the layer's role clearly
public interface BookRepository extends JpaRepository<Book, Long>, JpaSpecificationExecutor<Book> {

    /**
     * One book, but only if it belongs to this library.
     *
     * <p>Scoping the lookup rather than loading the row and checking afterwards
     * is what keeps the two failure cases identical. A book in another library
     * and a book that was never created both return empty here, so a caller
     * cannot tell them apart - and the row is never read at all, which means a
     * refused update or delete cannot act on data it was refused.</p>
     *
     * @param id        the book wanted
     * @param libraryId the caller's library
     * @return the book, or empty if it is missing or belongs elsewhere
     */
    Optional<Book> findByIdAndLibraryId(Long id, Long libraryId);

    /**
     * Every book on one library's shelves, matched by category name.
     *
     * <p>The library comes first in the method name and in the query, so the
     * category name is only ever matched within the caller's own tenant. Without
     * it, two libraries that both have a "Fiction" shelf would see each other's
     * stock.</p>
     *
     * @param libraryId the caller's library
     * @param name      the category name to match
     * @return that library's books in that category
     */
    List<Book> findByLibraryIdAndCategoryName(Long libraryId, String name);

    /**
     * One book by ISBN within a library.
     *
     * <p>This is the duplicate check the service uses. It is scoped because the
     * database now enforces {@code UNIQUE(library_id, isbn)} rather than a global
     * unique ISBN: two libraries stocking the same title is ordinary, and only a
     * repeat within one library is a clash. Service and constraint agree, so a
     * rejected ISBN fails as a clean validation error rather than as a database
     * integrity violation.</p>
     *
     * @param isbn      the ISBN to look for
     * @param libraryId the library to look in
     * @return the matching book within that library, if any
     */
    Optional<Book> findByIsbnAndLibraryId(String isbn, Long libraryId);

    /**
     * Finds every book shelved under the category with this name.
     *
     * <p>Still a derived query, but now it reaches <b>through</b> the
     * relationship: {@code findBy} + {@code Category} (the field on Book) +
     * {@code Name} (the field on Category). Spring Data reads that as "join
     * categories and match on its name", producing
     * {@code SELECT ... FROM books b JOIN categories c ON b.category_id = c.id
     * WHERE c.name = ?} - written for us, so no @Query and no SQL.</p>
     *
     * <p>This replaces the former {@code findByCategory(String)}, which matched
     * the old free-text column. Callers are unaffected: the parameter is still
     * a category name, so {@code /api/books/category/Programming} behaves
     * exactly as it did.</p>
     *
     * <p>The return type is a {@code List} because a category normally holds many
     * books; when none match, Spring Data returns an <b>empty list</b>, never
     * null, so the caller can loop over the result without checking first.</p>
     */
    List<Book> findByCategoryName(String name);

    /**
     * Reports whether any book currently points at this category.
     *
     * <p>Used before deleting a category. Reading it as Spring Data does:
     * {@code exists} + {@code ByCategory} (the association on Book) +
     * {@code Id} (the key on Category), giving
     * {@code SELECT count(*) FROM books WHERE category_id = ?}. Note it needs
     * no join - the foreign key is already on the books row - and it is a
     * derived query, so no @Query and no SQL.</p>
     *
     * <p>{@code boolean} rather than a List because the caller only needs to
     * know <i>whether</i> the category is in use, never which books; the
     * database can stop at the first match.</p>
     */
    boolean existsByCategoryId(Long categoryId);
}
