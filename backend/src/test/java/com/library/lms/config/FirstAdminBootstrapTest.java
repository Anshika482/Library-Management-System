package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import com.library.lms.dto.CreateLibraryRequest;
import com.library.lms.dto.FirstAdminRequest;
import com.library.lms.dto.LibraryResponse;
import com.library.lms.dto.UserResponse;
import com.library.lms.entity.Role;
import com.library.lms.repository.UserRepository;
import com.library.lms.service.LibraryService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * When the bootstrap runs, when it refuses, and when it does nothing at all.
 *
 * <p>No database and no context: the account count is stubbed, so "empty" and
 * "already has accounts" are both a line of setup, and the real validator is
 * used so the rules under test are the ones the API applies rather than a copy
 * of them.</p>
 *
 * <p>What it actually writes - a BCrypt password, the ADMIN role, one library -
 * is proved against a real database in
 * {@code FirstAdminBootstrapIntegrationTest}.</p>
 */
class FirstAdminBootstrapTest {

    /** Test-only values; the password is never a real one and must never be logged. */
    private static final String LIBRARY = "Bootstrap Library";

    private static final String USERNAME = "bootstrap-admin";

    private static final String EMAIL = "bootstrap-admin@example.invalid";

    private static final String PASSWORD = "bootstrap-test-only-password";

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    private final UserRepository users = mock(UserRepository.class);

    private final LibraryService libraries = mock(LibraryService.class);

    private FirstAdminBootstrap bootstrap(String library, String username, String email, String password) {
        return new FirstAdminBootstrap(users, libraries, VALIDATOR, library, username, email, password);
    }

    private FirstAdminBootstrap configuredBootstrap() {
        return bootstrap(LIBRARY, USERNAME, EMAIL, PASSWORD);
    }

    private void givenAccounts(long count) {
        when(users.count()).thenReturn(count);
    }

    private static LibraryResponse created() {
        return new LibraryResponse(7L, LIBRARY, null,
                new UserResponse(11L, USERNAME, EMAIL, Role.ROLE_ADMIN, true, true));
    }

    // ---------- a database that already has accounts ----------

    @Test
    void anExistingAccountStopsTheBootstrapBeforeItReadsAnything() {
        givenAccounts(1);

        assertThatCode(() -> bootstrap("", "", "", "").run(null))
                .as("blank configuration is the normal state of a running deployment")
                .doesNotThrowAnyException();

        verifyNoInteractions(libraries);
    }

    @Test
    void configurationCannotAddAnAdministratorToADatabaseThatHasOne() {
        givenAccounts(250);

        configuredBootstrap().run(null);

        verify(libraries, never()).createFirstLibrary(any());
    }

    // ---------- an empty database ----------

    @Test
    void anEmptyDatabaseGetsItsFirstLibraryAndAdministrator() {
        givenAccounts(0);
        when(libraries.createFirstLibrary(any())).thenReturn(created());

        configuredBootstrap().run(null);

        ArgumentCaptor<CreateLibraryRequest> request = ArgumentCaptor.forClass(CreateLibraryRequest.class);
        verify(libraries).createFirstLibrary(request.capture());

        assertThat(request.getValue().getName()).isEqualTo(LIBRARY);
        assertThat(request.getValue().getAdmin().getUsername()).isEqualTo(USERNAME);
        assertThat(request.getValue().getAdmin().getEmail()).isEqualTo(EMAIL);
        assertThat(request.getValue().getAdmin().getPassword())
                .as("handed over as typed; the service is what hashes it")
                .isEqualTo(PASSWORD);
    }

    @Test
    void surroundingSpacesAreTrimmedLikeTheApiTrimsThem() {
        givenAccounts(0);
        when(libraries.createFirstLibrary(any())).thenReturn(created());

        bootstrap("  " + LIBRARY + " ", " " + USERNAME + "  ", " " + EMAIL + " ", PASSWORD).run(null);

        ArgumentCaptor<CreateLibraryRequest> request = ArgumentCaptor.forClass(CreateLibraryRequest.class);
        verify(libraries).createFirstLibrary(request.capture());

        assertThat(request.getValue().getName()).isEqualTo(LIBRARY);
        assertThat(request.getValue().getAdmin().getUsername()).isEqualTo(USERNAME);
        assertThat(request.getValue().getAdmin().getEmail()).isEqualTo(EMAIL);
    }

    @Test
    void neitherARoleNorALibraryCanBeConfigured() {
        assertThat(FirstAdminRequest.class.getDeclaredFields())
                .as("no field for either, so configuration has nothing to set them with")
                .noneMatch(field -> field.getName().toLowerCase().contains("role")
                        || field.getName().toLowerCase().contains("library"));
        assertThat(CreateLibraryRequest.class.getDeclaredFields())
                .noneMatch(field -> field.getName().toLowerCase().contains("role")
                        || field.getName().toLowerCase().contains("libraryid"));
    }

    // ---------- configuration the bootstrap refuses ----------

    @ParameterizedTest
    @CsvSource(value = {
            ",username,e@example.invalid,password1|BOOTSTRAP_ADMIN_LIBRARY",
            "Library,,e@example.invalid,password1|BOOTSTRAP_ADMIN_USERNAME",
            "Library,username,,password1|BOOTSTRAP_ADMIN_EMAIL",
            "Library,username,e@example.invalid,|BOOTSTRAP_ADMIN_PASSWORD",
            "Library,username,e@example.invalid,'   '|BOOTSTRAP_ADMIN_PASSWORD"},
            delimiter = '|')
    void aMissingSettingStopsStartupAndSaysWhichOne(String configured, String expectedVariable) {
        givenAccounts(0);
        String[] parts = (configured + " ").split(",", -1);

        assertThatThrownBy(() -> bootstrap(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim())
                .run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedVariable);

        verifyNoInteractions(libraries);
    }

    @Test
    void anEntirelyUnconfiguredBootstrapNamesEveryVariable() {
        givenAccounts(0);

        assertThatThrownBy(() -> bootstrap("", "", "", "").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOTSTRAP_ADMIN_LIBRARY")
                .hasMessageContaining("BOOTSTRAP_ADMIN_USERNAME")
                .hasMessageContaining("BOOTSTRAP_ADMIN_EMAIL")
                .hasMessageContaining("BOOTSTRAP_ADMIN_PASSWORD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-address", "missing@domain@example.invalid", "@example.invalid"})
    void anInvalidEmailStopsStartupWithoutQuotingIt(String invalid) {
        givenAccounts(0);

        assertThatThrownBy(() -> bootstrap(LIBRARY, USERNAME, invalid, PASSWORD).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOTSTRAP_ADMIN_EMAIL")
                .hasMessageNotContaining(invalid);

        verifyNoInteractions(libraries);
    }

    @Test
    void aPasswordThatBreaksTheApisRuleIsRefusedAndNeverQuoted() {
        givenAccounts(0);
        String tooShort = "short7!";

        assertThatThrownBy(() -> bootstrap(LIBRARY, USERNAME, EMAIL, tooShort).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOTSTRAP_ADMIN_PASSWORD")
                .hasMessageNotContaining(tooShort);
    }

    @Test
    void aUsernameOrLibraryNameThatBreaksTheApisRulesIsRefused() {
        givenAccounts(0);

        assertThatThrownBy(() -> bootstrap(LIBRARY, "ab", EMAIL, PASSWORD).run(null))
                .as("the username length the user endpoint requires")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOTSTRAP_ADMIN_USERNAME");

        assertThatThrownBy(() -> bootstrap("L".repeat(101), USERNAME, EMAIL, PASSWORD).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOTSTRAP_ADMIN_LIBRARY");
    }

    // ---------- two instances starting at once ----------

    @Test
    void losingTheRaceToAnotherInstanceIsNotAFailure() {
        when(users.count()).thenReturn(0L, 1L);
        when(libraries.createFirstLibrary(any())).thenThrow(new DataIntegrityViolationException("duplicate username"));

        assertThatCode(() -> configuredBootstrap().run(null)).doesNotThrowAnyException();
    }

    @Test
    void anIntegrityFailureWithTheTableStillEmptyStopsTheStart() {
        when(users.count()).thenReturn(0L, 0L);
        when(libraries.createFirstLibrary(any())).thenThrow(new DataIntegrityViolationException("something else"));

        assertThatThrownBy(() -> configuredBootstrap().run(null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- what it says ----------

    @Test
    void nothingSensitiveIsLogged() {
        givenAccounts(0);
        when(libraries.createFirstLibrary(any())).thenReturn(created());

        Logger logger = (Logger) LoggerFactory.getLogger(FirstAdminBootstrap.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            configuredBootstrap().run(null);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertThat(lines).as("it says it happened").anyMatch(line -> line.contains("Bootstrapped library id=7"));
        assertThat(lines).as("never the password, the email or a hash").allSatisfy(line -> assertThat(line)
                .doesNotContain(PASSWORD)
                .doesNotContain(EMAIL)
                .doesNotContain("$2a$"));
    }
}
