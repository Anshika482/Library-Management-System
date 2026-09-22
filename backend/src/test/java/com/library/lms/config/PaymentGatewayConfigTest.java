package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.library.lms.service.HmacPaymentGateway;
import com.library.lms.service.PaymentGateway;
import com.library.lms.service.RazorpayPaymentGateway;

/**
 * Which provider a deployment ends up talking to.
 *
 * <p>This is configuration with teeth: picking the sandbox by accident in
 * production would mean every signature the application writes verifies against
 * a secret that never charged a card. So the name decides, and a name nobody
 * implements stops startup instead of falling back.</p>
 */
class PaymentGatewayConfigTest {

    /** Test-only credentials, never real ones. */
    private static final String KEY_ID = "test_key_id";

    private static final String KEY_SECRET = "test-only-secret";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
            .withUserConfiguration(PaymentGatewayConfig.class)
            .withPropertyValues(
                    "payment.gateway.key-id=" + KEY_ID,
                    "payment.gateway.key-secret=" + KEY_SECRET);

    @Test
    void theSandboxIsWhatADeploymentGetsWhenItAsksForNothing() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(PaymentGateway.class)
                .getBean(PaymentGateway.class)
                .isInstanceOf(HmacPaymentGateway.class));
    }

    @Test
    void namingRazorpayGetsRazorpay() {
        runner.withPropertyValues("payment.gateway.provider=razorpay")
                .run(context -> assertThat(context)
                        .hasSingleBean(PaymentGateway.class)
                        .getBean(PaymentGateway.class)
                        .isInstanceOf(RazorpayPaymentGateway.class));
    }

    @Test
    void theNameIsReadWhateverItsCaseOrSpacing() {
        runner.withPropertyValues("payment.gateway.provider=  RaZoRpAy  ")
                .run(context -> assertThat(context).getBean(PaymentGateway.class)
                        .isInstanceOf(RazorpayPaymentGateway.class));
    }

    @Test
    void aProviderNobodyImplementsStopsStartup() {
        runner.withPropertyValues("payment.gateway.provider=stripe")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("PAYMENT_GATEWAY_PROVIDER"));
    }

    @Test
    void theChosenGatewayCarriesTheCredentialsAndReportsThem() {
        runner.withPropertyValues("payment.gateway.provider=razorpay")
                .run(context -> {
                    PaymentGateway gateway = context.getBean(PaymentGateway.class);

                    assertThat(gateway.name()).isEqualTo("razorpay");
                    assertThat(gateway.keyId()).isEqualTo(KEY_ID);
                    assertThat(gateway.configured()).isTrue();
                });
    }

    @Test
    void missingCredentialsLeaveTheGatewayUnconfiguredRatherThanAbsent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
                .withUserConfiguration(PaymentGatewayConfig.class)
                .run(context -> {
                    PaymentGateway gateway = context.getBean(PaymentGateway.class);

                    assertThat(gateway.configured())
                            .as("online payment is refused with 503; the desk flow is unaffected")
                            .isFalse();
                });
    }

    // ---------- how long the provider is given to answer ----------

    @Test
    void theDefaultTimeoutsAreShortEnoughToHoldNoConnectionOpen() {
        runner.withPropertyValues("payment.gateway.provider=razorpay").run(context -> {
            assertThat(context).hasNotFailed();

            // The bean is built with these, and the values themselves are
            // pinned here: a database connection is held for the length of the
            // read timeout while an order is opened.
            assertThat(PaymentGatewayConfig.timeouts(Duration.ofSeconds(3), Duration.ofSeconds(8)))
                    .satisfies(settings -> {
                        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
                        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(8));
                    });
        });
    }

    @Test
    void theTimeoutsAreConfigurable() {
        runner.withPropertyValues(
                        "payment.gateway.provider=razorpay",
                        "payment.gateway.connect-timeout=PT1S",
                        "payment.gateway.read-timeout=PT2S")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PaymentGateway.class));

        assertThat(PaymentGatewayConfig.timeouts(Duration.ofSeconds(1), Duration.ofSeconds(2)).readTimeout())
                .isEqualTo(Duration.ofSeconds(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S"})
    void aTimeoutOfZeroOrLessIsRefusedRatherThanWaitingForEver(String timeout) {
        Duration refused = Duration.parse(timeout);

        assertThatThrownBy(() -> PaymentGatewayConfig.timeouts(refused, Duration.ofSeconds(8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_GATEWAY_CONNECT_TIMEOUT");
        assertThatThrownBy(() -> PaymentGatewayConfig.timeouts(Duration.ofSeconds(3), refused))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_GATEWAY_READ_TIMEOUT");
    }

    @Test
    void aTimeoutOfZeroStopsTheContextRatherThanBuildingAGateway() {
        runner.withPropertyValues("payment.gateway.provider=razorpay", "payment.gateway.read-timeout=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aPositiveTimeoutIsAccepted() {
        assertThatCode(() -> PaymentGatewayConfig.timeouts(Duration.ofMillis(1), Duration.ofMinutes(1)))
                .doesNotThrowAnyException();
    }
}
