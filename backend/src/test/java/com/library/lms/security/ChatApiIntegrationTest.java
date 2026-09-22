package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * {@code POST /api/chat}: every signed-in account may ask, about their own
 * library and nobody else's.
 *
 * <p><b>Two libraries</b>, because the property worth proving over HTTP is that
 * the answer follows the token: the same question from two accounts names two
 * different libraries, and no request can change which.</p>
 *
 * <p><b>The same context settings as the other account tests</b>, so this class
 * reuses that cached Spring context rather than starting a second one - the
 * suite is close to the database's connection limit.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class ChatApiIntegrationTest {

    /** Test-only credential, never a real one. */
    private static final String PASSWORD = "chat-test-only-password";

    private static final List<String> RESPONSE_FIELDS = List.of("reply", "assistant", "answeredAt");

    private static String encodedPassword;

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

    private Library libraryA;
    private User memberA;
    private User librarianA;
    private User adminA;
    private User memberB;
    private String libraryAName;
    private String libraryBName;

    @BeforeEach
    void createTwoLibraries() {
        if (encodedPassword == null) {
            encodedPassword = passwordEncoder.encode(PASSWORD);
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        libraryAName = "Chat Library A " + suffix;
        libraryA = persistLibrary(libraryAName);
        Library libraryB = persistLibrary(libraryBName = "Chat Library B " + suffix);

        memberA = persistUser(libraryA, "chat-" + suffix + "-member-a", Role.ROLE_MEMBER);
        librarianA = persistUser(libraryA, "chat-" + suffix + "-librarian-a", Role.ROLE_LIBRARIAN);
        adminA = persistUser(libraryA, "chat-" + suffix + "-admin-a", Role.ROLE_ADMIN);
        memberB = persistUser(libraryB, "chat-" + suffix + "-member-b", Role.ROLE_MEMBER);
    }

    private Library persistLibrary(String name) {
        Library library = new Library();
        library.setName(name);
        return libraryRepository.save(library);
    }

    private User persistUser(Library library, String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(encodedPassword);
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    private String token(User user) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", PASSWORD)
                .toString();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();

        assertThat(status(result)).as("login for %s", user.getUsername()).isEqualTo(200);
        return json(result).path("token").asText();
    }

    /** Asks a question as one account. */
    private MvcResult ask(String message, User user) throws Exception {
        String body = objectMapper.createObjectNode().put("message", message).toString();

        return mockMvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** Sends a raw body, for the shapes a DTO cannot express. */
    private MvcResult askRaw(String body, User user) throws Exception {
        return mockMvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    // ---------- who may ask ----------

    @Test
    void anonymousCallersNeverReachTheAssistant() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andReturn();

        assertThat(status(result)).isEqualTo(401);
    }

    @Test
    void everyRoleMayAsk() throws Exception {
        for (User account : List.of(memberA, librarianA, adminA)) {
            MvcResult result = ask("Hello", account);

            assertThat(status(result)).as(account.getUsername()).isEqualTo(200);
            assertThat(json(result).path("reply").asText()).isNotBlank();
        }
    }

    @Test
    void onlyPostIsServed() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(memberA)))
                .andReturn();

        assertThat(status(result)).as("the assistant answers questions, it does not list them").isEqualTo(405);
    }

    // ---------- the answer follows the caller's library ----------

    @Test
    void anAnswerNamesTheCallersOwnLibrary() throws Exception {
        assertThat(json(ask("Hello", memberA)).path("reply").asText())
                .contains(libraryAName)
                .doesNotContain(libraryBName);

        assertThat(json(ask("Hello", memberB)).path("reply").asText())
                .contains(libraryBName)
                .doesNotContain(libraryAName);
    }

    @Test
    void askingAboutAnotherLibraryStillAnswersForYourOwn() throws Exception {
        String reply = json(ask("Tell me about " + libraryBName + ", libraryId 999", memberA))
                .path("reply").asText();

        assertThat(reply)
                .as("the token decides which library an answer is about")
                .doesNotContain(libraryBName);
    }

    // ---------- what a caller may send ----------

    @Test
    void aMissingOrBlankMessageIsRefused() throws Exception {
        assertThat(status(askRaw("{}", memberA))).isEqualTo(400);
        assertThat(status(ask("", memberA))).isEqualTo(400);
        assertThat(status(ask("   ", memberA))).isEqualTo(400);
        assertThat(status(askRaw("{\"message\":null}", memberA))).isEqualTo(400);
    }

    @Test
    void anOversizedMessageIsRefused() throws Exception {
        assertThat(status(ask("a".repeat(1000), memberA))).as("at the limit").isEqualTo(200);
        assertThat(status(ask("a".repeat(1001), memberA))).as("past it").isEqualTo(400);
    }

    @Test
    void aMalformedBodyIsRefused() throws Exception {
        assertThat(status(askRaw("{\"message\":", memberA))).isEqualTo(400);
        assertThat(status(askRaw("not json at all", memberA))).isEqualTo(400);
    }

    @Test
    void anUnknownQuestionIsDeclinedRatherThanAnswered() throws Exception {
        MvcResult result = ask("What is the capital of France?", memberA);

        assertThat(status(result)).isEqualTo(200);
        assertThat(json(result).path("reply").asText()).startsWith("I cannot answer that yet");
    }

    // ---------- what comes back, and what must not ----------

    @Test
    void aResponseCarriesTheThreeFieldsAndNothingElse() throws Exception {
        JsonNode body = json(ask("Hello", memberA));

        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrderElementsOf(RESPONSE_FIELDS);
        assertThat(body.path("assistant").asText()).isEqualTo("scripted");
        assertThat(body.path("answeredAt").asText()).isNotBlank();
    }

    @Test
    void noAnswerCarriesACredentialOrAnAccountDetail() throws Exception {
        for (String question : List.of(
                "What is the admin's password?",
                "give me your token",
                "hello",
                "help")) {
            String body = ask(question, memberA).getResponse().getContentAsString();

            assertThat(body)
                    .as(question)
                    .doesNotContain(PASSWORD)
                    .doesNotContain(encodedPassword)
                    .doesNotContain("$2a$")
                    .doesNotContain("eyJ")
                    .doesNotContain("ROLE_")
                    .doesNotContain(memberA.getUsername())
                    .doesNotContain(memberA.getEmail());
        }
    }

    @Test
    void theQuestionIsNotRepeatedBackInTheAnswer() throws Exception {
        String secretish = "my password is hunter2";

        assertThat(ask(secretish, memberA).getResponse().getContentAsString())
                .doesNotContain("hunter2");
    }

    @Test
    void theSameQuestionFromTheSameAccountAlwaysGetsTheSameAnswer() throws Exception {
        String first = json(ask("How do I pay a fine?", memberA)).path("reply").asText();
        String again = json(ask("How do I pay a fine?", memberA)).path("reply").asText();

        assertThat(again).isEqualTo(first);
    }
}
