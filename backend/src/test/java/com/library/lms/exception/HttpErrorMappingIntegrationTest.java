package com.library.lms.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Checks the two failures that only the real application can produce.
 *
 * <p>{@code GlobalExceptionHandlerHttpMappingTest} covers the mapping from each
 * exception to its status, and does so quickly by throwing the exception from a
 * probe controller. That leaves one thing unproven: whether this application,
 * assembled as it actually is, <i>raises</i> those exceptions in the first
 * place. {@code NoResourceFoundException} in particular is not thrown by any
 * code in this project - it comes from the static resource handler that a
 * request reaches only after every mapping has failed to match, wiring that
 * exists only in a full Spring Boot context.</p>
 *
 * <p><b>Why the filters are off.</b> {@code addFilters = false} removes the
 * security chain for this class only. It has to: {@code anyRequest()
 * .authenticated()} means an unknown path answers 401 long before Spring MVC is
 * consulted, so with the chain in place there is nothing here to observe. No
 * production configuration is touched, and what the chain does with these
 * requests is not this class's question - {@code SecurityHttpIntegrationTest}
 * asks that one, with the filters on.</p>
 *
 * <p>Both requests below fail before reaching any controller, which is what
 * makes them safe to send unauthenticated: no service runs, no repository is
 * called, and the database is never read.</p>
 *
 * <p><b>Isolation:</b> its own throwaway schema, as in the other integration
 * tests here. {@code ddl-auto} stays on {@code update} - it can add tables but
 * can never drop one - so a mistyped URL could not damage anything even if the
 * tests did touch data.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step134_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc(addFilters = false)
class HttpErrorMappingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anUnknownPathReturns404RatherThanAServerError() throws Exception {
        // No mapping matches, so the request falls through to the resource
        // handler, which raises NoResourceFoundException. Before this step that
        // reached the catch-all and every mistyped URL answered 500.
        mockMvc.perform(get("/api/there-is-no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Resource not found."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void aPathProbeIsNotToldThatSomethingBroke() throws Exception {
        // The shape of a scan. The answer should be flatly uninteresting, and
        // must not name the path back to the caller.
        mockMvc.perform(get("/.env"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found."));
    }

    @Test
    void aRealPathWithTheWrongMethodReturns405() throws Exception {
        // /api/auth/login exists, but only for POST. The path matches a
        // mapping and the method does not, so Spring raises
        // HttpRequestMethodNotSupportedException before the controller runs.
        mockMvc.perform(delete("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value("Method not allowed."));
    }
}
