package com.library.lms.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.entity.Book;
import com.library.lms.entity.Category;
import com.library.lms.entity.DigitalResource;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.BookSpecifications;
import com.library.lms.repository.DigitalResourceRepository;

/**
 * Works out whether a question is about the catalogue, and if so answers it
 * from the caller's own library.
 *
 * <p><b>The assistant never reaches the database; this class does.</b> It runs
 * the same library-scoped queries the catalogue endpoints run, turns what comes
 * back into {@link BookFact}s, and hands over that list. An assistant is given
 * the list and no repository, so there is no query it can widen, no library it
 * can reach, and no field it can read that a {@code BookFact} does not carry -
 * which is what keeps a model's suggestibility away from the data.</p>
 *
 * <p><b>The library is a parameter, not a guess.</b> It comes from the caller's
 * authenticated account by way of {@link ChatContext}, and every query below
 * names it, so a question mentioning another library still searches the
 * caller's own.</p>
 *
 * <p><b>Nothing about a person is read.</b> The queries touch books and their
 * categories. Loans, members, fines and accounts are not joined, not selected
 * and not reachable from here, so no answer can carry them however a question
 * is phrased.</p>
 *
 * <p><b>Resources follow the books, and follow the same rules.</b> Whatever a
 * matching book has to read online is looked up through the digital resource
 * repository's own library-scoped finders - the enabled-only one for a member,
 * the full one for staff, which is exactly what the resource API decides for
 * each. A member therefore learns nothing here that they could not already see
 * at {@code GET /api/digital-resources}.</p>
 *
 * <p><b>Read-only and bounded.</b> At most {@value #MAX_BOOKS} books and
 * {@value #MAX_RESOURCES} resources, so a question cannot pull a whole
 * catalogue into an answer - or into a provider's request.</p>
 */
@Component
public class BookIntelligenceService {

    /** The most books one answer may be built from. */
    static final int MAX_BOOKS = 5;

    /** The most digital resources one answer may be built from, across every matching book. */
    static final int MAX_RESOURCES = 5;

    /** Phrases that mean "what has this library got", with the words that introduce the thing sought. */
    private static final List<Trigger> TRIGGERS = List.of(
            new Trigger(CatalogueIntent.CATEGORY, List.of("category", "genre", "section")),
            new Trigger(CatalogueIntent.AUTHOR, List.of("written by", "books by", "author", " by ")),
            new Trigger(CatalogueIntent.AVAILABILITY,
                    List.of("available", "in stock", "can i borrow", "copies of", "on the shelf")),
            new Trigger(CatalogueIntent.DETAILS, List.of("tell me about", "details of", "details about", "about")),
            new Trigger(CatalogueIntent.TITLE,
                    List.of("do you have", "looking for", "search for", "find", "book called",
                            "books called", "titled")));

    private final BookRepository bookRepository;

    private final DigitalResourceRepository resourceRepository;

    public BookIntelligenceService(BookRepository bookRepository, DigitalResourceRepository resourceRepository) {
        this.bookRepository = bookRepository;
        this.resourceRepository = resourceRepository;
    }

    /**
     * What the caller's library holds, if the question was about the catalogue
     * at all.
     *
     * @param message   the caller's question
     * @param libraryId the caller's own library, from their account
     * @param staff     whether the caller may see resources their library has
     *                  switched off - the same rule the resource API applies,
     *                  and one that comes from their account rather than from
     *                  anything in the question
     * @return the lookup, or empty when the question was not a catalogue one -
     *         in which case no query is run
     */
    @Transactional(readOnly = true)
    public Optional<CatalogueLookup> lookup(String message, Long libraryId, boolean staff) {
        if (message == null || message.isBlank() || libraryId == null) {
            return Optional.empty();
        }

        String asked = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();

        for (Trigger trigger : TRIGGERS) {
            Optional<String> term = trigger.termIn(asked);
            if (term.isPresent()) {
                List<Book> books = books(trigger.intent(), term.get(), libraryId);

                return Optional.of(new CatalogueLookup(trigger.intent(), term.get(),
                        books.stream().map(BookIntelligenceService::toFact).toList(),
                        resourcesOf(books, libraryId, staff)));
            }
        }

        return Optional.empty();
    }

    /** The caller's own library's books matching the term. */
    private List<Book> books(CatalogueIntent intent, String term, Long libraryId) {
        return intent == CatalogueIntent.CATEGORY
                ? bookRepository.findByLibraryIdAndCategoryName(libraryId, term, PageRequest.of(0, MAX_BOOKS))
                        .getContent()
                : bookRepository.findAll(
                        BookSpecifications.belongsToLibrary(libraryId).and(BookSpecifications.matchesKeyword(term)),
                        PageRequest.of(0, MAX_BOOKS)).getContent();
    }

    /**
     * What those books have to read online, within the same library.
     *
     * <p>Two finders, chosen by who is asking: a member gets the enabled-only
     * one, so a resource their library has switched off is as absent from an
     * answer as it is from their own list at
     * {@code GET /api/digital-resources}; staff get the view they have there
     * too. Both name the library as well as the book, so neither can reach
     * across - and the running total is what bounds the whole answer, not each
     * book separately.</p>
     */
    private List<ResourceFact> resourcesOf(List<Book> books, Long libraryId, boolean staff) {
        List<ResourceFact> facts = new ArrayList<>();

        for (Book book : books) {
            if (facts.size() >= MAX_RESOURCES) {
                break;
            }

            Pageable page = PageRequest.of(0, MAX_RESOURCES - facts.size());
            List<DigitalResource> resources = staff
                    ? resourceRepository.findByLibraryIdAndBookId(libraryId, book.getId(), page).getContent()
                    : resourceRepository.findByLibraryIdAndBookIdAndEnabledTrue(libraryId, book.getId(), page)
                            .getContent();

            for (DigitalResource resource : resources) {
                // Checked here as well as in the page size: the bound on what
                // reaches a prompt is this application's to keep, not something
                // to delegate to a query honouring the size it was asked for.
                if (facts.size() >= MAX_RESOURCES) {
                    break;
                }
                facts.add(toFact(resource, book));
            }
        }

        return List.copyOf(facts);
    }

    /** A resource, reduced to what an assistant may be told. No link, no id, no state. */
    private static ResourceFact toFact(DigitalResource resource, Book book) {
        return new ResourceFact(
                book.getTitle(),
                resource.getTitle(),
                resource.getDescription(),
                resource.getResourceType());
    }

    /** A book, reduced to what an assistant may be told. Nothing here comes from a person's record. */
    private static BookFact toFact(Book book) {
        Category category = book.getCategory();

        return new BookFact(
                book.getTitle(),
                book.getAuthor(),
                category == null ? null : category.getName(),
                book.getIsbn(),
                book.getAvailableCopies() == null ? 0 : book.getAvailableCopies(),
                book.getTotalCopies() == null ? 0 : book.getTotalCopies());
    }

    /**
     * One kind of catalogue question, and the phrases that introduce what is
     * being asked about.
     */
    private record Trigger(CatalogueIntent intent, List<String> phrases) {

        /**
         * What is being asked about, on whichever side of the phrase it sits.
         *
         * <p>English puts it on both. "copies of Dune" names the book after the
         * phrase; "is Dune available" names it before - and that second shape
         * is how most people ask whether they can borrow something, so taking
         * only what follows would miss the commonest availability question
         * there is.</p>
         *
         * <p>A phrase with nothing usable on either side is not a search: "is
         * it available?" names no book, so it produces no term and no
         * query.</p>
         */
        Optional<String> termIn(String asked) {
            for (String phrase : phrases) {
                int at = asked.indexOf(phrase);
                if (at < 0) {
                    continue;
                }

                String after = clean(asked.substring(at + phrase.length()));
                if (!after.isEmpty()) {
                    return Optional.of(after);
                }

                String before = clean(stripLeadingWords(asked.substring(0, at)));
                if (!before.isEmpty()) {
                    return Optional.of(before);
                }
            }

            return Optional.empty();
        }

        /**
         * The words a question opens with, removed until something that could
         * be a title is left.
         *
         * <p>"is dune" becomes "dune"; "is it" becomes nothing, which is the
         * right answer for a question that names no book.</p>
         */
        private static String stripLeadingWords(String before) {
            String term = before.replaceAll("[?!.,;:]+", " ").trim();

            List<String> openers = List.of("what", "which", "is", "are", "was", "were", "do", "does", "did",
                    "you", "have", "has", "got", "can", "i", "we", "tell", "me", "the", "a", "an", "any",
                    "some", "book", "books", "this", "that", "it", "there", "still");

            boolean stripped = true;
            while (stripped && !term.isEmpty()) {
                stripped = false;
                for (String opener : openers) {
                    if (term.equals(opener)) {
                        return "";
                    }
                    if (term.startsWith(opener + " ")) {
                        term = term.substring(opener.length() + 1).trim();
                        stripped = true;
                        break;
                    }
                }
            }

            return term;
        }

        /** The words after the phrase, without the punctuation or filler around them. */
        private static String clean(String rest) {
            String term = rest.replaceAll("[?!.,;:]+", " ").trim();

            for (String filler : List.of("the ", "a ", "an ", "any ", "some ", "books ", "book ")) {
                if (term.startsWith(filler)) {
                    term = term.substring(filler.length()).trim();
                }
            }

            // A term long enough to be a sentence is not a title someone typed;
            // searching for it would match nothing and read like a failure.
            return term.length() > 100 ? term.substring(0, 100).trim() : term;
        }
    }
}
