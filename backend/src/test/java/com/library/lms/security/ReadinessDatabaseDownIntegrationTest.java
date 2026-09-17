package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Proves a database outage takes the instance out of rotation - and that the
 * probe says so without saying anything about the database.
 *
 * <p>The real database check is replaced by one that fails the way a real
 * outage does, with an exception naming a host, an account and a JDBC URL, and
 * with details of its own. Spring Boot's own check steps aside for a bean with
 * this name, so the readiness group's {@code db} member is exactly this one.</p>
 *
 * <p>What a monitor then sees: readiness and overall health DOWN with a 503,
 * liveness still UP, and not a word of the failure's detail.</p>
 *
 * <p>A context of its own, because a failing health check must not leak into
 * the context the other integration tests share. <b>Isolation:</b> the same
 * throwaway schema they use, never the development database.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class ReadinessDatabaseDownIntegrationTest {

    /** The kind of text a real connection failure carries. All of it is invented. */
    private static final String FAILURE =
            "Access denied for user 'library_app'@'db.internal' - jdbc:mysql://db.internal:3306/library_db";

    @TestConfiguration
    static class FailingDatabaseCheck {

        @Bean
        HealthIndicator dbHealthIndicator() {
            return () -> Health.down(new SQLException(FAILURE))
                    .withDetail("database", "MySQL")
                    .withDetail("validationQuery", "isValid()")
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aDatabaseOutageTakesTheInstanceOutOfRotationWithoutDescribingIt() throws Exception {
        MvcResult readiness = mockMvc.perform(get("/actuator/health/readiness")).andReturn();
        assertThat(readiness.getResponse().getStatus()).as("readiness").isEqualTo(503);
        assertOnlyStatus("readiness", readiness, "DOWN");
        assertThat(fieldNames(json(readiness))).containsExactly("status");

        MvcResult health = mockMvc.perform(get("/actuator/health")).andReturn();
        assertThat(health.getResponse().getStatus()).as("health").isEqualTo(503);
        assertOnlyStatus("health", health, "DOWN");

        MvcResult liveness = mockMvc.perform(get("/actuator/health/liveness")).andReturn();
        assertThat(liveness.getResponse().getStatus())
                .as("liveness: restarting would not bring the database back")
                .isEqualTo(200);
        assertOnlyStatus("liveness", liveness, "UP");
    }

    private void assertOnlyStatus(String label, MvcResult result, String expectedStatus) throws Exception {
        JsonNode body = json(result);
        assertThat(body.path("status").asText()).as(label).isEqualTo(expectedStatus);
        assertThat(fieldNames(body)).as(label).isSubsetOf("status", "groups");

        String text = result.getResponse().getContentAsString().toLowerCase(Locale.ROOT);
        for (String leak : List.of("db.internal", "library_app", "jdbc", "access denied", "sqlexception",
                "mysql", "validationquery", "error", "details", "components")) {
            assertThat(text).as("%s must not mention %s", label, leak).doesNotContain(leak);
        }
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
