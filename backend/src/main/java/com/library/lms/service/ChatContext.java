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
 * @param libraryId   the caller's library, the only one an answer may draw on
 * @param libraryName that library's name, so an answer can say where it is from
 * @param userId      the caller, for answers about their own loans later
 * @param role        what the caller may be told
 */
public record ChatContext(Long libraryId, String libraryName, Long userId, Role role) {

    /** Whether the caller is staff, and so may be told about a library's own workings. */
    public boolean isStaff() {
        return role == Role.ROLE_ADMIN || role == Role.ROLE_LIBRARIAN;
    }
}
