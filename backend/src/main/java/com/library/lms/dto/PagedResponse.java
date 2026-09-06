package com.library.lms.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One page of results, plus the numbers a client needs to navigate.
 *
 * <p>Spring Data's own {@code Page} is deliberately <b>not</b> returned from the
 * controller. Its JSON carries a large amount of internal structure - a nested
 * {@code pageable} object, {@code sort} descriptors, {@code first}/{@code last}
 * flags, {@code numberOfElements} - most of which a frontend never uses, and all
 * of which is Spring's shape rather than ours. Serialising it also produces an
 * unstable contract: the layout has changed between Spring versions.</p>
 *
 * <p>This class keeps the five fields that actually matter and nothing else, so
 * the API stays stable no matter what the framework does.</p>
 *
 * <p>It is generic in {@code T} rather than hard-wired to books, because the
 * shape of a page has nothing to do with what is on it - the same class will
 * serve search or category results if they are paginated later.</p>
 *
 * @param <T> the type of item on the page, here {@link BookResponse}
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {

    /** The items on this page. Empty - never null - when the page is past the end. */
    private List<T> content;

    /** Zero-based page number: 0 is the first page. */
    private int page;

    /** How many items were requested per page. */
    private int size;

    /** How many items exist in total, across every page. */
    private long totalElements;

    /** How many pages those items fill at this page size. */
    private int totalPages;
}
