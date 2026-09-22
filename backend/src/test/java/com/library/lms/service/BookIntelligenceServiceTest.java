package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.library.lms.entity.Book;
import com.library.lms.entity.Category;
import com.library.lms.entity.Library;
import com.library.lms.repository.BookRepository;

/**
 * What the catalogue is asked, what it answers with, and what it refuses to
 * carry.
 *
 * <p>The repository is a mock, so the two things that matter can be read
 * exactly: that every query names the caller's own library, and that what comes
 * back is a list of book facts with nothing about any person in it.</p>
 */
class BookIntelligenceServiceTest {

    private static final long LIBRARY_ID = 7L;

    private final BookRepository bookRepository = mock(BookRepository.class);

    private final BookIntelligenceService intelligence = new BookIntelligenceService(bookRepository);

    @BeforeEach
    void noMatchesUnlessSaidOtherwise() {
        when(bookRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        when(bookRepository.findByLibraryIdAndCategoryName(anyLong(), anyString(), any(Pageable.class)))
                .thenReturn(Page.empty());
    }

    private static Book book(String title, String author, String category, int available, int total) {
        Library library = new Library();
        library.setId(LIBRARY_ID);

        Book book = new Book();
        book.setId(31L);
        book.setTitle(title);
        book.setAuthor(author);
        book.setIsbn("978-0000000000");
        book.setAvailableCopies(available);
        book.setTotalCopies(total);
        book.setLibrary(library);

        if (category != null) {
            Category entity = new Category();
            entity.setId(3L);
            entity.setName(category);
            book.setCategory(entity);
        }

        return book;
    }

    private void libraryHolds(Book... books) {
        when(bookRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(books)));
    }

    private void categoryHolds(Book... books) {
        when(bookRepository.findByLibraryIdAndCategoryName(anyLong(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(books)));
    }

    // ---------- which questions reach the catalogue ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "Do you have Dune?",
            "I am looking for Dune",
            "search for Dune",
            "find Dune",
            "is there a book called Dune"})
    void aTitleQuestionIsRecognised(String question) {
        Optional<CatalogueLookup> lookup = intelligence.lookup(question, LIBRARY_ID);

        assertThat(lookup).isPresent();
        assertThat(lookup.get().intent()).isEqualTo(CatalogueIntent.TITLE);
        assertThat(lookup.get().term()).contains("dune");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What books do you have by Frank Herbert?",
            "anything written by Frank Herbert",
            "author Frank Herbert"})
    void anAuthorQuestionIsRecognised(String question) {
        Optional<CatalogueLookup> lookup = intelligence.lookup(question, LIBRARY_ID);

        assertThat(lookup).isPresent();
        assertThat(lookup.get().intent()).isEqualTo(CatalogueIntent.AUTHOR);
        assertThat(lookup.get().term()).contains("frank herbert");
    }

    @Test
    void aCategoryQuestionIsRecognisedAndSearchedByCategory() {
        categoryHolds(book("Dune", "Frank Herbert", "Science Fiction", 2, 3));

        Optional<CatalogueLookup> lookup = intelligence.lookup("what is in the category science fiction",
                LIBRARY_ID);

        assertThat(lookup).isPresent();
        assertThat(lookup.get().intent()).isEqualTo(CatalogueIntent.CATEGORY);
        verify(bookRepository).findByLibraryIdAndCategoryName(eq(LIBRARY_ID), anyString(), any(Pageable.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "is Dune available",
            "are there any copies of Dune",
            "can I borrow Dune"})
    void anAvailabilityQuestionIsRecognised(String question) {
        Optional<CatalogueLookup> lookup = intelligence.lookup(question, LIBRARY_ID);

        assertThat(lookup).isPresent();
        assertThat(lookup.get().intent()).isEqualTo(CatalogueIntent.AVAILABILITY);
    }

    @Test
    void aDetailsQuestionIsRecognised() {
        Optional<CatalogueLookup> lookup = intelligence.lookup("tell me about Dune", LIBRARY_ID);

        assertThat(lookup).isPresent();
        assertThat(lookup.get().intent()).isEqualTo(CatalogueIntent.DETAILS);
        assertThat(lookup.get().term()).isEqualTo("dune");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "hello",
            "how do I pay a fine?",
            "what are your opening hours",
            "how do I reset my password"})
    void aQuestionThatIsNotAboutTheCatalogueRunsNoQuery(String question) {
        assertThat(intelligence.lookup(question, LIBRARY_ID)).isEmpty();

        verify(bookRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        verify(bookRepository, never()).findByLibraryIdAndCategoryName(anyLong(), anyString(), any(Pageable.class));
    }

    @Test
    void aTriggerWithNothingAfterItIsNotASearch() {
        assertThat(intelligence.lookup("is it available?", LIBRARY_ID))
                .as("no book is named, so there is nothing to look up")
                .isEmpty();
    }

    @Test
    void aMissingQuestionOrLibraryRunsNoQuery() {
        assertThat(intelligence.lookup(null, LIBRARY_ID)).isEmpty();
        assertThat(intelligence.lookup("   ", LIBRARY_ID)).isEmpty();
        assertThat(intelligence.lookup("do you have Dune", null)).isEmpty();

        verify(bookRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    // ---------- what comes back ----------

    @Test
    void aMatchCarriesTheBooksCatalogueFieldsAndItsAvailability() {
        libraryHolds(book("Dune", "Frank Herbert", "Science Fiction", 2, 3));

        BookFact fact = intelligence.lookup("do you have Dune", LIBRARY_ID).orElseThrow().books().get(0);

        assertThat(fact.title()).isEqualTo("Dune");
        assertThat(fact.author()).isEqualTo("Frank Herbert");
        assertThat(fact.category()).isEqualTo("Science Fiction");
        assertThat(fact.availableCopies()).isEqualTo(2);
        assertThat(fact.totalCopies()).isEqualTo(3);
        assertThat(fact.available()).isTrue();
    }

    @Test
    void aBookWithNoCopiesLeftIsReportedAsOut() {
        libraryHolds(book("Dune", "Frank Herbert", "Science Fiction", 0, 3));

        BookFact fact = intelligence.lookup("is Dune available", LIBRARY_ID).orElseThrow().books().get(0);

        assertThat(fact.available()).isFalse();
        assertThat(fact.describe()).contains("all 3 copies are out");
    }

    @Test
    void aBookWithNoCategoryStillDescribesCleanly() {
        libraryHolds(book("Dune", "Frank Herbert", null, 1, 1));

        assertThat(intelligence.lookup("do you have Dune", LIBRARY_ID).orElseThrow().books().get(0).describe())
                .doesNotContain("null");
    }

    @Test
    void aLibraryThatHoldsNothingMatchingAnswersEmptyRatherThanNotAtAll() {
        CatalogueLookup lookup = intelligence.lookup("do you have Dune", LIBRARY_ID).orElseThrow();

        assertThat(lookup.empty())
                .as("an empty result is an answer - the assistant must say so, not guess")
                .isTrue();
        assertThat(lookup.term()).isEqualTo("dune");
    }

    // ---------- one library, and a bounded amount of it ----------

    @Test
    void everySearchNamesTheCallersOwnLibrary() {
        intelligence.lookup("do you have Dune", LIBRARY_ID);

        verify(bookRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void aCategorySearchNamesTheCallersOwnLibrary() {
        intelligence.lookup("category science fiction", LIBRARY_ID);

        verify(bookRepository).findByLibraryIdAndCategoryName(eq(LIBRARY_ID), anyString(), any(Pageable.class));
    }

    @Test
    void noMoreThanAHandfulOfBooksIsEverAskedFor() {
        intelligence.lookup("do you have Dune", LIBRARY_ID);

        org.mockito.ArgumentCaptor<Pageable> pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(bookRepository).findAll(any(Specification.class), pageable.capture());

        assertThat(pageable.getValue().getPageSize())
                .as("a question cannot pull a whole catalogue into a prompt")
                .isEqualTo(BookIntelligenceService.MAX_BOOKS);
    }

    // ---------- what a fact may never carry ----------

    @Test
    void aBookFactHasNoFieldThatCouldHoldSomebodysData() {
        List<String> components = Arrays.stream(BookFact.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components)
                .as("a catalogue entry and two counts - nothing about a person")
                .containsExactlyInAnyOrder("title", "author", "category", "isbn", "availableCopies",
                        "totalCopies");
    }

    @Test
    void nothingAboutALoanOrAMemberIsEverRead() {
        libraryHolds(book("Dune", "Frank Herbert", "Science Fiction", 2, 3));

        CatalogueLookup lookup = intelligence.lookup("do you have Dune", LIBRARY_ID).orElseThrow();

        assertThat(lookup.toString().toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("password")
                .doesNotContain("@")
                .doesNotContain("token")
                .doesNotContain("borrower")
                .doesNotContain("member");
    }

    @Test
    void theServiceReachesOnlyTheBookRepository() {
        assertThat(Arrays.stream(BookIntelligenceService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getSimpleName())
                .toList())
                .as("no user, loan, payment or audit repository is reachable from here")
                .containsExactly("BookRepository");
    }
}
