package com.library.lms.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.ChatResponse;
import com.library.lms.entity.Library;
import com.library.lms.entity.User;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.UserRepository;

/**
 * Answering a caller's question, in their own library's context.
 *
 * <p><b>This class is where the context comes from, and it comes from the
 * account.</b> The caller's library, id and role are read from the database
 * using the authenticated name, never from anything they sent, and handed to
 * the {@link AiChatService} as a {@link ChatContext}. An assistant therefore
 * cannot be pointed at another library, whatever a question says, because it is
 * never told another library exists.</p>
 *
 * <p><b>Every role may ask.</b> Members, librarians and administrators all
 * reach the assistant; what differs is what a future implementation may tell
 * them, which is why the role travels in the context. Nothing here decides that
 * on the assistant's behalf.</p>
 *
 * <p><b>The question is not written down.</b> It is passed to the assistant and
 * dropped: it is whatever the caller typed, which may be anything at all, and a
 * log line is the wrong place for it. The log records that an account asked
 * something, by id, and how long the answer was.</p>
 *
 * <p>Read-only: asking a question changes nothing, and nothing about a
 * conversation is stored yet.</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final AiChatService assistant;

    private final UserRepository userRepository;

    public ChatService(AiChatService assistant, UserRepository userRepository) {
        this.assistant = assistant;
        this.userRepository = userRepository;
    }

    /**
     * Answers one question for one signed-in caller.
     *
     * @param message               the question, validated at the boundary
     * @param authenticatedUsername the caller, from the security context
     * @return the answer, with the assistant that produced it
     * @throws UserNotFoundException if the authenticated name matches no account
     */
    @Transactional(readOnly = true)
    public ChatResponse reply(String message, String authenticatedUsername) {
        User caller = userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));

        Library library = caller.getLibrary();
        ChatContext context = new ChatContext(
                library == null ? null : library.getId(),
                library == null ? null : library.getName(),
                caller.getId(),
                caller.getRole());

        String reply = assistant.reply(message, context);

        // By id, and by length. Never the question, and never the answer: one
        // is the caller's own words and the other may quote them back.
        log.info("Chat answered for user id={} in library id={} assistant='{}' replyLength={}",
                caller.getId(), context.libraryId(), assistant.name(), reply == null ? 0 : reply.length());

        return new ChatResponse(reply, assistant.name(), LocalDateTime.now());
    }
}
