package com.library.lms.exception;

/**
 * Raised when an account is asked for with a role this endpoint will not grant.
 *
 * <p>Administrators are the case it exists for. An administrator may create the
 * staff and members of their own library, but not another administrator:
 * otherwise the one privilege that cannot be handed out by anybody else -
 * control over who may use the system - would be self-propagating, and a single
 * compromised administrator account could quietly mint more of itself.</p>
 *
 * <p>The message names the roles that <i>are</i> allowed rather than describing
 * what was refused, because that is the part a caller can act on.</p>
 */
public class RoleNotAssignableException extends RuntimeException {

    private static final String MESSAGE = "Only ROLE_MEMBER or ROLE_LIBRARIAN accounts may be created.";

    public RoleNotAssignableException() {
        super(MESSAGE);
    }
}
