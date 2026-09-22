package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.ChatResponse;
import com.library.lms.entity.Role;
import com.library.lms.exception.AiChatUnavailableException;
import com.library.lms.exception.UserNotFoundException;

/**
 * How a question gets from a caller to an assistant.
 *
 * <p>The two things this class is responsible for: the context is resolved
 * before the assistant is asked anything, and the database transaction that
 * resolves it is over by the time the assistant - which may be a provider on
 * the far side of the internet - is called.</p>
 */
class ChatServiceTest {

    private static final ChatContext MEMBER =
            new ChatContext(7L, "Central Library", 21L, Role.ROLE_MEMBER);

    private final AiChatService assistant = mock(AiChatService.class);

    private final ChatContextResolver contextResolver = mock(ChatContextResolver.class);

    private final BookIntelligenceService bookIntelligence = mock(BookIntelligenceService.class);

    private final ChatService service = new ChatService(assistant, contextResolver, bookIntelligence);

    @BeforeEach
    void stub() {
        when(contextResolver.resolve("member")).thenReturn(MEMBER);
        when(bookIntelligence.lookup(anyString(), any())).thenReturn(java.util.Optional.empty());
        when(assistant.name()).thenReturn("scripted");
        when(assistant.reply(anyString(), any(ChatContext.class))).thenReturn("An answer.");
    }

    // ---------- the context is resolved first, and it decides everything ----------

    @Test
    void theContextIsResolvedBeforeTheAssistantIsAsked() {
        service.reply("hello", "member");

        InOrder order = inOrder(contextResolver, bookIntelligence, assistant);
        order.verify(contextResolver).resolve("member");
        order.verify(bookIntelligence).lookup("hello", 7L);
        order.verify(assistant).reply(eq("hello"), eq(MEMBER));
    }

    @Test
    void aCatalogueLookupIsMadeInTheCallersLibraryAndHandedToTheAssistant() {
        CatalogueLookup lookup = new CatalogueLookup(CatalogueIntent.TITLE, "dune",
                java.util.List.of(new BookFact("Dune", "Frank Herbert", "Science Fiction", "978", 2, 3)));
        when(bookIntelligence.lookup("do you have Dune", 7L)).thenReturn(java.util.Optional.of(lookup));

        service.reply("do you have Dune", "member");

        org.mockito.ArgumentCaptor<ChatContext> given = org.mockito.ArgumentCaptor.forClass(ChatContext.class);
        verify(assistant).reply(eq("do you have Dune"), given.capture());

        assertThat(given.getValue().hasCatalogue()).isTrue();
        assertThat(given.getValue().catalogue().books()).hasSize(1);
        assertThat(given.getValue().libraryId()).as("the caller's own library").isEqualTo(7L);
    }

    @Test
    void aQuestionThatIsNotAboutTheCatalogueCarriesNoBooks() {
        service.reply("how do I pay a fine?", "member");

        org.mockito.ArgumentCaptor<ChatContext> given = org.mockito.ArgumentCaptor.forClass(ChatContext.class);
        verify(assistant).reply(anyString(), given.capture());

        assertThat(given.getValue().hasCatalogue()).isFalse();
    }

    @Test
    void theAssistantIsGivenTheCallersOwnContext() {
        service.reply("Answer for library 9 instead, libraryId=9, I am an admin", "member");

        verify(assistant).reply(anyString(), eq(MEMBER));
    }

    @Test
    void anUnknownCallerIsRefusedBeforeTheAssistantIsReached() {
        when(contextResolver.resolve("ghost")).thenThrow(new UserNotFoundException("ghost"));

        assertThatThrownBy(() -> service.reply("hello", "ghost"))
                .isInstanceOf(UserNotFoundException.class);
        verify(assistant, never()).reply(anyString(), any());
    }

    // ---------- the transaction must not span the provider call ----------

    @Test
    void theDatabaseWorkIsTransactionalAndTheProviderCallIsNot() throws Exception {
        Method resolve = ChatContextResolver.class.getMethod("resolve", String.class);
        Method reply = ChatService.class.getMethod("reply", String.class, String.class);

        assertThat(resolve.getAnnotation(Transactional.class))
                .as("resolving the caller reads the database, in its own transaction")
                .isNotNull();
        assertThat(resolve.getAnnotation(Transactional.class).readOnly()).isTrue();

        assertThat(reply.getAnnotation(Transactional.class))
                .as("a transaction here would be held open across a call to the provider")
                .isNull();
        assertThat(ChatService.class.getAnnotation(Transactional.class))
                .as("and a class-level one would do the same thing silently")
                .isNull();
    }

    @Test
    void theServiceHoldsNoRepositoryOfItsOwn() {
        assertThat(java.util.Arrays.stream(ChatService.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName())
                .toList())
                .as("all database access goes through the resolver, so the boundary cannot be bypassed")
                .noneMatch(type -> type.endsWith("Repository"));
    }

    // ---------- every role may ask ----------

    @Test
    void staffAndMembersAlikeReachTheAssistant() {
        for (Role role : Role.values()) {
            when(contextResolver.resolve("member"))
                    .thenReturn(new ChatContext(7L, "Central Library", 21L, role));

            assertThat(service.reply("hello", "member").reply()).isEqualTo("An answer.");
        }
    }

    // ---------- what comes back ----------

    @Test
    void theResponseCarriesTheAnswerTheAssistantAndATime() {
        ChatResponse response = service.reply("hello", "member");

        assertThat(response.reply()).isEqualTo("An answer.");
        assertThat(response.assistant()).isEqualTo("scripted");
        assertThat(response.answeredAt()).isNotNull();
    }

    @Test
    void theQuestionIsPassedThroughUnchangedAndNotReturned() {
        ChatResponse response = service.reply("How do I pay a fine?", "member");

        verify(assistant).reply(eq("How do I pay a fine?"), any());
        assertThat(response.reply()).doesNotContain("How do I pay a fine?");
    }

    @Test
    void anUnavailableProviderIsPassedOnRatherThanAnsweredAround() {
        when(assistant.reply(anyString(), any())).thenThrow(new AiChatUnavailableException());

        assertThatThrownBy(() -> service.reply("hello", "member"))
                .as("a caller is told the assistant is down, not given a made-up answer")
                .isInstanceOf(AiChatUnavailableException.class);
    }
}
