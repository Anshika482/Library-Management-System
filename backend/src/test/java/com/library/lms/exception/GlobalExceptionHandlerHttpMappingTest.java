package com.library.lms.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Pins the HTTP status this API returns for each kind of failure.
 *
 * <p>Every other test in this project stops at the service layer, where a
 * failure is an exception. What a caller actually receives - a status line and
 * a JSON body - is decided afterwards, by {@link GlobalExceptionHandler}, and
 * until now nothing checked that step. The framework-level failures were the
 * evidence: an unsupported method, an impossible {@code Accept} header and an
 * unknown path each reached the catch-all and came back as <b>500</b>, telling
 * a client the server had broken when in every case the request was simply
 * wrong.</p>
 *
 * <p><b>Standalone MockMvc, not a Spring context.</b> The advice is applied to a
 * purpose-built probe controller through
 * {@code MockMvcBuilders.standaloneSetup(...).setControllerAdvice(...)}. That
 * gives real Spring MVC - real handler mapping, real content negotiation, real
 * message conversion - so 405 and 406 are raised by the framework rather than
 * simulated, while starting no context and touching no database. It also means
 * no security filter chain, which is the point: this class is about what MVC
 * decides, and {@code SecurityHttpIntegrationTest} already covers what the
 * filter chain decides.</p>
 *
 * <p>The regression block at the end is not padding. The three new handlers are
 * matched by type, and Spring resolves an exception to the <i>most specific</i>
 * handler; a mistake there would silently re-route an existing failure to a new
 * status. Those cases assert the mappings that were already in place still
 * hold.</p>
 */
class GlobalExceptionHandlerHttpMappingTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ---------- the framework failures this step is about ----------

    @Test
    void anUnsupportedHttpMethodReturns405() throws Exception {
        // /probe/read is mapped GET-only, so POSTing to it makes Spring raise
        // HttpRequestMethodNotSupportedException before any controller code runs.
        mockMvc.perform(post("/probe/read"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value("Method not allowed."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void anUnacceptableAcceptHeaderReturns406() throws Exception {
        // The endpoint can only produce JSON; the caller will only take XML.
        // Nothing is wrong with the server, so 500 was the wrong answer.
        mockMvc.perform(get("/probe/read").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.status").value(406))
                .andExpect(jsonPath("$.message").value("Requested representation is not available."));
    }

    @Test
    void aMissingResourceReturns404() throws Exception {
        mockMvc.perform(get("/probe/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Resource not found."));
    }

    @Test
    void agenuinelyUnexpectedFailureStillReturns500() throws Exception {
        // The catch-all must keep doing its job. Narrowing it by accident -
        // for example by making one of the new handlers too broad - would turn
        // real faults into misleading 4xx answers.
        mockMvc.perform(get("/probe/explode"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred. Please try again later."));
    }

    // ---------- nothing internal escapes ----------

    @Test
    void theNewMessagesDescribeNothingAboutTheServer() throws Exception {
        // The framework's own messages for these name the rejected method, the
        // producible media types and the resource path. A fixed sentence cannot
        // drift into disclosing them later.
        mockMvc.perform(post("/probe/read"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("POST"))));

        mockMvc.perform(get("/probe/missing-resource"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("probe"))));
    }

    @Test
    void noResponseBodyCarriesAnExceptionClassName() throws Exception {
        mockMvc.perform(get("/probe/explode"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Exception"))));
    }

    // ---------- regressions: mappings that already existed ----------

    @Test
    void aMissingBookStillReturns404WithItsOwnMessage() throws Exception {
        mockMvc.perform(get("/probe/book-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Book not found with id: 4242"));
    }

    @Test
    void aDuplicateIsbnStillReturns400() throws Exception {
        mockMvc.perform(get("/probe/duplicate-isbn"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void aLostOptimisticLockRaceStillReturns409() throws Exception {
        mockMvc.perform(get("/probe/stale-write"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("The resource was modified by another request. Please try again."));
    }

    @Test
    void aRefusedTransactionStillReturns403() throws Exception {
        mockMvc.perform(get("/probe/not-yours"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void anUnreadableBodyStillReturns400() throws Exception {
        // The Step 130 mapping. Included because a malformed body is the
        // closest neighbour of the failures added here and the easiest to
        // disturb.
        mockMvc.perform(post("/probe/write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request body could not be read. Check that it is valid JSON."));
    }

    @Test
    void anUnsupportedContentTypeStillReturns415() throws Exception {
        mockMvc.perform(post("/probe/write")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    /**
     * A controller that exists only to be failed against.
     *
     * <p>Deliberately not one of the real controllers. Those need services,
     * which need repositories and a database, and none of that has any bearing
     * on which status an exception maps to. Each endpoint here produces exactly
     * one failure.</p>
     */
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        /**
         * The shape read and written by the two endpoints below.
         *
         * <p>A record rather than a {@code String}, and that detail matters.
         * {@code StringHttpMessageConverter} advertises {@code *&#47;*}, so a
         * {@code String} endpoint happily writes XML, reads {@code text/plain}
         * and accepts malformed JSON as a perfectly good string - which
         * silently disables the 406, 415 and 400 cases. Only Jackson can
         * convert this type, so content negotiation and parsing actually have
         * something to refuse.</p>
         */
        record Payload(String title) {
        }

        /** Mapped GET-only, so a POST to it produces the 405 case. */
        @GetMapping("/read")
        Payload read() {
            return new Payload("ok");
        }

        /** Takes a JSON body, so a malformed or wrongly typed one lands here. */
        @PostMapping("/write")
        Payload write(@RequestBody Payload body) {
            return body;
        }

        @GetMapping("/missing-resource")
        String missingResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/probe/missing-resource");
        }

        @GetMapping("/explode")
        String explode() {
            throw new IllegalStateException("a fault the API never anticipated");
        }

        @GetMapping("/book-not-found")
        String bookNotFound() {
            throw new BookNotFoundException(4242L);
        }

        @GetMapping("/duplicate-isbn")
        String duplicateIsbn() {
            throw new DuplicateIsbnException("978-0000000000");
        }

        @GetMapping("/stale-write")
        String staleWrite() {
            throw new ObjectOptimisticLockingFailureException("Book", 1L);
        }

        @GetMapping("/not-yours")
        String notYours() {
            throw new TransactionAccessDeniedException();
        }
    }
}
