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
     * Finds the single book carrying this ISBN.
     *
     * <p>This is a <b>derived query</b>: Spring Data parses the method name
     * {@code findByIsbn} and writes the SQL itself
     * ({@code SELECT * FROM books WHERE isbn = ?}). No @Query is needed - the
     * name is the query, so it must match the {@code isbn} field on Book.</p>
     *
     * <p>The return type is {@code Optional<Book>} because the ISBN may not be
     * in the library at all. Optional forces the caller to handle the "not
     * found" case explicitly instead of risking a NullPointerException, and it
     * is the right type here precisely because {@code isbn} is unique - at most
     * one row can ever come back.</p>
     */
    Optional<Book> findByIsbn(String isbn);

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
