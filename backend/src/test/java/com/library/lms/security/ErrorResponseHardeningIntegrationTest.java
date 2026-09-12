package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Checks that every failure this API can produce comes back as its own JSON,
 * carrying nothing about the server that produced it.
 *
 * <p><b>A real port, not MockMvc.</b> The failure that mattered most cannot be
 * reproduced through MockMvc at all: a URL containing an encoded slash is
 * rejected by Tomcat while parsing the request line, before any filter,
 * servlet or controller exists. MockMvc has no connector and never sees it.
 * Driven over a real socket, that request used to return Tomcat's HTML error
 * page naming the exact server version.</p>
 *
 * <p>Every response body here is checked against one list of forbidden
 * substrings - server names and versions, package and class names, stack-trace
 * markers, SQL, filesystem paths, HTML - rather than only against the message
 * that was expected. An assertion about what a body <i>should</i> say cannot
 * notice an extra field that says too much.</p>
 *
 * <p><b>The logging tests read the log, not the response.</b> Security logging
 * is only useful if it records the event and not the credential, so the last
 * two tests attach an appender, drive a real failed login and a real rejected
 * token, and assert the credential never appears while the event does.</p>
 *
 * <p><b>Isolation:</b> the same throwaway schema the other integration tests
 * use, never the development database. Fixtures carry a unique suffix, so
 * repeated runs cannot collide.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                        + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                        + "&serverTimezone=UTC",
                "spring.jpa.hibernate.ddl-auto=update",
                "spring.jpa.open-in-view=false"
        })
class ErrorResponseHardeningIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step143-test-only-password";

    /**
     * Nothing a response body may contain.
     *
     * <p>Server and framework names, package prefixes, stack-trace markers, SQL,
     * filesystem paths and markup. Checked case-insensitively against every
     * error body below.</p>
     */
    private static final List<String> FORBIDDEN = List.of(
            "tomcat", "apache", "coyote", "catalina",
            "java.", "javax.", "jakarta.", "org.springframework", "com.library.lms",
            "exception", "stacktrace", "at com.", "caused by",
            "select ", "insert ", "update ", "jdbc", "hibernate", "mysql",
            "c:\\", "/users/", "/home/", "<html", "<!doctype", "servlet");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String suffix;
    private User librarian;
    private User member;
    private String librarianToken;
    private String memberToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createUsersAndLogIn() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("Step143 Library " + suffix);
        library = libraryRepository.save(library);

        librarian = persistUser(library, Role.ROLE_LIBRARIAN);
        member = persistUser(library, Role.ROLE_MEMBER);

        librarianToken = token(login(librarian.getUsername(), TEST_PASSWORD));
        memberToken = token(login(member.getUsername(), TEST_PASSWORD));
    }

    private User persistUser(Library library, Role role) {
        String username = "step143-" + role.name().toLowerCase().replace("role_", "") + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step143 " + role.name());
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    // ---------- helpers ----------

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private ResponseEntity<String> login(String username, String password) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", username)
                .put("password", password)
                .toString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        return rest.exchange(uri("/api/auth/login"), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String token(ResponseEntity<String> loginResponse) throws Exception {
        assertThat(loginResponse.getStatusCode().value()).as("login must succeed").isEqualTo(200);
        return objectMapper.readTree(loginResponse.getBody()).path("token").asText();
    }

    private ResponseEntity<String> get(String path, String token) {
        return getUri(uri(path), token);
    }

    /** For paths a URI template would re-encode, such as one holding {@code %2F}. */
    private ResponseEntity<String> getUri(URI uri, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /**
     * Asserts the response is this API's JSON error shape, says exactly what is
     * expected, and discloses nothing about the server.
     */
    private void assertSafeError(String label, ResponseEntity<String> response, int status, String message)
            throws Exception {
        assertThat(response.getStatusCode().value()).as("%s status", label).isEqualTo(status);
        assertSafeBody(label, response);

        JsonNode body = objectMapper.readTree(response.getBody());
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).as("%s fields", label).containsExactlyInAnyOrder("status", "message", "timestamp");
        assertThat(body.path("status").asInt()).as("%s json status", label).isEqualTo(status);
        assertThat(body.path("message").asText()).as("%s message", label).isEqualTo(message);
        assertThat(body.path("timestamp").asText()).as("%s timestamp", label).isNotBlank();
    }

    /** Asserts a body is JSON and free of anything describing the server. */
    private void assertSafeBody(String label, ResponseEntity<String> response) {
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).as("%s content type", label).isNotNull();
        assertThat(contentType.getType() + "/" + contentType.getSubtype())
                .as("%s content type", label).isEqualTo(MediaType.APPLICATION_JSON_VALUE);

        String body = response.getBody() == null ? "" : response.getBody().toLowerCase();
        for (String forbidden : FORBIDDEN) {
            assertThat(body).as("%s body must not contain '%s'", label, forbidden).doesNotContain(forbidden);
        }
    }

    // ---------- the errors an API client can provoke ----------

    @Test
    void aFailedLoginStillReturnsTheSameSafe401() throws Exception {
        assertSafeError("failed login", login(librarian.getUsername(), "wrong-password-" + suffix),
                401, "Invalid username or password");
    }

    @Test
    void anInvalidJwtStillReturnsASafe401() throws Exception {
        assertSafeError("invalid token", get("/api/books", "not.a.valid.token"),
                401, "Authentication required");
        assertSafeError("no token", get("/api/books", null),
                401, "Authentication required");
    }

    @Test
    void aMemberOnAStaffEndpointGetsASafe403() throws Exception {
        assertSafeError("member on staff endpoint", get("/api/transactions/status/ISSUED", memberToken),
                403, "Access denied.");
    }

    @Test
    void anUnknownPathGetsASafe404() throws Exception {
        assertSafeError("unknown path", get("/api/definitely-not-here-" + suffix, librarianToken),
                404, "Resource not found.");
    }

    @Test
    void aMalformedPathReturnsJsonRatherThanTheContainersHtmlErrorPage() throws Exception {
        // Rejected by the connector while parsing the request line, so it never
        // reaches Spring. This used to return Tomcat's HTML page, which names
        // the exact server version.
        ResponseEntity<String> response = getUri(uri("/api/books/%2F1"), librarianToken);

        assertThat(response.getStatusCode().value()).as("malformed path").isEqualTo(400);
        assertSafeBody("malformed path", response);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText()).isEqualTo("The request could not be processed.");
        assertThat(body.path("timestamp").asText()).isNotBlank();
    }

    @Test
    void theContainersErrorDispatchReturnsThisApisShape() throws Exception {
        // Failures raised in a filter never reach the controller advice; the
        // container dispatches them here instead.
        assertSafeError("error dispatch", get("/error", librarianToken),
                500, "An unexpected error occurred. Please try again later.");
    }

    @Test
    void aRejectedSortParameterNoLongerEchoesWhatWasSent() throws Exception {
        // A marker that could not plausibly appear by chance, so finding it in
        // the reply would prove the value was echoed back.
        String marker = "step143marker" + suffix;
        ResponseEntity<String> response = get("/api/books?sortBy=" + marker, librarianToken);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertSafeBody("rejected sort field", response);
        assertThat(objectMapper.readTree(response.getBody()).path("message").asText())
                .as("names what is allowed, not what was sent")
                .isEqualTo("Unsupported sort field. Allowed fields are: "
                        + "author, availableCopies, id, isbn, title, totalCopies")
                .doesNotContain(marker);
    }

    @Test
    void validRequestsAreUnaffected() throws Exception {
        ResponseEntity<String> response = get("/api/books", librarianToken);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.has("content")).as("a paged response").isTrue();
        assertThat(body.path("totalElements").asLong()).isZero();
    }

    // ---------- what reaches the log, and what must not ----------

    @Test
    void aFailedLoginIsLoggedWithoutThePasswordAndWithoutForgingALogLine() throws Exception {
        String password = "step143-password-that-must-never-be-logged";
        String username = "step143-evil\nINFO forged log line";

        List<ILoggingEvent> events = captureLogsWhile(() -> login(username, password));

        assertThat(ourMessages(events))
                .as("the attempt is recorded")
                .anyMatch(line -> line.contains("Login failed") && line.contains("step143-evil"));
        assertThat(messages(events))
                .as("the password must never appear, in any log line from anywhere")
                .noneMatch(line -> line.contains(password));
        assertThat(ourMessages(events))
                .as("a newline in the username must not break one of this application's log lines")
                .noneMatch(line -> line.contains("\n"));
    }

    @Test
    void aRejectedTokenIsLoggedWithoutTheTokenItself() throws Exception {
        String token = "step143.invalid.token-" + suffix;

        List<ILoggingEvent> events = captureLogsWhile(() -> get("/api/books", token));

        assertThat(messages(events))
                .as("the rejection is recorded")
                .anyMatch(line -> line.contains("Rejected bearer token"));
        assertThat(messages(events))
                .as("the token is a credential and must never appear")
                .noneMatch(line -> line.contains(token));
    }

    /** Runs an action with an appender attached to the root logger. */
    private List<ILoggingEvent> captureLogsWhile(ThrowingRunnable action) throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        try {
            action.run();
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        return List.copyOf(appender.list);
    }

    private static List<String> messages(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * The lines this application logged, ignoring the frameworks underneath it.
     *
     * <p>Hibernate prints its SQL across several lines - {@code format_sql} is
     * on - so "no log line contains a newline" is false of the log as a whole
     * and says nothing about log injection. The question is whether a username
     * can break one of <i>our</i> lines, so the check is scoped to our loggers.
     * The credential checks above stay deliberately unscoped: a password must
     * not appear in anyone's output, ours or a framework's.</p>
     */
    private static List<String> ourMessages(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> event.getLoggerName().startsWith("com.library.lms"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
