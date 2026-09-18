package com.library.lms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: starts the whole Spring application context.
 *
 * <p>If this test passes, the configuration, dependencies and database
 * connection are all wired correctly. It is the cheapest way to catch a broken
 * setup, and it requires a reachable MySQL database because Spring Data JPA
 * creates a real connection pool at startup.</p>
 *
 * <p><b>Never the development database.</b> Left on the default configuration
 * this context connected to the developer's own schema and, with
 * {@code ddl-auto=update}, altered it whenever the entities changed. It uses the
 * throwaway schema the other integration tests share - with the same
 * properties, and so the same cached context - and
 * {@link TestDatabaseIsolationTest} keeps every context test that way.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
class LibraryManagementSystemApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: the test fails if the context cannot start.
    }
}
