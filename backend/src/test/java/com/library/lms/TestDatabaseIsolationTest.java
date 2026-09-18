package com.library.lms;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Keeps every test that starts a Spring context away from the developer's own
 * database.
 *
 * <p>A context left on the default configuration connects to the development
 * schema, and with {@code ddl-auto=update} alters it to match the entities of
 * whatever branch the tests run on. So every {@code @SpringBootTest} has to
 * choose its database: a {@code spring.datasource.url} property naming a
 * throwaway schema - one whose name is {@code library_db_} followed by a suffix
 * - or a {@code @DynamicPropertySource} method that supplies one.</p>
 *
 * <p>Classes are inspected without being initialised, so no test's static
 * set-up runs here.</p>
 */
class TestDatabaseIsolationTest {

    private static final String URL_PROPERTY = "spring.datasource.url=";

    /** A MySQL URL whose schema name carries a suffix after {@code library_db_}. */
    private static final Pattern THROWAWAY_SCHEMA =
            Pattern.compile("jdbc:mysql://[^/]+/library_db_[A-Za-z0-9_]+(\\?.*)?");

    @Test
    void everySpringContextTestChoosesAThrowawayDatabase() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootTest.class));

        List<String> contextTests = new ArrayList<>();
        List<String> notIsolated = new ArrayList<>();

        for (BeanDefinition candidate : scanner.findCandidateComponents("com.library.lms")) {
            Class<?> type = Class.forName(candidate.getBeanClassName(), false, getClass().getClassLoader());
            contextTests.add(type.getSimpleName());
            if (!choosesAThrowawayDatabase(type)) {
                notIsolated.add(type.getName());
            }
        }

        assertThat(contextTests)
                .as("the scan found the context tests")
                .contains("LibraryManagementSystemApplicationTests", "FlywayMigrationIntegrationTest")
                .hasSizeGreaterThan(20);
        assertThat(notIsolated)
                .as("Spring context tests that would connect to the development database")
                .isEmpty();
    }

    @Test
    void theDevelopmentDatabaseIsNotAThrowawayOne() throws Exception {
        Properties development = new Properties();
        try (InputStream file = TestDatabaseIsolationTest.class.getResourceAsStream("/application.properties")) {
            assertThat(file).isNotNull();
            development.load(file);
        }

        assertThat(THROWAWAY_SCHEMA.matcher(development.getProperty("spring.datasource.url")).matches())
                .as("the guard's pattern must refuse the development database's URL")
                .isFalse();
    }

    private static boolean choosesAThrowawayDatabase(Class<?> type) {
        SpringBootTest annotation = AnnotatedElementUtils.findMergedAnnotation(type, SpringBootTest.class);
        String[] properties = annotation == null ? new String[0] : annotation.properties();

        boolean throwawayUrl = Arrays.stream(properties)
                .filter(property -> property.startsWith(URL_PROPERTY))
                .map(property -> property.substring(URL_PROPERTY.length()))
                .anyMatch(url -> THROWAWAY_SCHEMA.matcher(url).matches());

        boolean suppliesItsOwn = Arrays.stream(type.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(DynamicPropertySource.class));

        return throwawayUrl || suppliesItsOwn;
    }
}
