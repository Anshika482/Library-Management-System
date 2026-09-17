package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Proves the health endpoints work for a caller with no token, say nothing but
 * a status, and open no other door.
 *
 * <p><b>Reachable without a token</b>, because a load balancer or orchestrator
 * has none: overall health, liveness, readiness and info.</p>
 *
 * <p><b>Nothing but a status</b> - no components, no details, no database
 * product or URL - and an administrator's token does not change that.</p>
 *
 * <p><b>Readiness means something.</b> It includes the database and liveness
 * does not, which is checked against the configured groups, and it follows the
 * application's own availability: an instance that declares itself unable to
 * take traffic answers 503 there while liveness stays up. What a failed
 * database check looks like is in {@link ReadinessDatabaseDownIntegrationTest}.</p>
 *
 * <p><b>Nothing else is exposed</b>, and nothing about the API changed: every
 * other actuator endpoint is unreachable even for an administrator, anonymous
 * callers are refused before learning even that, only GET on the probes is
 * public, and the API still needs a token.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database, with a unique suffix per test.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class ActuatorHealthIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step155-test-only-password";

    /** Endpoints Spring Boot can provide, none of which may be reachable here. */
    private static final List<String> UNEXPOSED = List.of(
            "env", "configprops", "beans", "mappings", "conditions", "loggers", "metrics",
            "heapdump", "threaddump", "scheduledtasks", "caches", "flyway", "shutdown",
            "startup", "httpexchanges", "sbom", "prometheus", "auditevents", "sessions");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private HealthEndpointGroups healthEndpointGroups;

    private String adminToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createAnAdministrator() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("Step155 Library " + suffix);
        library = libraryRepository.save(library);

        User admin = new User();
        admin.setUsername("step155-admin-" + suffix);
        admin.setEmail("step155-admin-" + suffix + "@example.invalid");
        admin.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        admin.setRole(Role.ROLE_ADMIN);
        admin.setLibrary(library);
        admin = userRepository.save(admin);

        String body = objectMapper.createObjectNode()
                .put("username", admin.getUsername())
                .put("password", TEST_PASSWORD)
                .toString();
        MvcResult login = perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(status(login)).isEqualTo(200);
        adminToken = json(login).path("token").asText();
    }

    // ---------- helpers ----------

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** A health response that carries a status and, at the top level, the group names - nothing else. */
    private void assertStatusOnly(String label, MvcResult result, String expectedStatus) throws Exception {
        JsonNode body = json(result);

        assertThat(body.path("status").asText()).as(label).isEqualTo(expectedStatus);
        assertThat(fieldNames(body)).as("%s: no components, no details", label).isSubsetOf("status", "groups");

        String text = result.getResponse().getContentAsString().toLowerCase(Locale.ROOT);
        for (String leak : List.of("components", "details", "diskspace", "mysql", "jdbc", "library_db", "error")) {
            assertThat(text).as("%s must not mention %s", label, leak).doesNotContain(leak);
        }
    }

    // ---------- reachable without a token, saying nothing but a status ----------

    @Test
    void healthIsUpWithoutATokenAndCarriesOnlyAStatus() throws Exception {
        MvcResult result = perform(get("/actuator/health"));

        assertThat(status(result)).isEqualTo(200);
        assertStatusOnly("health", result, "UP");
    }

    @Test
    void livenessAndReadinessAreUpWithoutAToken() throws Exception {
        for (String path : List.of("/actuator/health/liveness", "/actuator/health/readiness")) {
            MvcResult result = perform(get(path));

            assertThat(status(result)).as(path).isEqualTo(200);
            assertStatusOnly(path, result, "UP");
            assertThat(fieldNames(json(result))).as(path).containsExactly("status");
        }
    }

    @Test
    void infoIsReachableWithoutATokenAndEmpty() throws Exception {
        MvcResult result = perform(get("/actuator/info"));

        assertThat(status(result)).isEqualTo(200);
        assertThat(json(result).isObject()).isTrue();
        assertThat(fieldNames(json(result))).as("no build, git, java, os, process or env information").isEmpty();
    }

    @Test
    void anAdministratorSeesNoMoreThanAnyoneElse() throws Exception {
        MvcResult health = asAdmin(get("/actuator/health"));
        assertThat(status(health)).isEqualTo(200);
        assertStatusOnly("health as administrator", health, "UP");

        assertThat(status(asAdmin(get("/actuator/health/db"))))
                .as("a single component is not available, even to an administrator")
                .isEqualTo(404);
        assertThat(status(perform(get("/actuator/health/db"))))
                .as("and it is not one of the public probe paths")
                .isEqualTo(401);
    }

    // ---------- readiness means something ----------

    @Test
    void readinessChecksTheDatabaseAndLivenessDoesNot() {
        HealthEndpointGroup readiness = healthEndpointGroups.get("readiness");
        HealthEndpointGroup liveness = healthEndpointGroups.get("liveness");

        assertThat(readiness).isNotNull();
        assertThat(liveness).isNotNull();

        assertThat(readiness.isMember("readinessState")).isTrue();
        assertThat(readiness.isMember("db")).as("no database, no requests served").isTrue();

        assertThat(liveness.isMember("livenessState")).isTrue();
        assertThat(liveness.isMember("db")).as("a restart does not fix a database").isFalse();
    }

    @Test
    void readinessFollowsTheApplicationsOwnAvailability() throws Exception {
        try {
            AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);

            MvcResult readiness = perform(get("/actuator/health/readiness"));
            assertThat(status(readiness)).as("out of rotation").isEqualTo(503);
            assertStatusOnly("readiness", readiness, "OUT_OF_SERVICE");

            MvcResult liveness = perform(get("/actuator/health/liveness"));
            assertThat(status(liveness)).as("but not to be restarted").isEqualTo(200);
        } finally {
            AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
        }

        assertThat(status(perform(get("/actuator/health/readiness")))).as("back in rotation").isEqualTo(200);
    }

    // ---------- nothing else exposed, nothing else changed ----------

    @Test
    void nothingButHealthAndInfoIsExposed() throws Exception {
        for (String endpoint : UNEXPOSED) {
            String path = "/actuator/" + endpoint;

            assertThat(status(perform(get(path)))).as("anonymous %s", path).isEqualTo(401);
            assertThat(status(asAdmin(get(path)))).as("administrator %s", path).isEqualTo(404);
        }

        assertThat(status(perform(get("/actuator")))).as("anonymous discovery page").isEqualTo(401);

        MvcResult discovery = asAdmin(get("/actuator"));
        assertThat(status(discovery)).isEqualTo(200);
        assertThat(fieldNames(json(discovery).path("_links")))
                .as("the only links are to health and info")
                .isSubsetOf("self", "health", "health-path", "info")
                .contains("health", "info");
    }

    @Test
    void onlyReadingTheProbesIsPublicAndTheApiStillNeedsAToken() throws Exception {
        assertThat(status(perform(post("/actuator/health")))).as("POST is not a probe").isEqualTo(401);
        assertThat(status(perform(get("/api/books")))).as("the API is unchanged").isEqualTo(401);

        MvcResult withBadToken = perform(get("/actuator/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"));
        assertThat(status(withBadToken)).as("a probe needs no token, so a bad one does not fail it").isEqualTo(200);
    }
}
