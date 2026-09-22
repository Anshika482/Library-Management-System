package com.library.lms.service;

/** What a caller seems to be asking the catalogue for. */
public enum CatalogueIntent {

    /** A book by its title, or any words from it. */
    TITLE,

    /** What a named author wrote. */
    AUTHOR,

    /** What a library holds in a named category. */
    CATEGORY,

    /** Whether a copy can be borrowed right now. */
    AVAILABILITY,

    /** What a particular book is. */
    DETAILS
}
