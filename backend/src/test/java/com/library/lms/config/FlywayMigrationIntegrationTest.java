package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.library.lms.entity.Book;
import com.library.lms.entity.Category;
import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Proves the Flyway migrations build, on an empty database, exactly the schema
 * the entities map - and that the application starts on it the way production
 * does.
 *
 * <p><b>Production's schema settings, on a database that has never
 * existed.</b> The context runs with Flyway on and {@code ddl-auto=validate},
 * against a schema whose name is new on every run, so there is no earlier
 * history for Flyway to skip past and no table Hibernate could have left
 * behind. The context starting at all is the first proof: under validate,
 * Hibernate refuses to start when a table or column it maps is missing or has
 * the wrong type.</p>
 *
 * <p><b>Validate is not enough on its own</b>, because it looks at tables,
 * columns and types and nothing else. A migration that forgot a unique
 * constraint, a foreign key or a NOT NULL would pass it, and the database would
 * then quietly accept data the entities were designed to refuse. So Hibernate
 * is also asked for the create script it would generate for the same mappings;
 * that script is run into a second empty schema, and the two are compared from
 * MySQL's own catalogue - every column's type, nullability and default, every
 * index with its columns and uniqueness, every foreign key and where it
 * points.</p>
 *
 * <p>That comparison keeps standing guard: an entity change without a matching
 * migration fails here, not at the first production start.</p>
 *
 * <p><b>Isolation:</b> neither schema is the developer database or one any
 * other test uses. Both carry a per-run suffix and are dropped afterwards, by
 * a helper that refuses any name that is not one of these two.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
        "spring.jpa.properties.hibernate.hbm2ddl.delimiter=;",
        "spring.jpa.properties.hibernate.hbm2ddl.schema-generation.script.append=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayMigrationIntegrationTest {

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    /** Built by Flyway, the way production is. */
    private static final String MIGRATED = "library_db_step150_flyway_" + RUN;

    /** Built from Hibernate's own create script: the reference. */
    private static final String GENERATED = "library_db_step150_hibernate_" + RUN;

    /** The only schema names this class will ever drop. */
    private static final Pattern THROWAWAY = Pattern.compile("library_db_step150_(flyway|hibernate)_[0-9a-f]{8}");

    /** Where Hibernate writes the create script for the current mappings. */
    private static final Path HIBERNATE_SCRIPT = createScriptTarget();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ---------- a schema that did not exist before this run ----------

    @DynamicPropertySource
    static void freshSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://localhost:3306/" + MIGRATED
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        registry.add("spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target",
                HIBERNATE_SCRIPT::toString);
    }

    private static Path createScriptTarget() {
        try {
            return Files.createTempFile("step150-hibernate-create-", ".sql");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create a file for Hibernate's create script", exception);
        }
    }

    @AfterAll
    void dropTheThrowawaySchemas() throws IOException {
        dropThrowaway(GENERATED);
        dropThrowaway(MIGRATED);
        Files.deleteIfExists(HIBERNATE_SCRIPT);
    }

    private void dropThrowaway(String schema) {
        if (!THROWAWAY.matcher(schema).matches()) {
            throw new IllegalStateException("Refusing to drop a schema this test did not create");
        }

        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + schema);
    }

    // ---------- the migration ran, from nothing ----------

    @Test
    void flywayBuiltTheSchemaFromNothingWithEveryMigration() {
        List<String> history = jdbcTemplate.query(
                "SELECT version, description, type, success FROM flyway_schema_history ORDER BY installed_rank",
                (row, n) -> row.getString(1) + " | " + row.getString(2) + " | " + row.getString(3)
                        + " | " + row.getBoolean(4));

        assertThat(history)
                .as("every migration, in order, applied on this run to an empty schema")
                .containsExactly(
                        "1 | initial schema | SQL | true",
                        "2 | fine payment tracking | SQL | true",
                        "3 | refresh tokens | SQL | true");

        assertThat(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                String.class, MIGRATED))
                .containsExactly("books", "categories", "flyway_schema_history", "libraries", "refresh_tokens",
                        "transactions", "users");
    }

    @Test
    void theApplicationStartsOnTheMigratedSchemaWithHibernateOnlyValidating() {
        // Reaching this line is the assertion that matters: under validate the
        // context does not start if any mapped table or column is missing or
        // of the wrong type. These say which configuration it started with.
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(MIGRATED);
    }

    @Test
    void theInitialMigrationCreatesStructureAndInsertsNoRows() throws IOException {
        String migration;
        try (InputStream file = getClass().getResourceAsStream("/db/migration/V1__initial_schema.sql")) {
            assertThat(file).as("V1 must be on the classpath where Flyway looks for it").isNotNull();
            migration = new String(file.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<String> statements = Arrays.stream(withoutComments(migration).split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();

        assertThat(statements)
                .as("one CREATE TABLE per entity, and no seed data of any kind")
                .hasSize(5)
                .allSatisfy(statement -> assertThat(statement.toUpperCase(Locale.ROOT)).startsWith("CREATE TABLE "));
    }

    // ---------- it is exactly the schema the entities map ----------

    @Test
    void theMigrationBuildsExactlyTheSchemaTheEntitiesMap() throws Exception {
        List<String> ddl = hibernateCreateStatements();
        assertThat(ddl).as("Hibernate wrote its create script for the current mappings").isNotEmpty();

        buildReferenceSchema(ddl);

        List<String> expected = structureOf(GENERATED);
        List<String> actual = structureOf(MIGRATED);

        // Not vacuous: the reference holds the facts that matter most.
        assertThat(expected)
                .anyMatch(fact -> fact.startsWith("index users.") && fact.endsWith(" unique (username)"))
                .anyMatch(fact -> fact.startsWith("index users.") && fact.endsWith(" unique (email)"))
                .anyMatch(fact -> fact.equals("index books.uk_books_library_isbn unique (library_id,isbn)"))
                .anyMatch(fact -> fact.startsWith("foreign key transactions.user_id -> users.id "))
                .anyMatch(fact -> fact.startsWith("column users.enabled tinyint(1) nullable=NO default=1 "));

        assertThat(actual)
                .as("the migrated schema, fact for fact against what Hibernate generates")
                .containsExactlyElementsOf(expected);
    }

    @Test
    void enumColumnsListTheirValuesInDeclarationOrder() {
        // MySQL sorts an ENUM column by each value's position in its definition,
        // not alphabetically, so this order is what sorting by status returns.
        // Hibernate's create script lists the values alphabetically; the
        // migration keeps the Java declaration order, which is also how the
        // original column was documented. The structural comparison therefore
        // checks which values exist, and their order is pinned here instead.
        assertThat(columnType(MIGRATED, "transactions", "status")).isEqualTo(enumOf(TransactionStatus.values()));
        assertThat(columnType(MIGRATED, "users", "role")).isEqualTo(enumOf(Role.values()));
        assertThat(columnType(MIGRATED, "transactions", "fine_payment_status"))
                .isEqualTo(enumOf(FinePaymentStatus.values()));
    }

    // ---------- and the application can use it ----------

    @Test
    void everyEntityIsWrittenAndReadBackThroughTheMigratedTables() {
        Library library = new Library();
        library.setName("Step150 Library " + RUN);
        library = libraryRepository.save(library);

        User member = new User();
        member.setUsername("step150-member-" + RUN);
        member.setEmail("step150-member-" + RUN + "@example.invalid");
        member.setPassword(passwordEncoder.encode("step150-test-only-password"));
        member.setRole(Role.ROLE_MEMBER);
        member.setLibrary(library);
        member = userRepository.save(member);

        Category category = new Category();
        category.setName("Step150 Category");
        category.setLibrary(library);
        category = categoryRepository.save(category);

        Book book = new Book();
        book.setTitle("Step150 Title");
        book.setAuthor("Step150 Author");
        book.setIsbn("150-" + RUN);
        book.setTotalCopies(3);
        book.setAvailableCopies(2);
        book.setCategory(category);
        book.setLibrary(library);
        book = bookRepository.save(book);

        Transaction loan = new Transaction();
        loan.setBook(book);
        loan.setUser(member);
        loan.setLibrary(library);
        loan.setIssueDate(LocalDate.of(2026, 9, 1));
        loan.setDueDate(LocalDate.of(2026, 9, 15));
        loan.setFineAmount(12.5);
        loan.setStatus(TransactionStatus.OVERDUE);
        // Any account serves to prove the column maps; in the application only
        // staff record payments.
        loan.setFinePaymentStatus(FinePaymentStatus.PAID);
        loan.setFinePaidAt(LocalDateTime.of(2026, 9, 20, 10, 30));
        loan.setFinePaymentRecordedBy(member);
        loan = transactionRepository.save(loan);

        User storedMember = userRepository.findById(member.getId()).orElseThrow();
        assertThat(storedMember.getRole()).isEqualTo(Role.ROLE_MEMBER);
        assertThat(storedMember.isEnabled()).isTrue();
        assertThat(storedMember.isAccountNonLocked()).isTrue();
        assertThat(storedMember.getCreatedAt()).isNotNull();
        assertThat(storedMember.getLibrary().getId()).isEqualTo(library.getId());

        Category storedCategory = categoryRepository.findById(category.getId()).orElseThrow();
        assertThat(storedCategory.getVersion()).as("optimistic locking column").isNotNull();
        assertThat(storedCategory.getLibrary().getId()).isEqualTo(library.getId());

        Book storedBook = bookRepository.findById(book.getId()).orElseThrow();
        assertThat(storedBook.getVersion()).as("optimistic locking column").isNotNull();
        assertThat(storedBook.getIsbn()).isEqualTo("150-" + RUN);
        assertThat(storedBook.getAvailableCopies()).isEqualTo(2);
        assertThat(storedBook.getCategory().getId()).isEqualTo(category.getId());
        assertThat(storedBook.getLibrary().getId()).isEqualTo(library.getId());

        Transaction storedLoan = transactionRepository.findById(loan.getId()).orElseThrow();
        assertThat(storedLoan.getStatus()).isEqualTo(TransactionStatus.OVERDUE);
        assertThat(storedLoan.getIssueDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(storedLoan.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(storedLoan.getReturnDate()).isNull();
        assertThat(storedLoan.getFineAmount()).isEqualTo(12.5);
        assertThat(storedLoan.getBook().getId()).isEqualTo(book.getId());
        assertThat(storedLoan.getUser().getId()).isEqualTo(member.getId());
        assertThat(storedLoan.getLibrary().getId()).isEqualTo(library.getId());
        assertThat(storedLoan.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.PAID);
        assertThat(storedLoan.getFinePaidAt()).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 30));
        assertThat(storedLoan.getFinePaymentRecordedBy().getId()).isEqualTo(member.getId());
    }

    // ---------- helpers ----------

    private static List<String> hibernateCreateStatements() throws IOException {
        return Arrays.stream(Files.readString(HIBERNATE_SCRIPT).split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    /** Runs Hibernate's create script into its own empty schema. */
    private void buildReferenceSchema(List<String> ddl) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String home = connection.getCatalog();
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + GENERATED);
            }

            connection.setCatalog(GENERATED);
            // Created only after the switch: the MySQL driver remembers which
            // database was current when a statement was made and switches back
            // to it for every execute, so an earlier statement would run this
            // script straight into the migrated schema.
            try (Statement statement = connection.createStatement()) {
                for (String sql : ddl) {
                    statement.execute(sql);
                }
            } finally {
                // The pool does not reset the catalog itself, so the connection
                // goes back pointing where every other caller expects.
                connection.setCatalog(home);
            }
        }
    }

    /**
     * Everything about a schema's structure the application relies on, as
     * MySQL records it: one line per fact, sorted.
     *
     * <p>Index and constraint names are included, lower-cased because MySQL
     * treats them case-insensitively. A later migration that drops or alters a
     * constraint names it, so the name has to be the same everywhere. Column
     * order is not included: it changes nothing about how the application reads
     * or writes, and a column added by a later ALTER lands at the end
     * regardless.</p>
     */
    private List<String> structureOf(String schema) {
        List<String> facts = new ArrayList<>();

        facts.addAll(jdbcTemplate.query(
                "SELECT table_name, engine, table_collation FROM information_schema.tables"
                        + " WHERE table_schema = ? AND table_type = 'BASE TABLE'"
                        + " AND table_name <> 'flyway_schema_history'",
                (row, n) -> "table " + row.getString(1) + " engine=" + row.getString(2)
                        + " collation=" + row.getString(3),
                schema));

        facts.addAll(jdbcTemplate.query(
                "SELECT table_name, column_name, column_type, is_nullable, column_default, extra,"
                        + " character_set_name, collation_name FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name <> 'flyway_schema_history'",
                (row, n) -> "column " + row.getString(1) + "." + row.getString(2)
                        + " " + withSortedEnumValues(row.getString(3))
                        + " nullable=" + row.getString(4)
                        + " default=" + row.getString(5)
                        + " extra=" + row.getString(6)
                        + " charset=" + row.getString(7)
                        + " collation=" + row.getString(8),
                schema));

        facts.addAll(jdbcTemplate.query(
                "SELECT table_name, index_name, non_unique,"
                        + " GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')"
                        + " FROM information_schema.statistics"
                        + " WHERE table_schema = ? AND table_name <> 'flyway_schema_history'"
                        + " GROUP BY table_name, index_name, non_unique",
                (row, n) -> "index " + row.getString(1) + "." + row.getString(2).toLowerCase(Locale.ROOT)
                        + (row.getInt(3) == 0 ? " unique" : " non-unique")
                        + " (" + row.getString(4) + ")",
                schema));

        facts.addAll(jdbcTemplate.query(
                "SELECT k.table_name, k.column_name, k.referenced_table_name, k.referenced_column_name,"
                        + " k.constraint_name, r.update_rule, r.delete_rule"
                        + " FROM information_schema.key_column_usage k"
                        + " JOIN information_schema.referential_constraints r"
                        + " ON r.constraint_schema = k.constraint_schema"
                        + " AND r.table_name = k.table_name"
                        + " AND r.constraint_name = k.constraint_name"
                        + " WHERE k.table_schema = ? AND k.referenced_table_name IS NOT NULL",
                (row, n) -> "foreign key " + row.getString(1) + "." + row.getString(2)
                        + " -> " + row.getString(3) + "." + row.getString(4)
                        + " name=" + row.getString(5).toLowerCase(Locale.ROOT)
                        + " on update " + row.getString(6)
                        + " on delete " + row.getString(7),
                schema));

        facts.addAll(jdbcTemplate.query(
                "SELECT t.table_name, c.check_clause FROM information_schema.table_constraints t"
                        + " JOIN information_schema.check_constraints c"
                        + " ON c.constraint_schema = t.constraint_schema"
                        + " AND c.constraint_name = t.constraint_name"
                        + " WHERE t.table_schema = ? AND t.constraint_type = 'CHECK'",
                (row, n) -> "check " + row.getString(1) + " " + row.getString(2),
                schema));

        facts.sort(null);
        return facts;
    }

    /** An ENUM's values in a fixed order, so the comparison sees which values exist. */
    private static String withSortedEnumValues(String columnType) {
        if (!columnType.startsWith("enum(")) {
            return columnType;
        }

        String values = columnType.substring("enum(".length(), columnType.length() - 1);
        return Arrays.stream(values.split(",")).sorted().collect(Collectors.joining(",", "enum(", ")"));
    }

    private String columnType(String schema, String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                String.class, schema, table, column);
    }

    private static String enumOf(Enum<?>[] constants) {
        return Arrays.stream(constants)
                .map(constant -> "'" + constant.name() + "'")
                .collect(Collectors.joining(",", "enum(", ")"));
    }

    private static String withoutComments(String sql) {
        return sql.lines()
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining("\n"));
    }
}
