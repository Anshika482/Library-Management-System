package com.library.lms.entity;

/** Where an online fine payment has got to. */
public enum PaymentStatus {

    /** An order exists with the provider; nothing has been paid yet. */
    CREATED,

    /** The provider's answer was verified here, and the fine was marked paid. */
    SUCCEEDED,

    /** The attempt ended without payment - abandoned, declined, or an answer that did not verify. */
    FAILED
}
