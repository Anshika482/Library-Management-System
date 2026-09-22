package com.library.lms.config;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.exception.GlobalExceptionHandler.ErrorResponse;

/**
 * Security configuration for the REST API.
 *
 * <p>Adding spring-boot-starter-security in the previous step immediately
 * locked the whole application down: Spring Boot auto configures a default
 * filter chain that demands HTTP Basic credentials on every endpoint. That
 * default suits a servlet app with a login page and is useless for a JSON API,
 * so this class replaces it with an explicit chain we control.</p>
 *
 * <p><b>Authentication is enforced from here on.</b> Every endpoint
 * except the login route now requires a caller to present a valid JWT.
 * Writing to the catalogue is restricted to administrators and
 * librarians; reading it needs only a valid token.</p>
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Authority strings, which must stay identical to the names of the
     * {@code Role} enum constants.
     *
     * <p>The user service builds each authority from {@code role.name()}, so
     * these values already carry the {@code ROLE_} prefix. That is why the
     * rules below call {@code hasAnyAuthority} and never {@code hasAnyRole}:
     * the latter prepends {@code ROLE_} itself and would look for
     * {@code ROLE_ROLE_ADMIN}, an authority no account will ever hold, which
     * fails closed and silently locks out the very people it names.</p>
     */
    private static final String ADMIN = "ROLE_ADMIN";

    private static final String LIBRARIAN = "ROLE_LIBRARIAN";

    /**
     * Defines the single filter chain every request passes through.
     *
     * <p>Declaring this bean is what makes Spring Boot stand down. Its
     * {@code SpringBootWebSecurityConfiguration} installs the default
     * username-and-password chain only when no {@link SecurityFilterChain} bean
     * of our own exists, so this method replaces that default outright.</p>
     *
     * <p><b>Accounts come from the database.</b> Spring Boot would otherwise
     * create a throwaway in memory user and log a generated password at
     * startup. That comes from {@code UserDetailsServiceAutoConfiguration},
     * which backs off as soon as a UserDetailsService bean exists, and this
     * application supplies one that reads the users table. The default account
     * is no longer created: a test run reports zero generated password lines
     * and no InMemoryUserDetailsManager, so the database is the only source of
     * accounts.</p>
     *
     * <p>Authorization is now two rules rather than one blanket allowance.
     * See the list below.</p>
     *
     * <p>Each setting, and why:</p>
     * <ul>
     *   <li><b>CSRF disabled</b> - cross site request forgery protection works
     *       by issuing a token to a server rendered form and checking it comes
     *       back. There are no forms here, and a stateless REST client cannot
     *       hold that token, so the check would reject every POST, PUT and
     *       DELETE while protecting nothing.</li>
     *   <li><b>Form login disabled</b> - the default chain redirects
     *       unauthenticated callers to an HTML login page. An API client wants
     *       a status code, not a page of markup.</li>
     *   <li><b>HTTP Basic disabled</b> - this project will authenticate with
     *       JWT later. Leaving Basic enabled would keep a second, weaker way in
     *       that nobody intends to use.</li>
     *   <li><b>Login public, writes restricted, everything else
     *       authenticated</b> - the public auth matchers name one method and
     *       exact paths, so only {@code POST} on login, refresh and logout is
     *       open; refresh and logout carry their own credential, the refresh
     *       token, because the access token may already have expired. A
     *       pattern such as {@code /api/auth/**} would have been shorter and
     *       would have quietly exposed every future route under that prefix,
     *       which is how a password reset endpoint ends up public by
     *       accident.</li>
     *   <li><b>Creating, editing and deleting</b> books and categories, and
     *       issuing or returning a book, require ADMIN or LIBRARIAN. Reading
     *       any of them requires only authentication, so a member can browse
     *       the catalogue and see loans without being able to change
     *       anything. Rules are declared specific first and the catch-all
     *       last, because the first match wins: putting
     *       {@code anyRequest()} earlier would swallow every rule after
     *       it.</li>
     *   <li><b>401 for unauthenticated callers</b> - with form login and
     *       HTTP Basic both off, Spring Security has no entry point of its
     *       own and falls back to {@code Http403ForbiddenEntryPoint}, which
     *       answers an empty 403. That is the wrong signal: 403 means "you
     *       are known and still may not", while these callers simply sent no
     *       credentials. See {@link #restAuthenticationEntryPoint}.</li>
     *   <li><b>403 for authenticated callers without the role</b> - a
     *       caller who presented a valid token but lacks the authority an
     *       endpoint demands. Left to Spring Security's default handler this
     *       was a 403 with no body and no content type, so the filter chain
     *       answered in a different shape from every controller. See
     *       {@link RestAccessDeniedHandler}.</li>
     *   <li><b>Health probes public</b> - {@code GET} on the health, liveness,
     *       readiness and info paths needs no token, because a load balancer or
     *       orchestrator has none. Only those exact paths and only GET: every
     *       other actuator path falls to the catch-all, and Actuator exposes
     *       nothing else in any case. The responses carry a status and nothing
     *       more - see application.properties.</li>
     *   <li><b>CORS answered first</b> - {@link CorsConfig} decides which other
     *       sites a browser may call this API from. Its filter sits ahead of
     *       authentication, so a browser's preflight, which never carries a
     *       token, is answered instead of refused with a 401, and a request from
     *       an origin that is not listed is refused before any token is read.
     *       See {@link RestCorsProcessor}.</li>
     *   <li><b>JWT filter inserted</b> - {@link JwtAuthenticationFilter} runs
     *       ahead of {@link UsernamePasswordAuthenticationFilter}, the slot
     *       Spring Security reserves for whatever establishes identity. It
     *       reads a Bearer token and populates the security context, so a
     *       later authorization rule has something to judge. Placing it after
     *       that point would leave the context empty at the moment the
     *       decision is made.</li>
     * </ul>
     *
     * @param http                    the builder Spring Security hands us
     *                               to describe the chain
     * @param jwtAuthenticationFilter the filter that reads a Bearer token,
     *                               inserted into the chain below
     * @return the configured chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthenticationEntryPoint restAuthenticationEntryPoint,
            AccessDeniedHandler restAccessDeniedHandler) throws Exception {
        http
                // Uses the corsFilter bean from CorsConfig - Spring Security looks
                // for exactly that name - and places it ahead of authentication,
                // so a browser's preflight is answered rather than refused for
                // carrying no token, and an unlisted origin is refused before any
                // token is read.
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // Every request carries its own JWT, so the server has no reason
                // to remember anyone between calls. Without this the default
                // policy is IF_REQUIRED and Tomcat hands out a JSESSIONID on
                // every response - wasted state, and worse, it leaves the
                // csrf-disable above resting on the assumption that nothing is
                // ever session-borne. STATELESS makes that assumption explicit
                // and enforced rather than incidental.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                        // For a caller whose access token may already have expired:
                        // the refresh token in the body is the credential, and
                        // RefreshTokenService checks it. POST and these exact paths only.
                        .requestMatchers(HttpMethod.POST, "/api/auth/refresh", "/api/auth/logout").permitAll()

                        // Self-service password reset: asked for and redeemed by
                        // someone who, by definition, cannot sign in. The address
                        // and the token are the only inputs, and neither answer
                        // says whether an account exists.
                        .requestMatchers(HttpMethod.POST, "/api/auth/forgot-password", "/api/auth/reset-password")
                                .permitAll()

                        // Health probes, for callers that have no token: a load
                        // balancer, an orchestrator, a monitor. GET only, and these
                        // exact paths only - a health component path such as
                        // /actuator/health/db, and everything else under /actuator,
                        // still needs authentication below.
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness", "/actuator/info").permitAll()

                        .requestMatchers(HttpMethod.POST, "/api/books").hasAnyAuthority(ADMIN, LIBRARIAN)
                        .requestMatchers(HttpMethod.PUT, "/api/books/**").hasAnyAuthority(ADMIN, LIBRARIAN)
                        .requestMatchers(HttpMethod.DELETE, "/api/books/**").hasAnyAuthority(ADMIN, LIBRARIAN)

                        .requestMatchers(HttpMethod.POST, "/api/categories").hasAnyAuthority(ADMIN, LIBRARIAN)
                        .requestMatchers(HttpMethod.PUT, "/api/categories/**").hasAnyAuthority(ADMIN, LIBRARIAN)
                        .requestMatchers(HttpMethod.DELETE, "/api/categories/**").hasAnyAuthority(ADMIN, LIBRARIAN)

                        .requestMatchers(HttpMethod.POST, "/api/transactions/issue").hasAnyAuthority(ADMIN, LIBRARIAN)
                        .requestMatchers(HttpMethod.POST, "/api/transactions/*/return").hasAnyAuthority(ADMIN, LIBRARIAN)

                        // Recording a fine as paid at the desk is staff work: a member
                        // who could do it would clear their own fine just by saying
                        // so. No method named, so every verb on the path is covered.
                        .requestMatchers("/api/transactions/*/fine-payment").hasAnyAuthority(ADMIN, LIBRARIAN)

                        // Paying a fine through the provider is open to any signed-in
                        // caller, because the member who owes it is the one who pays.
                        // Saying so here is not saying they may pay any fine: the
                        // rule about whose loan it is lives in PaymentService, which
                        // is the only layer that knows, and a member is refused any
                        // loan but their own. Nothing here marks a fine paid - only a
                        // signature verified on the server does that.
                        .requestMatchers(HttpMethod.POST, "/api/transactions/*/payment-order").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/transactions/*/payment-verification").authenticated()

                        .requestMatchers(HttpMethod.GET, "/api/books/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/categories/**").authenticated()
                        // No HTTP method on these two, deliberately. A GET-only rule does
                        // not match HEAD, yet Spring MVC serves HEAD from the GET handler,
                        // so a member's HEAD fell through to the authenticated() rules
                        // below and ran the staff-only query. Without a method, the rule
                        // covers every verb on the path.
                        .requestMatchers("/api/transactions/book/**").hasAnyAuthority(ADMIN, LIBRARIAN)
                        .requestMatchers("/api/transactions/status/**").hasAnyAuthority(ADMIN, LIBRARIAN)
                        .requestMatchers(HttpMethod.GET, "/api/transactions/**").authenticated()

                        // Your own account: any signed-in account, members
                        // included - it is how a client learns the id its other
                        // calls need. One exact path, which names nobody else, and
                        // placed before the directory rule below, whose
                        // "/api/users/*" would otherwise claim it for staff only.
                        .requestMatchers(HttpMethod.GET, "/api/users/me").authenticated()
                        .requestMatchers(HttpMethod.HEAD, "/api/users/me").authenticated()

                        // The user directory: reading accounts is open to both
                        // kinds of staff. Librarians need it to find the member
                        // they are issuing a book to; UserService then limits
                        // them to members. HEAD is named as well as GET because
                        // Spring MVC serves HEAD from the GET handler - the same
                        // gap the transaction rules above close. "/api/users/*"
                        // is one segment only, so the status endpoint below it is
                        // not covered here.
                        .requestMatchers(HttpMethod.GET, "/api/users", "/api/users/*")
                                .hasAnyAuthority(ADMIN, LIBRARIAN)
                        .requestMatchers(HttpMethod.HEAD, "/api/users", "/api/users/*")
                                .hasAnyAuthority(ADMIN, LIBRARIAN)

                        // The staff password reset: administrators and librarians
                        // both, POST only. UserService then limits librarians to
                        // members and refuses an administrator's own account.
                        .requestMatchers(HttpMethod.POST, "/api/users/*/password-reset")
                                .hasAnyAuthority(ADMIN, LIBRARIAN)

                        // Everything else on the path is administrators only, and
                        // no HTTP method is named: these endpoints change who may
                        // use the system, so every other verb is covered rather
                        // than the ones thought of today.
                        .requestMatchers("/api/users/**").hasAuthority(ADMIN)

                        // Registering a library, together with its first
                        // administrator, is administrators only as well, for
                        // every verb on the path. The creator's own account is
                        // never attached to the library it creates.
                        .requestMatchers("/api/libraries/**").hasAuthority(ADMIN)

                        // The audit log is administrators only, for every verb
                        // on the path. No method is named, so GET and the HEAD
                        // Spring MVC serves from it are both covered, and so is
                        // anything added here later. AuditService is the second
                        // lock, and scopes the read to the caller's library.
                        .requestMatchers("/api/audit-events/**").hasAuthority(ADMIN)

                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * The hashing algorithm used for every user password in this system.
     *
     * <p>BCrypt is deliberate rather than incidental. A plain hash such as
     * SHA-256 is designed to be fast, which is exactly the wrong property for a
     * password: it lets an attacker who steals the table try billions of
     * guesses per second. BCrypt is designed to be slow and takes a cost factor
     * that can be raised as hardware improves, so the same stored hash stays
     * expensive to attack years later. It also salts each password
     * automatically, so two users who pick the same password still get
     * different hashes and neither can be spotted by comparing rows.</p>
     *
     * <p>The existing rows in the users table already hold BCrypt hashes, and
     * this encoder reads back what is already there: the version and cost
     * factor live inside the hash string itself, so verification uses the
     * parameters each hash was created with rather than the ones configured
     * here. The no argument constructor is the current default strength and is
     * used for anything hashed from now on.</p>
     *
     * <p>Declaring it now, before any authentication exists, means the login
     * and registration work in later steps has one encoder to inject rather
     * than each constructing its own and quietly disagreeing.</p>
     *
     * @return the BCrypt encoder shared by the whole application
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Exposes Spring Security's authentication engine as an injectable bean.
     *
     * <p>An {@link AuthenticationManager} is the thing that actually decides
     * whether a username and password are valid. Spring Security always builds
     * one internally, but it does not publish it, so nothing of ours can call
     * it. A login endpoint needs exactly that call, and this method is what
     * makes it reachable.</p>
     *
     * <p>It asks {@link AuthenticationConfiguration} for the manager rather
     * than assembling one. That matters: Spring Boot has already wired a
     * DaoAuthenticationProvider from the two beans this application declares,
     * the {@code CustomUserDetailsService} that reads accounts from MySQL and
     * the {@link #passwordEncoder()} that verifies BCrypt hashes. Asking for
     * the manager returns that same configured instance. Constructing a
     * ProviderManager and provider by hand would produce a second engine that
     * looked equivalent today and would quietly stop matching the first the
     * moment either bean changed.</p>
     *
     * <p>Taking {@code AuthenticationConfiguration} as a method parameter is
     * also what keeps this safe. Reaching for the builder held inside
     * HttpSecurity instead is the well known way to create a circular
     * dependency between the filter chain and the manager it depends on.</p>
     *
     * <p>Nothing calls this yet. Every request is still permitted and no login
     * endpoint exists; this step only publishes the bean that authentication
     * will consume later.</p>
     *
     * @param authenticationConfiguration Spring Security's own assembled
     *                                    authentication setup
     * @return the authentication manager Spring Security already built
     * @throws Exception if the manager cannot be obtained
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    /**
     * Answers an unauthenticated request to a protected endpoint.
     *
     * <p>This runs when authorization has already refused the request, so the
     * only job left is to say so in the shape the rest of this API uses. It
     * reuses the {@link ErrorResponse} record the global exception handler
     * returns, which means a client parses one error format whether the
     * refusal came from a controller or from the filter chain long before
     * one was reached.</p>
     *
     * <p>The {@code ObjectMapper} is injected rather than constructed. Spring
     * Boot's instance has the JavaTimeModule registered; a plain
     * {@code new ObjectMapper()} would throw on the {@code LocalDateTime}
     * timestamp, and even if it did not, it would format dates differently
     * from every other response in the application.</p>
     *
     * <p>The message is a fixed sentence and the {@code AuthenticationException}
     * argument is deliberately never read. Whether the token was missing,
     * expired, forged or valid for a deleted account is information the caller
     * has not earned. The request is recorded at DEBUG only: arriving without
     * credentials is ordinary, and logging it at a level that is on by default
     * would bury the failures that matter. A token that was presented and
     * rejected is logged by {@link JwtAuthenticationFilter} instead.</p>
     *
     * @param objectMapper the application's configured JSON writer
     * @return an entry point that writes a 401 in the standard error shape
     */
    @Bean
    public AuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authenticationException) -> {
            // DEBUG, not WARN. Reaching a protected endpoint without credentials
            // is ordinary - every client does it once before logging in, and
            // every scanner does it constantly - so logging it at a level that
            // is on by default would bury the failures that do matter. A token
            // that was presented and rejected is logged by the JWT filter.
            log.debug("Unauthenticated request to {} {}", request.getMethod(), request.getRequestURI());

            ErrorResponse errorResponse = new ErrorResponse(
                    HttpStatus.UNAUTHORIZED.value(),
                    "Authentication required",
                    LocalDateTime.now());

            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), errorResponse);
        };
    }

    /**
     * Stops the servlet container from registering the JWT filter a second time.
     *
     * <p>Spring Boot registers <i>every</i> bean of type {@code Filter} with the
     * servlet container automatically. That is a convenience for ordinary
     * filters and a nuisance here: {@link JwtAuthenticationFilter} is already
     * placed inside the security chain above, so leaving the automatic
     * registration in place would run the same filter twice per request, once
     * in each position. Note that this has nothing to do with
     * {@code @Component} versus {@code @Bean} - the container adapts any Filter
     * bean regardless of how it was declared, so dropping the annotation would
     * not have prevented it.</p>
     *
     * <p>Wrapping the filter in a registration whose {@code enabled} flag is
     * false is Spring Boot's documented way to opt out. The filter is taken as
     * a parameter rather than constructed, so this bean disables the
     * registration of the very same instance the security chain uses; there is
     * still exactly one filter object in the application.</p>
     *
     * @param jwtAuthenticationFilter the single filter instance, injected
     * @return a disabled registration, which suppresses the automatic one
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter jwtAuthenticationFilter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setEnabled(false);

        return registration;
    }
}
