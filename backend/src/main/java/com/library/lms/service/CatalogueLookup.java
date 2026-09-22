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
 * @param intent what the question seemed to be about
 * @param term   what was searched for, as the caller wrote it
 * @param books  what the caller's own library holds, at most a handful
 */
public record CatalogueLookup(CatalogueIntent intent, String term, List<BookFact> books) {

    public CatalogueLookup {
        books = books == null ? List.of() : List.copyOf(books);
    }

    /** Whether the library held nothing matching. */
    public boolean empty() {
        return books.isEmpty();
    }
}
