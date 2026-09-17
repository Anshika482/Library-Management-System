package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Proves the time-zone conventions on a real connection: the JVM runs in the
 * business zone, and date-times are stored in UTC even when the URL says
 * nothing about it.
 *
 * <p>The datasource URL here deliberately carries no {@code serverTimezone}, so
 * UTC storage can only come from the pool's {@code connectionTimeZone}
 * property. Stored values are read back with {@code DATE_FORMAT}, as text, so
 * the driver has no chance to convert them on the way out.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
class TimeZoneStorageIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void theApplicationRunsInTheConfiguredBusinessTimeZone() {
        assertThat(environment.getProperty(ProductionTimeZoneValidator.TIME_ZONE_PROPERTY)).isEqualTo("Asia/Kolkata");
        assertThat(ZoneId.systemDefault().getId()).as("pinned by the pom, not inherited").isEqualTo("Asia/Kolkata");

        ProductionTimeZoneValidator.validateBusinessTimeZone(
                environment.getProperty(ProductionTimeZoneValidator.TIME_ZONE_PROPERTY), ZoneId.systemDefault());
    }

    @Test
    void theConnectionPoolIsSetToUtcWithoutTheUrlSayingSo() {
        HikariDataSource pool = (HikariDataSource) dataSource;

        assertThat(pool.getJdbcUrl()).doesNotContainIgnoringCase("timezone");
        assertThat(pool.getDataSourceProperties().getProperty("connectionTimeZone")).isEqualTo("UTC");
    }

    @Test
    void dateTimesAreStoredInUtcAndDatesAsTheCalendarDate() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("TZ Library " + suffix);
        library.setCreatedAt(LocalDateTime.of(2026, 1, 15, 10, 0));
        library = libraryRepository.save(library);

        assertThat(storedText("libraries", "created_at", "%Y-%m-%d %H:%i:%s", library.getId()))
                .as("10:00 in Asia/Kolkata is stored as 04:30 UTC")
                .isEqualTo("2026-01-15 04:30:00");
        assertThat(libraryRepository.findById(library.getId()).orElseThrow().getCreatedAt())
                .as("and read back as 10:00")
                .isEqualTo(LocalDateTime.of(2026, 1, 15, 10, 0));

        User member = new User();
        member.setUsername("tz-member-" + suffix);
        member.setEmail("tz-member-" + suffix + "@example.invalid");
        member.setPassword("not-a-real-hash");
        member.setRole(Role.ROLE_MEMBER);
        member.setLibrary(library);
        member = userRepository.save(member);

        Book book = new Book();
        book.setTitle("TZ Title");
        book.setAuthor("TZ Author");
        book.setIsbn("TZ-" + suffix);
        book.setTotalCopies(1);
        book.setAvailableCopies(0);
        book.setLibrary(library);
        book = bookRepository.save(book);

        Transaction loan = new Transaction();
        loan.setBook(book);
        loan.setUser(member);
        loan.setLibrary(library);
        loan.setIssueDate(LocalDate.of(2026, 1, 1));
        loan.setDueDate(LocalDate.of(2026, 1, 15));
        loan.setStatus(TransactionStatus.ISSUED);
        loan = transactionRepository.save(loan);

        assertThat(storedText("transactions", "due_date", "%Y-%m-%d", loan.getId()))
                .as("a DATE is stored unconverted")
                .isEqualTo("2026-01-15");
    }

    private String storedText(String table, String column, String format, Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(" + column + ", ?) FROM " + table + " WHERE id = ?", String.class, format, id);
    }
}
