package com.library.lms.dto;

import java.time.LocalDateTime;

import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.PaymentStatus;

/**
 * How a payment ended, and what it left the loan's fine at.
 *
 * <p>References and states only. Nothing about a card is here because nothing
 * about a card exists anywhere in this application.</p>
 *
 * @param loanId            the loan whose fine this settles
 * @param finePaymentStatus the fine after this payment - PAID once it succeeded
 */
public record PaymentResponse(Long paymentId, PaymentStatus status, String providerPaymentId, Long loanId,
        FinePaymentStatus finePaymentStatus, LocalDateTime paidAt) {
}
