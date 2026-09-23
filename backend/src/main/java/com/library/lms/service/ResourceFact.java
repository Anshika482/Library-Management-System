package com.library.lms.service;

import com.library.lms.entity.ResourceType;

/**
 * One digital resource, reduced to what an assistant may be told about it.
 *
 * <p><b>Four fields, and the list is closed.</b> No URL - a link is the one
 * thing worth exfiltrating from an answer, and it is also the field most likely
 * to become a signed, time-limited address once files are hosted somewhere. No
 * id, no version, no timestamps, no library, and no {@code enabled}: whether a
 * resource is switched off is a fact about the library's decisions, and a
 * member learns it by the resource simply not being there. Nothing about a
 * member, a loan or a fine can appear because no such field exists here.</p>
 *
 * <p><b>The title and description are staff-written free text, and this is the
 * only untrusted content that reaches a prompt.</b> Whoever renders it must
 * mark it as data rather than instruction - see the providers - and it is
 * shortened here so one long description cannot crowd out everything else.</p>
 *
 * @param bookTitle the book this hangs off, so an answer can place it
 */
public record ResourceFact(String bookTitle, String title, String description, ResourceType resourceType) {

    /** How much of a description an assistant is given. Enough to be useful, not enough to fill a prompt. */
    static final int MAX_DESCRIPTION = 200;

    public ResourceFact {
        description = shorten(description);
    }

    private static String shorten(String description) {
        if (description == null) {
            return null;
        }

        String trimmed = description.trim();

        return trimmed.length() <= MAX_DESCRIPTION ? trimmed : trimmed.substring(0, MAX_DESCRIPTION).trim() + "...";
    }

    /** One line an assistant can read out, with no link and no record of any person in it. */
    public String describe() {
        StringBuilder line = new StringBuilder(resourceType == null ? "Resource" : resourceType.name())
                .append(": \"").append(title).append("\"");

        if (bookTitle != null && !bookTitle.isBlank()) {
            line.append(" (for \"").append(bookTitle).append("\")");
        }
        if (description != null && !description.isBlank()) {
            line.append(" - ").append(description);
        }

        return line.toString();
    }
}
