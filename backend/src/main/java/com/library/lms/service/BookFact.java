package com.library.lms.service;

/**
 * One book, reduced to what an assistant may be told about it.
 *
 * <p><b>This is the whole of what leaves the database for a chat answer.</b>
 * A catalogue entry and two counts - nothing about who borrowed it, who is
 * waiting for it, what anyone owes on it, or which account asked. There is no
 * field here that could carry an address, a hash or a token, and the type
 * exists so that adding one would be a deliberate act rather than an
 * accident.</p>
 *
 * @param availableCopies how many are on the shelf now
 * @param totalCopies     how many the library owns
 */
public record BookFact(String title, String author, String category, String isbn, int availableCopies,
        int totalCopies) {

    /** Whether a copy can be borrowed right now. */
    public boolean available() {
        return availableCopies > 0;
    }

    /** One line an assistant can read or repeat, with no record of any person in it. */
    public String describe() {
        StringBuilder line = new StringBuilder("\"").append(title).append("\"");

        if (author != null && !author.isBlank()) {
            line.append(" by ").append(author);
        }
        if (category != null && !category.isBlank()) {
            line.append(" (").append(category).append(")");
        }

        return line.append(" - ")
                .append(available() ? availableCopies + " of " + totalCopies + " copies available now"
                        : "all " + totalCopies + " copies are out")
                .toString();
    }
}
