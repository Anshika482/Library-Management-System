package com.library.lms.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Which browser origins may call this API from another site, and with what.
 *
 * <p><b>Exact origins, from configuration.</b> The list comes from
 * {@code security.cors.allowed-origins}, which the CORS_ALLOWED_ORIGINS
 * environment variable supplies. There is no wildcard and no pattern: each
 * entry is one origin, compared exactly, and an entry that is anything else - a
 * {@code *}, a path, a trailing slash, credentials - stops the application at
 * startup instead of being interpreted generously. Nothing configured means no
 * cross-origin browser access at all.</p>
 *
 * <p><b>What is allowed is what the API uses.</b> The six methods its endpoints
 * answer, and the request headers a browser client has to send: Authorization
 * for the bearer token, Content-Type for a JSON body, and Accept. No response
 * headers are exposed, because nothing a client needs travels in one - the
 * token is returned in the login response body.</p>
 *
 * <p><b>No credentials.</b> The token is sent in the Authorization header,
 * which a browser includes because the client's script sets it, not because of
 * CORS credentials mode. Credentialed CORS would additionally let a listed
 * origin have cookies and HTTP authentication attached; this API uses neither,
 * and allowing what nothing needs only widens what a compromised listed origin
 * could do.</p>
 *
 * <p><b>One filter, placed by Spring Security.</b> {@code SecurityConfig}
 * enables CORS on the chain, and Spring Security then uses the
 * {@link #corsFilter} bean below, ahead of authentication.</p>
 */
@Configuration
public class CorsConfig {

    static final String ALLOWED_ORIGINS_PROPERTY = "security.cors.allowed-origins";

    static final List<String> ALLOWED_METHODS = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");

    static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Content-Type", "Accept");

    /** How long a browser may reuse a successful preflight before asking again. */
    static final Duration PREFLIGHT_CACHE = Duration.ofHours(1);

    /**
     * The one CORS filter in the application.
     *
     * <p><b>Named {@code corsFilter} on purpose.</b> When a security chain
     * enables CORS, Spring Security uses a bean of exactly this name if one
     * exists, and places it where CORS belongs. Supplying the whole filter,
     * rather than only its rules, is what lets it carry
     * {@link RestCorsProcessor}; given bare rules, Spring Security builds a
     * filter of its own, which refuses in plain text.</p>
     *
     * <p>The rules are built here and not exposed as a bean of their own. The
     * filter is their only user, and a second route to CORS is how a second
     * filter ends up in front of the API.</p>
     *
     * @param allowedOrigins    the comma-separated origins configured for this
     *                          deployment, possibly empty
     * @param restCorsProcessor writes refusals in the API's error shape
     * @return the filter the security chain uses
     * @throws IllegalStateException if an origin entry is not exactly an origin
     */
    @Bean
    public CorsFilter corsFilter(@Value("${" + ALLOWED_ORIGINS_PROPERTY + ":}") String allowedOrigins,
            RestCorsProcessor restCorsProcessor) {
        CorsFilter filter = new CorsFilter(corsConfigurationSource(allowedOrigins));
        filter.setCorsProcessor(restCorsProcessor);
        return filter;
    }

    /**
     * Keeps the servlet container from running the CORS filter a second time.
     *
     * <p>Spring Boot registers every {@code Filter} bean with the container
     * automatically. This one belongs inside the security chain only - the same
     * arrangement as the JWT filter - so its automatic registration is
     * switched off.</p>
     *
     * @param corsFilter the single filter instance
     * @return a disabled registration, which suppresses the automatic one
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(CorsFilter corsFilter) {
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(corsFilter);
        registration.setEnabled(false);

        return registration;
    }

    /**
     * The CORS rules for every API path.
     *
     * <p>Registered even when no origin is configured. With a configuration in
     * place, a cross-origin request from an unlisted origin is refused before it
     * runs; with none, Spring would refuse only the preflight and let a simple
     * cross-origin request through to be executed, merely hiding the response
     * from the page that sent it.</p>
     *
     * @param allowedOrigins the comma-separated origins, possibly empty
     * @return the rules, for paths under {@code /api/}
     * @throws IllegalStateException if an entry is not exactly an origin
     */
    static CorsConfigurationSource corsConfigurationSource(String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins(allowedOrigins));
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(PREFLIGHT_CACHE);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * Parses and checks the configured origins.
     *
     * <p>Entries are trimmed, blank ones are skipped and repeats collapse to
     * one. Every remaining entry must look exactly like an Origin header does:
     * an http or https scheme, a host, an optional port, and nothing after
     * them. That is checked here, at startup, because the alternative is finding
     * out from a browser console - or not finding out, if the mistake happens to
     * allow more than intended.</p>
     *
     * <p><b>No message quotes an entry.</b> An entry is refused because it is
     * not what it should be, and one carrying {@code user:password@} is exactly
     * that; naming its position is enough to find it.</p>
     *
     * @param configured the raw property value
     * @return the origins, in the order given, without repeats
     * @throws IllegalStateException if an entry holds a wildcard or is not an
     *                               origin
     */
    static List<String> allowedOrigins(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }

        Set<String> origins = new LinkedHashSet<>();
        String[] entries = configured.split(",");

        for (int index = 0; index < entries.length; index++) {
            String entry = entries[index].trim();
            if (entry.isEmpty()) {
                continue;
            }

            int position = index + 1;

            if (entry.contains("*")) {
                throw new IllegalStateException(ALLOWED_ORIGINS_PROPERTY + " entry " + position
                        + " contains a wildcard. List every allowed origin exactly: a wildcard would let"
                        + " pages from any site it matches call this API from a user's browser.");
            }
            if (!isExactOrigin(entry)) {
                throw new IllegalStateException(ALLOWED_ORIGINS_PROPERTY + " entry " + position
                        + " is not an origin. Each entry must be exactly scheme://host or"
                        + " scheme://host:port, with an http or https scheme and nothing after it - no path,"
                        + " no trailing slash, no query and no credentials.");
            }

            origins.add(entry);
        }

        return List.copyOf(origins);
    }

    private static boolean isExactOrigin(String entry) {
        URI uri;
        try {
            uri = new URI(entry);
        } catch (URISyntaxException exception) {
            return false;
        }

        String scheme = uri.getScheme();

        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
    }
}
