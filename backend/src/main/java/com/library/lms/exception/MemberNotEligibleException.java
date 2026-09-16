package com.library.lms.exception;

/**
 * Raised when the account a book is being issued to cannot borrow one.
 *
 * <p>Three different situations produce it - the account is not a member, it is
 * disabled, or it is locked - and all three carry the <b>same</b> message on
 * purpose. Saying which one applied would turn the issue endpoint into a way to
 * read another account's status, and there is no endpoint that discloses that
 * deliberately.</p>
 *
 * <p>An account that does not exist, or belongs to another library, is a
 * different case entirely: that raises {@code UserNotFoundException} and comes
 * back as a 404, exactly as a missing id does.</p>
 */
public class MemberNotEligibleException extends RuntimeException {

    /** The one sentence every ineligible borrower produces. */
    private static final String MESSAGE = "Books can only be issued to an active member account.";

    public MemberNotEligibleException() {
        super(MESSAGE);
    }
}
