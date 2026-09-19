package com.library.lms.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.library.lms.dto.CreateLibraryRequest;
import com.library.lms.dto.FirstAdminRequest;
import com.library.lms.dto.LibraryResponse;
import com.library.lms.repository.UserRepository;
import com.library.lms.service.LibraryService;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

/**
 * Creates the first library and administrator of an empty database, once.
 *
 * <p><b>The circle it breaks.</b> Every endpoint that creates an account needs
 * an administrator who is already signed in, so a fresh deployment has no way
 * to make its first one. Doing it by hand means writing a BCrypt hash and a UTC
 * timestamp into the table correctly; doing it with an open endpoint would mean
 * shipping a way to create an administrator without being one. This runs at
 * startup instead, reads four environment variables, and hands the work to
 * {@link LibraryService}, which applies exactly the rules the API applies.</p>
 *
 * <p><b>Only into an empty table.</b> The account table is counted first, and a
 * single existing account is enough to stop: nothing is created, and the
 * settings are not even read, so a running deployment that still carries them
 * in its environment is unaffected. Configuration cannot add an administrator
 * to a database that has one.</p>
 *
 * <p><b>Required only when it runs.</b> The four settings default to blank, so
 * an ordinary start needs none of them. When the table is empty they are all
 * required, and an incomplete or invalid set stops the application - with a
 * message that names the variables and never their values.</p>
 *
 * <p><b>Nothing sensitive is logged.</b> Not the password, not the hash, not
 * the email address; the one line written on success carries ids and the
 * username, which is what the rest of the application logs too.</p>
 */
@Component
public class FirstAdminBootstrap implements ApplicationRunner {

    static final String LIBRARY_PROPERTY = "bootstrap.admin.library";

    static final String USERNAME_PROPERTY = "bootstrap.admin.username";

    static final String EMAIL_PROPERTY = "bootstrap.admin.email";

    static final String PASSWORD_PROPERTY = "bootstrap.admin.password";

    /** Which environment variable stands behind each field, for messages that name one. */
    private static final Map<String, String> VARIABLES = Map.of(
            "name", "BOOTSTRAP_ADMIN_LIBRARY",
            "admin.username", "BOOTSTRAP_ADMIN_USERNAME",
            "admin.email", "BOOTSTRAP_ADMIN_EMAIL",
            "admin.password", "BOOTSTRAP_ADMIN_PASSWORD");

    private static final Logger log = LoggerFactory.getLogger(FirstAdminBootstrap.class);

    private final UserRepository userRepository;

    private final LibraryService libraryService;

    private final Validator validator;

    private final String libraryName;

    private final String username;

    private final String email;

    private final String password;

    public FirstAdminBootstrap(UserRepository userRepository, LibraryService libraryService, Validator validator,
            @Value("${" + LIBRARY_PROPERTY + ":}") String libraryName,
            @Value("${" + USERNAME_PROPERTY + ":}") String username,
            @Value("${" + EMAIL_PROPERTY + ":}") String email,
            @Value("${" + PASSWORD_PROPERTY + ":}") String password) {
        this.userRepository = userRepository;
        this.libraryService = libraryService;
        this.validator = validator;
        this.libraryName = libraryName;
        this.username = username;
        this.email = email;
        this.password = password;
    }

    /**
     * Creates the first library and administrator if, and only if, there are no
     * accounts.
     *
     * <p>Running as an {@link ApplicationRunner} puts this after the context is
     * built and the migrations have run, but before the application reports
     * itself ready - so a configuration problem here stops the start rather
     * than surfacing as a failed login later.</p>
     *
     * <p>The race between two instances starting against the same empty
     * database is settled by the unique index on the username: the one that
     * loses sees an integrity violation, finds the table no longer empty, and
     * carries on. If the table is still empty the failure was something else
     * and is allowed to stop the start.</p>
     *
     * @throws IllegalStateException if the table is empty and the settings are
     *                               missing or invalid
     */
    @Override
    public void run(ApplicationArguments args) {
        long accounts = userRepository.count();

        if (accounts > 0) {
            log.debug("First-administrator bootstrap skipped: {} account(s) already exist", accounts);
            return;
        }

        log.info("No accounts found; creating the first library and administrator from configuration");

        CreateLibraryRequest request = configuredRequest();

        try {
            LibraryResponse created = libraryService.createFirstLibrary(request);

            log.info("Bootstrapped library id={} with its first administrator id={} (username='{}')",
                    created.getId(), created.getAdmin().getId(), created.getAdmin().getUsername());
        } catch (DataIntegrityViolationException exception) {
            if (userRepository.count() > 0) {
                log.info("Another instance created the first administrator first; nothing to do");
                return;
            }

            throw exception;
        }
    }

    /**
     * Builds the request from configuration and refuses anything the API would
     * refuse.
     *
     * <p>The constraints are the request objects' own, checked with the
     * application's validator, so what the bootstrap accepts cannot drift from
     * what {@code POST /api/libraries} accepts. Neither object has a field for a
     * role or a library id, which is why configuration cannot ask for either -
     * the role is always ADMIN and the library is always the one created
     * alongside.</p>
     *
     * <p>Messages name the variable and the rule it broke. No value is quoted:
     * a startup failure that printed the password would put it in every log
     * collector the deployment has.</p>
     */
    private CreateLibraryRequest configuredRequest() {
        List<String> missing = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(LIBRARY_PROPERTY, libraryName);
        values.put(USERNAME_PROPERTY, username);
        values.put(EMAIL_PROPERTY, email);
        values.put(PASSWORD_PROPERTY, password);

        values.forEach((property, value) -> {
            if (value == null || value.isBlank()) {
                missing.add(variableFor(property));
            }
        });

        if (!missing.isEmpty()) {
            throw new IllegalStateException("The account table is empty, so the first library and administrator"
                    + " have to be created at startup, but " + String.join(", ", missing) + " "
                    + (missing.size() == 1 ? "is" : "are") + " not set. Set all of BOOTSTRAP_ADMIN_LIBRARY,"
                    + " BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD.");
        }

        FirstAdminRequest admin = new FirstAdminRequest();
        admin.setUsername(username.trim());
        admin.setEmail(email.trim());
        admin.setPassword(password);

        CreateLibraryRequest request = new CreateLibraryRequest();
        request.setName(libraryName.trim());
        request.setAdmin(admin);

        Set<ConstraintViolation<CreateLibraryRequest>> violations = validator.validate(request);

        if (!violations.isEmpty()) {
            List<String> problems = violations.stream()
                    .map(violation -> VARIABLES.getOrDefault(violation.getPropertyPath().toString(),
                            violation.getPropertyPath().toString()) + ": " + violation.getMessage())
                    .sorted()
                    .toList();

            throw new IllegalStateException("The first library and administrator cannot be created from the"
                    + " configured values: " + String.join("; ", problems));
        }

        return request;
    }

    private static String variableFor(String property) {
        return "BOOTSTRAP_ADMIN_" + property.substring(property.lastIndexOf('.') + 1).toUpperCase();
    }
}
