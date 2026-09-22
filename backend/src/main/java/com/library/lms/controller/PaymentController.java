package com.library.lms.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.PaymentOrderResponse;
import com.library.lms.dto.PaymentResponse;
import com.library.lms.dto.PaymentVerificationRequest;
import com.library.lms.service.PaymentService;

import jakarta.validation.Valid;

/**
 * Paying a loan's fine through the payment provider.
 *
 * <p>Two steps, because that is how a checkout works: open an order, then bring
 * back what the provider signed. The money never passes through here - the card
 * is entered on the provider's pages - and the fine is marked paid only after
 * {@link PaymentService} has verified that answer on the server.</p>
 *
 * <p>Both are open to any signed-in caller; which loans they may pay is decided
 * in the service, where the loan's owner is known. Staff can still record a
 * fine paid at the desk with {@code POST /api/transactions/{id}/fine-payment},
 * which is unchanged.</p>
 */
@RestController
@RequestMapping("/api/transactions/{transactionId}")
@Validated
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * POST /api/transactions/{id}/payment-order - opens an order for the fine.
     *
     * <p>Answers 200 with the provider's order reference, the amount and the
     * public merchant key. Asking again while an order is still open returns
     * that same order rather than a second one.</p>
     */
    @PostMapping("/payment-order")
    public ResponseEntity<PaymentOrderResponse> createOrder(@PathVariable Long transactionId,
            Authentication authentication) {
        return ResponseEntity.ok(paymentService.createOrder(transactionId, authentication.getName()));
    }

    /**
     * POST /api/transactions/{id}/payment-verification - settles the fine, if
     * the provider's answer verifies.
     *
     * <p>Answers 200 with the payment and the fine's new state. A signature
     * that is not the provider's over those references is a 400 that says only
     * that the payment could not be verified, and the fine is left alone.
     * Sending the same verified payment again returns the same answer and
     * changes nothing.</p>
     */
    @PostMapping("/payment-verification")
    public ResponseEntity<PaymentResponse> verify(@PathVariable Long transactionId,
            @Valid @RequestBody PaymentVerificationRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(paymentService.verifyAndSettle(transactionId, request, authentication.getName()));
    }
}
