package com.library.lms.entity;

/**
 * What an audit event records: the security-sensitive changes to accounts,
 * passwords and libraries.
 *
 * <p>Stored by name in a MySQL {@code ENUM}, so adding an action is a
 * migration - deliberate friction for a list that should only grow with
 * thought. Reads are not audited; only changes are.</p>
 */
public enum AuditAction {

    /** An administrator created a member or librarian account. */
    USER_CREATED,

    /** An administrator enabled, disabled, locked or unlocked an account. */
    USER_STATUS_CHANGED,

    /** An account holder changed their own password, signed in. */
    PASSWORD_CHANGED,

    /** A member of staff set a new password for someone who had forgotten theirs. */
    PASSWORD_RESET_BY_STAFF,

    /** A self-service reset token was issued - or refused - for an account. */
    PASSWORD_RESET_REQUESTED,

    /** A self-service reset token was redeemed - or refused - for an account. */
    PASSWORD_RESET_COMPLETED,

    /** An administrator registered a new library with its first administrator. */
    LIBRARY_CREATED,

    /** The first library and administrator were created at startup from configuration. */
    LIBRARY_BOOTSTRAPPED
}
