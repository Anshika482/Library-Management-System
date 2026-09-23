package com.library.lms.service;

import java.util.List;
import java.util.Locale;

/**
 * The assistant until there is a real one: a fixed script, matched on keywords.
 *
 * <p><b>Deterministic on purpose.</b> The same question from the same caller
 * always produces the same answer, with no model, no network call and no
 * randomness, so the endpoint around it - authentication, validation, the
 * library context, the error handling - can be built and tested before any
 * provider exists, and tested the same way afterwards.</p>
 *
 * <p><b>It answers only what it knows.</b> Anything unmatched gets a plain "I
 * cannot answer that yet" rather than a guess. An assistant that invents
 * library hours or a fine amount is worse than one that declines, and this one
 * has nothing to invent from: it reads no data at all.</p>
 *
 * <p><b>The caller's own library, and nothing else.</b> The only external fact
 * any answer uses is the library name in the {@link ChatContext} handed to it -
 * the caller's own. There is no query here that could reach another library,
 * and no way to ask for one.</p>
 *
 * <p><b>Selected with {@code chat.provider=scripted}</b>, which is the default:
 * development and CI run on this, so neither needs a provider key.</p>
 *
 * <p><b>The question is never repeated back.</b> It is matched against keywords
 * and then dropped: it is whatever the caller typed, and an answer that echoes
 * it is a way for one person's words to end up somewhere they did not expect.
 * Nothing here logs it either.</p>
 */
public class ScriptedAiChatService implements AiChatService {

    static final String NAME = "scripted";

    /** What a caller is told when nothing matches. */
    static final String UNKNOWN = "I cannot answer that yet. I can help with borrowing, returns, fines, "
            + "passwords and what I am able to do - try asking about one of those.";

    private static final List<Answer> SCRIPT = List.of(
            new Answer(List.of("hello", "hi ", "hey", "good morning", "good afternoon", "good evening"),
                    "Hello. I am the %s assistant. Ask me about borrowing, returns, fines or your account."),
            new Answer(List.of("help", "what can you do", "what do you do", "options"),
                    "I can explain how borrowing and returns work at %s, how overdue fines are worked out, "
                            + "how to pay one, and how to change or reset your password."),
            new Answer(List.of("borrow", "issue", "take out", "loan a book"),
                    "Staff at %s issue books at the desk. A loan is recorded against your account with a due "
                            + "date, and you can see it in your loan history."),
            new Answer(List.of("return", "give back", "bring back"),
                    "Bring the book back to %s and staff will record the return. The copy goes back on the "
                            + "shelf straight away, and any fine is worked out on the day it comes back."),
            new Answer(List.of("fine", "overdue", "late"),
                    "A loan is overdue from the day after its due date, and a fine is charged for each day "
                            + "after that. Returning the book fixes the amount owed."),
            new Answer(List.of("pay", "payment", "card"),
                    "A fine can be paid at the desk, or by card once the book is back. Payment is recorded "
                            + "against the loan, and it can only be paid once."),
            new Answer(List.of("password", "sign in", "log in", "login", "locked out"),
                    "You can change your own password while signed in. If you have forgotten it, ask for a "
                            + "reset link by email, or ask staff at %s to set a new one."),
            new Answer(List.of("hours", "open", "closing", "address", "phone"),
                    "I do not have opening hours or contact details for %s. Staff at the desk can tell you."));

    @Override
    public String name() {
        return NAME;
    }

    /**
     * The first scripted answer whose keywords the question contains, or a
     * refusal.
     *
     * <p>Matched in the order above, so a question mentioning two subjects gets
     * the earlier one rather than an arbitrary pick - which is what keeps the
     * same question producing the same answer.</p>
     */
    @Override
    public String reply(String message, ChatContext context) {
        if (message == null || context == null) {
            return UNKNOWN;
        }

        String library = context.libraryName() == null ? "your library" : context.libraryName();

        // A catalogue question was recognised and looked up before this was
        // called. The books are read out exactly as they were found: this
        // assistant states what the library holds and never adds to it.
        if (context.hasCatalogue()) {
            return catalogueAnswer(context.catalogue(), library);
        }

        // Padded so a keyword like "hi" matches the word and not the middle of
        // "this", and lowercased so matching does not depend on how it was typed.
        String asked = " " + message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim() + " ";

        for (Answer answer : SCRIPT) {
            if (answer.matches(asked)) {
                return answer.text().formatted(library);
            }
        }

        return UNKNOWN;
    }

    /**
     * What the library holds, in a sentence.
     *
     * <p>Nothing is added to what the lookup found. An empty result says so
     * plainly rather than offering something else, because a library not
     * holding a book is a fact worth stating exactly.</p>
     */
    private static String catalogueAnswer(CatalogueLookup lookup, String library) {
        if (lookup.empty()) {
            return "I could not find anything matching \"" + lookup.term() + "\" in " + library
                    + ". Staff at the desk can check for you, or order it in.";
        }

        StringBuilder answer = new StringBuilder(library).append(" has ")
                .append(lookup.books().size() == 1 ? "one match" : lookup.books().size() + " matches")
                .append(" for \"").append(lookup.term()).append("\":");

        for (BookFact book : lookup.books()) {
            answer.append("\n- ").append(book.describe());
        }

        // What those books have to read online. A member was only given the
        // enabled ones, so this cannot mention a resource they could not open.
        if (lookup.hasResources()) {
            answer.append("\n\nTo read online:");
            for (ResourceFact resource : lookup.resources()) {
                answer.append("\n- ").append(resource.describe());
            }
        }

        return answer.toString();
    }

    /** One scripted answer and the keywords that reach it. */
    private record Answer(List<String> keywords, String text) {

        boolean matches(String paddedQuestion) {
            return keywords.stream().anyMatch(keyword -> paddedQuestion.contains(
                    keyword.endsWith(" ") ? keyword : " " + keyword));
        }
    }
}
