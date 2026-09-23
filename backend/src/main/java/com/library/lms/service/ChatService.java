package com.library.lms.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.library.lms.dto.ChatResponse;
import com.library.lms.exception.UserNotFoundException;

/**
 * Answering a caller's question, in their own library's context.
 *
 * <p><b>A catalogue question is answered from the catalogue, not from the
 * model.</b> {@link BookIntelligenceService} recognises questions about titles,
 * authors, categories, availability and book details, and looks them up in the
 * caller's own library. The assistant is then asked to phrase what was found -
 * it is given the books and no way to look up any others.</p>
 *
 * <p><b>The context comes from the account, and it comes first.</b>
 * {@link ChatContextResolver} reads the caller's library, id and role from the
 * database and hands back a {@link ChatContext}; only then is the assistant
 * asked anything. An assistant therefore cannot be pointed at another library,
 * whatever a question says, because it is never told another library
 * exists.</p>
 *
 * <p><b>Deliberately not transactional.</b> The database work happens inside
 * the resolver's own transaction, which has committed and released its
 * connection before the assistant is called. That matters once the assistant is
 * a provider on the far side of the internet: a transaction held across that
 * call would pin a database connection for the length of a network round trip,
 * and a provider having a slow day would take the connection pool - and so the
 * rest of the application - with it. Nothing here writes, so there is nothing
 * for a transaction to protect.</p>
 *
 * <p><b>Every role may ask.</b> Members, librarians and administrators all
 * reach the assistant; what differs is what an implementation may tell them,
 * which is why the role travels in the context.</p>
 *
 * <p><b>The question is not written down.</b> It is passed to the assistant and
 * dropped: it is whatever the caller typed, which may be anything at all, and a
 * log line is the wrong place for it. The log records that an account asked
 * something, by id.</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AiChatService assistant;

    private final ChatContextResolver contextResolver;

    private final BookIntelligenceService bookIntelligence;

    public ChatService(AiChatService assistant, ChatContextResolver contextResolver,
            BookIntelligenceService bookIntelligence) {
        this.assistant = assistant;
        this.contextResolver = contextResolver;
        this.bookIntelligence = bookIntelligence;
    }

    /**
     * Answers one question for one signed-in caller.
     *
     * @param message               the question, validated at the boundary
     * @param authenticatedUsername the caller, from the security context
     * @return the answer, with the assistant that produced it
     * @throws UserNotFoundException if the authenticated name matches no account
     * @throws com.library.lms.exception.AiChatUnavailableException if the
     *         assistant's provider cannot answer
     */
    public ChatResponse reply(String message, String authenticatedUsername) {
        // In a transaction, which ends when this returns.
        ChatContext context = contextResolver.resolve(authenticatedUsername);

        // And a second one, if the question was about the catalogue. The
        // assistant is handed what this found; it never queries anything
        // itself, and it cannot ask for another library's shelves.
        // Whether disabled resources are visible follows the caller's role, as
        // it does at the resource API - taken from the account, never from the
        // question.
        context = bookIntelligence.lookup(message, context.libraryId(), context.isStaff())
                .map(context::withCatalogue)
                .orElse(context);

        // Out of every transaction. This may go over the network and take seconds.
        String reply = assistant.reply(message, context);

        // By id, and by length. Never the question, and never the answer: one
        // is the caller's own words and the other may quote them back.
        log.info("Chat answered for user id={} in library id={} assistant='{}' catalogue={} replyLength={}",
                context.userId(), context.libraryId(), assistant.name(),
                context.hasCatalogue() ? context.catalogue().books().size() + " books" : "none",
                reply == null ? 0 : reply.length());

        return new ChatResponse(reply, assistant.name(), LocalDateTime.now());
    }
}
