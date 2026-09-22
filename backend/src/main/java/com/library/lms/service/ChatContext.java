package com.library.lms.service;

import com.library.lms.entity.Role;

/**
 * Who is asking, and from where.
 *
 * <p><b>Every field comes from the authenticated account</b>, never from the
 * request body. That is what makes library isolation hold for a chat answer the
 * way it holds for every query in this application: an implementation that
 * looks anything up can only be told one library, and it is the caller's own.
 * A caller cannot name a library, a member or a loan belonging to anyone
 * else.</p>
 *
 * <p><b>Nothing sensitive is here.</b> Ids, a role and a library name - no
 * password, no hash, no token, and no authority string the security layer
 * depends on. An implementation that one day sends this to a provider sends
 * nothing that would matter if it were read.</p>
 *
 * <p><b>The catalogue, when there is one to show.</b> A catalogue question is
 * answered from the caller's own library before any assistant is called, and
 * the result travels here as a {@link CatalogueLookup}. An assistant is given
 * those facts and no way to ask for more: it holds no repository, so it cannot
 * widen the search or reach another library's shelves.</p>
 *
 * @param libraryId   the caller's library, the only one an answer may draw on
 * @param libraryName that library's name, so an answer can say where it is from
 * @param userId      the caller, for answers about their own loans later
 * @param role        what the caller may be told
 * @param catalogue   what their library holds, when the question was about it
 */
public record ChatContext(Long libraryId, String libraryName, Long userId, Role role, CatalogueLookup catalogue) {

    /** A context with no catalogue behind it - the shape every non-catalogue question has. */
    public ChatContext(Long libraryId, String libraryName, Long userId, Role role) {
        this(libraryId, libraryName, userId, role, null);
    }

    /** The same caller, now with what their library holds. */
    public ChatContext withCatalogue(CatalogueLookup lookup) {
        return new ChatContext(libraryId, libraryName, userId, role, lookup);
    }

    /** Whether a catalogue question was recognised and looked up. */
    public boolean hasCatalogue() {
        return catalogue != null;
    }

    /** Whether the caller is staff, and so may be told about a library's own workings. */
    public boolean isStaff() {
        return role == Role.ROLE_ADMIN || role == Role.ROLE_LIBRARIAN;
    }
}
