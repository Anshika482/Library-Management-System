package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.library.lms.exception.PaymentGatewayUnavailableException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The Razorpay gateway, against a stubbed Orders API.
 *
 * <p><b>No request leaves this JVM.</b> {@link MockRestServiceServer} answers
 * in place of Razorpay, which is what lets these tests assert the things that
 * matter and would otherwise need a live merchant account: that the order is
 * opened server-side, that the amount is sent in paise, that the credentials go
 * in an Authorization header and nowhere else, and that a provider failure
 * becomes a 503 rather than an echo of whatever Razorpay said.</p>
 *
 * <p>Every credential here is a test-only value.</p>
 */
class RazorpayPaymentGatewayTest {

    /** Test-only merchant credentials, never a real Razorpay key. */
    private static final String KEY_ID = "rzp_test_key_id";

    private static final String KEY_SECRET = "test-only-razorpay-secret";

    private static final String BASE_URL = "https://razorpay.test.invalid";

    private RestClient.Builder builder;

    private MockRestServiceServer server;

    private RazorpayPaymentGateway gateway;

    @BeforeEach
    void stubTheProvider() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new RazorpayPaymentGateway(builder, BASE_URL, KEY_ID, KEY_SECRET);
    }

    private static String expectedAuthorization() {
        return "Basic " + Base64.getEncoder()
                .encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));
    }

    /** What Razorpay returns for a created order, trimmed to what this gateway reads. */
    private static String orderResponse(String id) {
        return """
                {"id":"%s","entity":"order","amount":500,"currency":"INR","status":"created"}
                """.formatted(id);
    }

    // ---------- opening an order ----------

    @Test
    void anOrderIsOpenedOnRazorpayAndItsIdIsReturned() {
        server.expect(requestTo(BASE_URL + "/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", expectedAuthorization()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.amount").value(500))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.receipt").value("loan-42"))
                .andRespond(withSuccess(orderResponse("order_Nx1abcDEF"), MediaType.APPLICATION_JSON));

        String orderId = gateway.createOrder("42", new BigDecimal("5.00"), "INR");

        assertThat(orderId).isEqualTo("order_Nx1abcDEF");
        server.verify();
    }

    @Test
    void theAmountIsSentInPaise() {
        assertThat(RazorpayPaymentGateway.paise(new BigDecimal("5.00"))).isEqualTo(500);
        assertThat(RazorpayPaymentGateway.paise(new BigDecimal("12.50"))).isEqualTo(1250);
        assertThat(RazorpayPaymentGateway.paise(new BigDecimal("0.01"))).isEqualTo(1);
        assertThat(RazorpayPaymentGateway.paise(new BigDecimal("1")))
                .as("a whole rupee is a hundred paise, not one")
                .isEqualTo(100);
        assertThat(RazorpayPaymentGateway.paise(new BigDecimal("1.005")))
                .as("rounded half-up at two decimals before it is sent")
                .isEqualTo(101);
    }

    @Test
    void theReceiptCarriesTheLoanAndNothingElse() {
        server.expect(requestTo(BASE_URL + "/v1/orders"))
                .andExpect(jsonPath("$.receipt").value("loan-987654"))
                .andRespond(withSuccess(orderResponse("order_ok"), MediaType.APPLICATION_JSON));

        gateway.createOrder("987654", new BigDecimal("2.00"), "INR");

        server.verify();
    }

    @Test
    void theCurrencyIsNormalisedAndDefaultsToRupees() {
        server.expect(requestTo(BASE_URL + "/v1/orders"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andRespond(withSuccess(orderResponse("order_ok"), MediaType.APPLICATION_JSON));

        gateway.createOrder("1", new BigDecimal("1.00"), "inr");

        server.verify();
    }

    // ---------- when Razorpay does not play along ----------

    @Test
    void aRefusalFromRazorpayBecomesAnUnavailableGateway() {
        server.expect(requestTo(BASE_URL + "/v1/orders"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":{\"description\":\"amount must be at least 100\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.createOrder("42", new BigDecimal("0.50"), "INR"))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        server.verify();
    }

    @Test
    void aServerErrorFromRazorpayBecomesAnUnavailableGateway() {
        server.expect(requestTo(BASE_URL + "/v1/orders")).andRespond(withServerError());

        assertThatThrownBy(() -> gateway.createOrder("42", new BigDecimal("5.00"), "INR"))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
    }

    @Test
    void anAnswerWithNoOrderIdIsRefusedRatherThanStored() {
        server.expect(requestTo(BASE_URL + "/v1/orders"))
                .andRespond(withSuccess("{\"entity\":\"order\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.createOrder("42", new BigDecimal("5.00"), "INR"))
                .as("an order with no id is not an order")
                .isInstanceOf(PaymentGatewayUnavailableException.class);
    }

    @Test
    void anUnconfiguredGatewayCallsNothing() {
        RazorpayPaymentGateway unconfigured = new RazorpayPaymentGateway(builder, BASE_URL, KEY_ID, "");

        assertThat(unconfigured.configured()).isFalse();
        assertThatThrownBy(() -> unconfigured.createOrder("42", new BigDecimal("5.00"), "INR"))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    // ---------- verification, which is the same check as the sandbox's ----------

    @Test
    void razorpaysOwnSignatureVerifies() throws Exception {
        String signature = sign(KEY_SECRET, "order_abc", "pay_xyz");

        assertThat(gateway.verify("order_abc", "pay_xyz", signature)).isTrue();
    }

    @Test
    void aForgedOrAlteredSignatureIsRefused() throws Exception {
        assertThat(gateway.verify("order_abc", "pay_xyz", sign("another-secret", "order_abc", "pay_xyz")))
                .isFalse();
        assertThat(gateway.verify("order_other", "pay_xyz", sign(KEY_SECRET, "order_abc", "pay_xyz")))
                .isFalse();
        assertThat(gateway.verify("order_abc", "pay_other", sign(KEY_SECRET, "order_abc", "pay_xyz")))
                .isFalse();
        assertThat(gateway.verify("order_abc", "pay_xyz", "not-hex")).isFalse();
        assertThat(gateway.verify("order_abc", "pay_xyz", null)).isFalse();
    }

    @Test
    void theSandboxAndRazorpayAgreeOnWhatAValidSignatureIs() throws Exception {
        HmacPaymentGateway sandbox = new HmacPaymentGateway("hmac-sandbox", KEY_ID, KEY_SECRET);
        String signature = sign(KEY_SECRET, "order_abc", "pay_xyz");

        assertThat(gateway.verify("order_abc", "pay_xyz", signature))
                .as("switching provider must not change what proves a payment")
                .isEqualTo(sandbox.verify("order_abc", "pay_xyz", signature));
    }

    // ---------- the secret stays put ----------

    @Test
    void theSecretIsNotLoggedWhenTheProviderFails() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        try {
            server.expect(requestTo(BASE_URL + "/v1/orders"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                            .body("{\"error\":{\"description\":\"Authentication failed\"}}")
                            .contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> gateway.createOrder("42", new BigDecimal("5.00"), "INR"))
                    .isInstanceOf(PaymentGatewayUnavailableException.class);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line)
                        .doesNotContain(KEY_SECRET)
                        .doesNotContain(expectedAuthorization())
                        .as("nor what the provider said, which quotes the request back")
                        .doesNotContain("Authentication failed"));
    }

    @Test
    void theSecretIsNotInTheGatewaysOwnDescription() {
        assertThat(gateway.toString()).doesNotContain(KEY_SECRET);
        assertThat(gateway.keyId()).as("the public half is readable").isEqualTo(KEY_ID);
    }

    /** What Razorpay signs a completed payment with. */
    private static String sign(String secret, String orderId, String paymentId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        return HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
    }
}
