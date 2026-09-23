package com.library.lms.service;

import java.util.List;

/**
 * What the catalogue was asked, and what it answered.
 *
 * <p>Built by {@link BookIntelligenceService} from the caller's own library
 * before any assistant is called. An assistant is handed this and nothing else:
 * it never sees a repository, a query or an entity, so it cannot widen the
 * search, reach another library, or read a field that is not a
 * {@link BookFact}.</p>
 *
 * <p><b>An empty list is an answer, not a failure.</b> It means the library
 * genuinely holds nothing matching, and an assistant is expected to say so
 * rather than invent something.</p>
 *
 * <p><b>Resources hang off the books that matched.</b> They are the digital
 * things attached to those books - a PDF, an EPUB, a video, a link - and a
 * member is only ever given the enabled ones. Their text is written by staff
 * rather than by this application, so whoever renders it treats it as data.</p>
 *
 * @param intent    what the question seemed to be about
 * @param term      what was searched for, as the caller wrote it
 * @param books     what the caller's own library holds, at most a handful
 * @param resources what those books have to read online, at most a handful
 */
public record CatalogueLookup(CatalogueIntent intent, String term, List<BookFact> books,
        List<ResourceFact> resources) {

    public CatalogueLookup {
        books = books == null ? List.of() : List.copyOf(books);
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    /** A lookup that found books and nothing to read online. */
    public CatalogueLookup(CatalogueIntent intent, String term, List<BookFact> books) {
        this(intent, term, books, List.of());
    }

    /** Whether the library held nothing matching at all. */
    public boolean empty() {
        return books.isEmpty() && resources.isEmpty();
    }

    /** Whether any of the matching books had something to read online. */
    public boolean hasResources() {
        return !resources.isEmpty();
    }
}
