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
 */
@SpringBootTest
class LibraryManagementSystemApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: the test fails if the context cannot start.
    }
}
