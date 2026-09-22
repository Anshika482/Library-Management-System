package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/**
 * The signature check that decides whether a fine may be marked paid.
 *
 * <p>Everything here is a test-only key: the secret below is written into this
 * file precisely because it is worthless, and no real credential ever appears
 * in the repository.</p>
 */
class HmacPaymentGatewayTest {

    /** Test-only values, never a real merchant credential. */
    private static final String KEY_ID = "test_key_id";

    private static final String KEY_SECRET = "test-only-gateway-secret";

    private static final String ORDER = "order_abc123";

    private static final String PAYMENT = "pay_xyz789";

    private final HmacPaymentGateway gateway = new HmacPaymentGateway("hmac-sandbox", KEY_ID, KEY_SECRET);

    /** What the provider would send: HMAC-SHA256 over "order|payment", hex-encoded. */
    private static String signature(String secret, String order, String payment) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        return HexFormat.of().formatHex(mac.doFinal((order + "|" + payment).getBytes(StandardCharsets.UTF_8)));
    }

    // ---------- configuration ----------

    @Test
    void bothHalvesOfTheCredentialAreNeeded() {
        assertThat(gateway.configured()).isTrue();
        assertThat(new HmacPaymentGateway("p", "", KEY_SECRET).configured()).isFalse();
        assertThat(new HmacPaymentGateway("p", KEY_ID, "").configured()).isFalse();
        assertThat(new HmacPaymentGateway("p", null, null).configured()).isFalse();
        assertThat(new HmacPaymentGateway("p", "  ", "  ").configured()).isFalse();
    }

    @Test
    void anUnconfiguredGatewayOpensNoOrderAndVerifiesNothing() throws Exception {
        HmacPaymentGateway unconfigured = new HmacPaymentGateway("p", "", "");

        assertThatThrownBy(() -> unconfigured.createOrder("1", BigDecimal.ONE, "INR"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(unconfigured.verify(ORDER, PAYMENT, signature(KEY_SECRET, ORDER, PAYMENT)))
                .as("without a secret nothing can be proved, so nothing is")
                .isFalse();
    }

    // ---------- orders ----------

    @Test
    void everyOrderReferenceIsDifferentAndDescribesNothing() {
        List<String> references = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            references.add(gateway.createOrder("42", new BigDecimal("3.50"), "INR"));
        }

        assertThat(references).doesNotHaveDuplicates();

        // "order_" and 32 random hex characters, and nothing else: a reference
        // is quoted in URLs and to the provider, so the loan id and the amount
        // must not be recoverable from it. Pinning the shape says that; a
        // "does not contain 42" check would not, since random hex contains any
        // given pair of digits often enough by chance.
        assertThat(references).allSatisfy(reference -> assertThat(reference).matches("order_[0-9a-f]{32}"));
    }

    // ---------- verification ----------

    @Test
    void theProvidersOwnSignatureVerifies() throws Exception {
        assertThat(gateway.verify(ORDER, PAYMENT, signature(KEY_SECRET, ORDER, PAYMENT))).isTrue();
    }

    @Test
    void anUppercaseOrPaddedSignatureStillVerifies() throws Exception {
        String signature = signature(KEY_SECRET, ORDER, PAYMENT);

        assertThat(gateway.verify(ORDER, PAYMENT, signature.toUpperCase(java.util.Locale.ROOT))).isTrue();
        assertThat(gateway.verify(ORDER, PAYMENT, "  " + signature + "  ")).isTrue();
    }

    @Test
    void aSignatureFromAnotherSecretIsRefused() throws Exception {
        assertThat(gateway.verify(ORDER, PAYMENT, signature("a-different-secret", ORDER, PAYMENT)))
                .as("this is what a forged payment looks like")
                .isFalse();
    }

    @Test
    void aTamperedOrderOrPaymentReferenceIsRefused() throws Exception {
        String signature = signature(KEY_SECRET, ORDER, PAYMENT);

        assertThat(gateway.verify("order_other", PAYMENT, signature)).isFalse();
        assertThat(gateway.verify(ORDER, "pay_other", signature)).isFalse();
        assertThat(gateway.verify(PAYMENT, ORDER, signature)).as("the two cannot be swapped").isFalse();
    }

    @Test
    void aSignatureThatIsNotEvenHexIsRefusedRatherThanThrowing() {
        assertThat(gateway.verify(ORDER, PAYMENT, "not-a-signature")).isFalse();
        assertThat(gateway.verify(ORDER, PAYMENT, "")).isFalse();
        assertThat(gateway.verify(ORDER, PAYMENT, "abc")).as("odd length").isFalse();
    }

    @Test
    void nullsAreRefusedRatherThanThrowing() {
        assertThat(gateway.verify(null, PAYMENT, "aa")).isFalse();
        assertThat(gateway.verify(ORDER, null, "aa")).isFalse();
        assertThat(gateway.verify(ORDER, PAYMENT, null)).isFalse();
    }

    @Test
    void oneWrongByteIsAsWrongAsAWholeWrongSignature() throws Exception {
        String signature = signature(KEY_SECRET, ORDER, PAYMENT);
        char last = signature.charAt(signature.length() - 1);
        String almost = signature.substring(0, signature.length() - 1) + (last == 'a' ? 'b' : 'a');

        assertThat(gateway.verify(ORDER, PAYMENT, almost)).isFalse();
    }

    // ---------- the secret stays put ----------

    @Test
    void theSecretIsNeverExposedByTheGatewaysOwnApi() {
        List<String> readable = new ArrayList<>();
        for (Method method : HmacPaymentGateway.class.getMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == String.class
                    && !method.getName().equals("toString")) {
                readable.add(method.getName());
            }
        }

        assertThat(readable)
                .as("the key id is public and the secret has no getter")
                .containsExactlyInAnyOrder("name", "keyId");
    }

    @Test
    void theSecretIsHeldPrivatelyAndNowhereElse() {
        for (Field field : HmacPaymentGateway.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertThat(Modifier.isPrivate(field.getModifiers())).as(field.getName()).isTrue();
                assertThat(Modifier.isFinal(field.getModifiers())).as(field.getName()).isTrue();
            }
        }
    }

    @Test
    void theSecretDoesNotLeakThroughToString() {
        assertThat(gateway.toString()).doesNotContain(KEY_SECRET);
    }
}
