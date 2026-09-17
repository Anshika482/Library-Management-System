package com.library.lms.entity;

/**
 * Whether a loan's fine has been settled, kept apart from how much it is.
 *
 * <p>The amount says what was owed; this says what happened about it. Keeping
 * them separate means recording a payment never touches the amount, so the
 * record of what a late return cost survives the payment intact.</p>
 *
 * <p>Stored by name, like {@link TransactionStatus}, so reordering these
 * constants cannot change what a stored row means.</p>
 */
public enum FinePaymentStatus {

    /** The fine came to nothing - the book was back on time - so there is nothing to pay. */
    NOT_REQUIRED,

    /** A fine is owed and no payment has been recorded. */
    UNPAID,

    /** A member of staff has recorded that the fine was paid. */
    PAID
}
