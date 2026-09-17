package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The CORS rules on their own: which configured origins are accepted, what the
 * resulting configuration allows, and what happens when nothing is configured.
 *
 * <p>No application context. The origin rules are checked directly with
 * strings, and the "nothing configured" case is run through a real
 * {@link CorsFilter} with mock requests - the same filter and processor the
 * security chain uses, without standing up a database to prove a refusal.</p>
 *
 * <p>The behaviour through the full chain, with origins configured, is in
 * {@code CorsIntegrationTest}.</p>
 */
class CorsConfigTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    // ---------- accepted origins ----------

    @Test
    void exactOriginsAreAcceptedTrimmedAndWithoutRepeats() {
        assertThat(CorsConfig.allowedOrigins(
                " https://library.example.org , http://localhost:5173,, https://library.example.org "))
                .containsExactly("https://library.example.org", "http://localhost:5173");
    }

    @Test
    void anOriginMayCarryAPortAndAnyCase() {
        assertThat(CorsConfig.allowedOrigins("HTTPS://Library.Example.org:8443,http://127.0.0.1:3000"))
                .containsExactly("HTTPS://Library.Example.org:8443", "http://127.0.0.1:3000");
    }

    @Test
    void nothingConfiguredMeansNoOrigins() {
        assertThat(CorsConfig.allowedOrigins(null)).isEmpty();
        assertThat(CorsConfig.allowedOrigins("")).isEmpty();
        assertThat(CorsConfig.allowedOrigins("   ")).isEmpty();
        assertThat(CorsConfig.allowedOrigins(" , ,")).isEmpty();
    }

    // ---------- refused origins ----------

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.org", "http://localhost:*", "https://example.org, *"})
    void aWildcardStopsStartup(String configured) {
        assertThatThrownBy(() -> CorsConfig.allowedOrigins(configured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard")
                .hasMessageContaining(CorsConfig.ALLOWED_ORIGINS_PROPERTY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost:5173",
            "example.org",
            "ftp://example.org",
            "file:///srv/app",
            "https://",
            "https://example.org/",
            "https://example.org/app",
            "https://example.org?x=1",
            "https://example.org#top",
            "https://user:secret@example.org",
            "https://exa mple.org",
            "null"})
    void anythingThatIsNotExactlyAnOriginStopsStartup(String configured) {
        assertThatThrownBy(() -> CorsConfig.allowedOrigins(configured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry 1 is not an origin");
    }

    @Test
    void aRefusalNamesTheEntryButNeverQuotesIt() {
        assertThatThrownBy(() -> CorsConfig.allowedOrigins(
                "https://ok.example.org, https://admin:hunter2@bad.example.org"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry 2")
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("bad.example.org");
    }

    // ---------- what the configuration allows ----------

    @Test
    void theConfigurationAllowsOnlyWhatTheApiUses() {
        CorsConfigurationSource source = CorsConfig.corsConfigurationSource("https://app.example.test");

        CorsConfiguration configuration = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/books"));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly("https://app.example.test");
        assertThat(configuration.getAllowedOriginPatterns()).as("no patterns, only exact origins").isNull();
        assertThat(configuration.getAllowedMethods())
                .containsExactly("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
        assertThat(configuration.getAllowedHeaders()).containsExactly("Authorization", "Content-Type", "Accept");
        assertThat(configuration.getExposedHeaders()).as("nothing a client needs is in a header").isNull();
        assertThat(configuration.getAllowCredentials()).as("bearer tokens, not cookies").isFalse();
        assertThat(configuration.getMaxAge()).isEqualTo(3600L);

        assertThat(source.getCorsConfiguration(new MockHttpServletRequest("GET", "/error")))
                .as("only API paths are configured")
                .isNull();
    }

    // ---------- nothing configured ----------

    @Test
    void withNoOriginsConfiguredEveryCrossOriginRequestIsRefusedBeforeTheApi() throws Exception {
        CorsFilter filter = new CorsConfig().corsFilter("", new RestCorsProcessor(objectMapper));

        for (boolean preflight : List.of(true, false)) {
            MockHttpServletRequest request = new MockHttpServletRequest(preflight ? "OPTIONS" : "GET", "/api/books");
            request.addHeader(HttpHeaders.ORIGIN, "https://app.example.test");
            if (preflight) {
                request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).as("preflight=%s", preflight).isEqualTo(403);
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
            assertThat(chain.getRequest()).as("the request went no further").isNull();

            JsonNode body = objectMapper.readTree(response.getContentAsString());
            assertThat(body.path("status").asInt()).isEqualTo(403);
            assertThat(body.path("message").asText()).isEqualTo(RestCorsProcessor.MESSAGE);
            assertThat(body.path("timestamp").isMissingNode()).isFalse();
        }
    }

    @Test
    void withNoOriginsConfiguredSameOriginAndOriginlessRequestsPassThrough() throws Exception {
        CorsFilter filter = new CorsConfig().corsFilter("", new RestCorsProcessor(objectMapper));

        MockHttpServletRequest sameOrigin = new MockHttpServletRequest("GET", "/api/books");
        sameOrigin.addHeader(HttpHeaders.ORIGIN, "http://localhost");
        MockHttpServletRequest noOrigin = new MockHttpServletRequest("GET", "/api/books");

        for (MockHttpServletRequest request : List.of(sameOrigin, noOrigin)) {
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(chain.getRequest()).as("not a cross-origin request, so not CORS's to refuse").isNotNull();
        }
    }

    @Test
    void theDefaultComesFromTheEnvironmentAndIsEmpty() throws Exception {
        Properties development = new Properties();
        try (InputStream file = CorsConfigTest.class.getResourceAsStream("/application.properties")) {
            assertThat(file).isNotNull();
            development.load(file);
        }

        assertThat(development.getProperty(CorsConfig.ALLOWED_ORIGINS_PROPERTY))
                .isEqualTo("${CORS_ALLOWED_ORIGINS:}");
    }
}
