package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a production instance must know before it may take card payments.
 *
 * <p>The rule this pins down is worth more than it looks: the sandbox gateway
 * marks a fine paid, writes the audit event and answers the member, while no
 * money moves anywhere and nothing inside the application notices. Startup is
 * the only place that can tell the difference, so the provider must be named,
 * and named as the real one.</p>
 *
 * <p>Called directly rather than through a context, like the validators beside
 * it: what matters is the rule, not the wiring.</p>
 */
class ProductionPaymentValidatorTest {

    /** Test-only credentials, never real ones. */
    private static final String KEY_ID = "rzp_test_key_id";

    private static final String KEY_SECRET = "test-only-razorpay-secret";

    @Test
    void razorpayWithBothCredentialsIsAccepted() {
        assertThatCode(() -> ProductionPaymentValidator.validate("razorpay", KEY_ID, KEY_SECRET))
                .doesNotThrowAnyException();
    }

    @Test
    void theProviderIsReadWhateverItsCaseOrSpacing() {
        assertThatCode(() -> ProductionPaymentValidator.validate("  RaZoRpAy  ", KEY_ID, KEY_SECRET))
                .doesNotThrowAnyException();
    }

    // ---------- the sandbox must not follow a deployment into production ----------

    @Test
    void theSandboxIsRefused() {
        assertThatThrownBy(() -> ProductionPaymentValidator.validate("hmac-sandbox", KEY_ID, KEY_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("razorpay");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankProviderIsRefusedRatherThanDefaulted(String provider) {
        assertThatThrownBy(() -> ProductionPaymentValidator.validate(provider, KEY_ID, KEY_SECRET))
                .as("the development default must not reach production by omission")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_GATEWAY_PROVIDER");
    }

    @Test
    void anUnsetProviderIsRefused() {
        assertThatThrownBy(() -> ProductionPaymentValidator.validate(null, KEY_ID, KEY_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_GATEWAY_PROVIDER");
    }

    @Test
    void aProviderNobodyImplementsIsRefused() {
        assertThatThrownBy(() -> ProductionPaymentValidator.validate("stripe", KEY_ID, KEY_SECRET))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- named, but unusable ----------

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void razorpayWithoutAKeyIdIsRefused(String keyId) {
        assertThatThrownBy(() -> ProductionPaymentValidator.validate("razorpay", keyId, KEY_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_GATEWAY_KEY_ID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void razorpayWithoutASecretIsRefused(String keySecret) {
        assertThatThrownBy(() -> ProductionPaymentValidator.validate("razorpay", KEY_ID, keySecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_GATEWAY_KEY_SECRET");
    }

    @Test
    void unsetCredentialsAreRefused() {
        assertThatThrownBy(() -> ProductionPaymentValidator.validate("razorpay", null, KEY_SECRET))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProductionPaymentValidator.validate("razorpay", KEY_ID, null))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- and nothing is quoted back ----------

    @Test
    void noMessageEverQuotesACredential() {
        for (String[] settings : new String[][] {
                {null, KEY_ID, KEY_SECRET},
                {"hmac-sandbox", KEY_ID, KEY_SECRET},
                {"razorpay", "", KEY_SECRET},
                {"razorpay", KEY_ID, ""}}) {
            assertThatThrownBy(() -> ProductionPaymentValidator.validate(settings[0], settings[1], settings[2]))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain(KEY_SECRET)
                            .doesNotContain(KEY_ID));
        }
    }
}
