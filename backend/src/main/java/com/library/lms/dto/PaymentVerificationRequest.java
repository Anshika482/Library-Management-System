package com.library.lms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What a client sends back after paying: the provider's two references and the
 * signature over them.
 *
 * <p><b>None of it is trusted.</b> The signature is recomputed on the server
 * with a secret the client does not have, and the fine moves only if it
 * matches. The fields are bounded so nothing outsized reaches the check.</p>
 *
 * <p>There is no field here for a card, and none may be added: this object is
 * built from a request body, and a card number in a request body is a card
 * number in a log the moment anything goes wrong.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class PaymentVerificationRequest {

    @NotBlank(message = "The provider order id is required")
    @Size(max = 100, message = "The provider order id must not exceed 100 characters")
    private String providerOrderId;

    @NotBlank(message = "The provider payment id is required")
    @Size(max = 100, message = "The provider payment id must not exceed 100 characters")
    private String providerPaymentId;

    @NotBlank(message = "The signature is required")
    @Size(max = 256, message = "The signature must not exceed 256 characters")
    private String signature;
}
