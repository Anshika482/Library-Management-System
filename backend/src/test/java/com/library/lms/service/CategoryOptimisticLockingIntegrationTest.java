package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.library.lms.entity.Category;
import com.library.lms.entity.Library;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.LibraryRepository;

/**
 * Proves that {@code Category}'s optimistic locking actually works, against a
 * real Hibernate session and a real MySQL row.
 *
 * <p>The distinction from {@code OptimisticLockingTest} matters. That class is
 * Mockito-based: it makes a mocked {@code save} <i>throw</i>
 * {@link ObjectOptimisticLockingFailureException} and checks the service lets it
 * out. Useful, but it proves nothing about whether Hibernate would ever raise
 * it - a missing column, a misspelled field or a forgotten {@code @Version}
 * would leave every one of those tests green. Nothing in this project verified
 * that the mechanism itself fires until now.</p>
 *
 * <p><b>How two persistence contexts are obtained.</b> The class carries no
 * {@code @Transactional}, so every repository call opens a transaction, runs,
 * commits and closes its own persistence context. Two separate
 * {@code findById} calls therefore return two <i>independent detached
 * instances</i> of the same row, each carrying the version it read. That is
 * exactly the situation two librarians editing the same category produce, and
 * {@code theTwoLoadsProduceIndependentInstances} asserts the premise rather than
 * assuming it.</p>
 *
 * <p><b>Why this fails if {@code @Version} is removed.</b> Without a version
 * column in the UPDATE's WHERE clause the stale write simply succeeds and
 * overwrites the winner. Two tests here would then fail: the stale save would
 * throw nothing, and the final row would hold the loser's name. That is the
 * acceptance criterion for this test being real rather than decorative.</p>
 *
 * <p><b>Isolation:</b> its own throwaway schema, created on demand and never
 * shared with the development database. {@code ddl-auto} stays on
 * {@code update} - it can add tables but can never drop one - and every fixture
 * carries a unique suffix, so repeated runs cannot collide and nothing ever
 * needs deleting.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step124_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
class CategoryOptimisticLockingIntegrationTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private LibraryRepository libraryRepository;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Persists one library and one category through the real repositories.
     *
     * <p>No transaction spans these calls, so the returned category is already
     * committed and detached before the test touches it.</p>
     */
    private Category persistCategory() {
        String suffix = unique();

        Library library = new Library();
        library.setName("Step124 Library " + suffix);
        library = libraryRepository.save(library);

        Category category = new Category();
        category.setName("Original " + suffix);
        category.setLibrary(library);

        return categoryRepository.save(category);
    }

    private Category reloadFromDatabase(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new AssertionError("the saved category was not found"));
    }

    // ---------- the premise ----------

    @Test
    void theTwoLoadsProduceIndependentInstances() {
        Long id = persistCategory().getId();

        Category contextA = reloadFromDatabase(id);
        Category contextB = reloadFromDatabase(id);

        // Each findById ran in its own transaction, so these are two distinct
        // objects, not the same managed instance handed back twice. Everything
        // below depends on that.
        assertThat(contextA).isNotSameAs(contextB);
        assertThat(contextA.getId()).isEqualTo(contextB.getId());
        assertThat(contextA.getVersion()).isEqualTo(contextB.getVersion());
    }

    // ---------- (b) the starting version ----------

    @Test
    void aPersistedCategoryStartsAtVersionZero() {
        Category saved = persistCategory();

        assertThat(saved.getVersion())
                .as("Hibernate assigns the initial version on insert")
                .isZero();
        assertThat(reloadFromDatabase(saved.getId()).getVersion())
                .as("and the row itself carries it")
                .isZero();
    }

    // ---------- (c-f) the winning update ----------

    @Test
    void theFirstUpdateSucceedsAndIncrementsTheVersion() {
        Long id = persistCategory().getId();

        Category contextA = reloadFromDatabase(id);
        Long versionBefore = contextA.getVersion();

        contextA.setName("Winner " + unique());
        Category committed = categoryRepository.save(contextA);

        assertThat(versionBefore).isZero();
        assertThat(committed.getVersion())
                .as("Hibernate increments the version on a committed update")
                .isEqualTo(versionBefore + 1);
        assertThat(reloadFromDatabase(id).getVersion())
                .as("and the increment is in the database, not just in memory")
                .isEqualTo(1L);
    }

    // ---------- (g-h) the stale update ----------

    @Test
    void theStaleUpdateIsRefusedByOptimisticLocking() {
        Long id = persistCategory().getId();

        Category contextA = reloadFromDatabase(id);
        Category contextB = reloadFromDatabase(id);   // loaded before A commits

        contextA.setName("Winner " + unique());
        categoryRepository.save(contextA);            // committed: version 0 -> 1

        // B still believes the row is at version 0. Its UPDATE carries
        // WHERE version = 0, which now matches no row.
        contextB.setName("Stale " + unique());

        assertThatThrownBy(() -> categoryRepository.save(contextB))
                .as("the losing writer must be told, not silently allowed to overwrite")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ---------- the final state ----------

    @Test
    void theWinningUpdateSurvivesAndTheStaleValueIsNotPersisted() {
        Long id = persistCategory().getId();
        String winningName = "Winner " + unique();
        String staleName = "Stale " + unique();

        Category contextA = reloadFromDatabase(id);
        Category contextB = reloadFromDatabase(id);

        contextA.setName(winningName);
        categoryRepository.save(contextA);

        contextB.setName(staleName);
        assertThatThrownBy(() -> categoryRepository.save(contextB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Category finalState = reloadFromDatabase(id);

        assertThat(finalState.getName())
                .as("the first update must still be there")
                .isEqualTo(winningName);
        assertThat(finalState.getName())
                .as("the stale update must not have overwritten it")
                .isNotEqualTo(staleName);
        assertThat(finalState.getVersion())
                .as("exactly one update was applied")
                .isEqualTo(1L);
    }
}
