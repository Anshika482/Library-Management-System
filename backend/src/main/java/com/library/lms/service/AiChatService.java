package com.library.lms.service;

/**
 * The assistant that answers a question, as the rest of this application sees
 * it.
 *
 * <p><b>One method, and a context it does not choose.</b> An implementation is
 * handed the question and the caller's own {@link ChatContext}; it cannot ask
 * for another library's, because it is never given one. Whatever answers
 * questions later - a local model, a hosted provider, a retrieval layer over
 * the catalogue - reaches the rest of the application through this interface
 * and no other way, so swapping it is one new implementation.</p>
 *
 * <p><b>What an implementation must never do:</b> put a password, a hash, a
 * token, an authority or any other security detail into an answer, log the
 * question's text, or read data outside {@code context.libraryId()}. The
 * question is a caller's own words and may hold anything they typed.</p>
 */
public interface AiChatService {

    /** The assistant's name, so an answer can say what produced it. */
    String name();

    /**
     * Answers one question.
     *
     * @param message the caller's question, already validated as present and
     *                within length
     * @param context who is asking, and the only library an answer may draw on
     * @return the answer, which carries nothing the caller may not see
     */
    String reply(String message, ChatContext context);
}
