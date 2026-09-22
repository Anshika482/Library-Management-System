package com.library.lms.entity;

/** What kind of thing a digital resource points at. */
public enum ResourceType {

    /** A PDF document. */
    PDF,

    /** An EPUB e-book. */
    EPUB,

    /** A video recording. */
    VIDEO,

    /** A web page, or anything else reached by following a link. */
    LINK
}
