package io.paysre.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paysre.contracts.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class PaymentOrderTest {

    @Test
    void channelTimeoutMovesProcessingPaymentToUnknown() {
        var payment = payment("P10001");
        payment.start("CHANNEL_A", "route-v1", Instant.EPOCH);

        payment.markUnknown("CHANNEL_TIMEOUT", Instant.EPOCH.plusSeconds(1));

        assertThat(payment.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(payment.events().get(payment.events().size() - 1).reasonCode())
                .isEqualTo("CHANNEL_TIMEOUT");
    }

    @Test
    void cannotDirectlyMarkAnInitialPaymentSuccessful() {
        var payment = payment("P10002");

        assertThatThrownBy(() -> payment.markSuccess("00", Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expected PROCESSING but was INIT");
    }

    @Test
    void reconciliationCanConfirmAnUnknownPaymentAsSuccessful() {
        var payment = payment("P10003");
        payment.start("CHANNEL_A", "route-v1", Instant.EPOCH);
        payment.markUnknown("CHANNEL_TIMEOUT", Instant.EPOCH.plusSeconds(1));

        payment.confirmUnknownSuccess("00", Instant.EPOCH.plusSeconds(2));

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.events()).extracting(PaymentEvent::toStatus)
                .containsExactly(
                        PaymentStatus.INIT,
                        PaymentStatus.PROCESSING,
                        PaymentStatus.UNKNOWN,
                        PaymentStatus.SUCCESS);
    }

    @Test
    void reconciliationCanConfirmAnUnknownPaymentAsFailed() {
        var payment = payment("P10004");
        payment.start("CHANNEL_A", "route-v1", Instant.EPOCH);
        payment.markUnknown("CHANNEL_TIMEOUT", Instant.EPOCH.plusSeconds(1));

        payment.confirmUnknownFailure("51", Instant.EPOCH.plusSeconds(2));

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        var last = payment.events().get(payment.events().size() - 1);
        assertThat(last.source()).isEqualTo("CHANNEL_QUERY");
        assertThat(last.reasonCode()).isEqualTo("51");
    }

    @Test
    void reconciliationOnlyAppliesToUnknownPayments() {
        var payment = payment("P10005");
        payment.start("CHANNEL_A", "route-v1", Instant.EPOCH);
        payment.markSuccess("00", Instant.EPOCH.plusSeconds(1));

        assertThatThrownBy(() -> payment.confirmUnknownFailure("51", Instant.EPOCH.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expected UNKNOWN but was SUCCESS");
    }

    private PaymentOrder payment(String paymentId) {
        return PaymentOrder.create(
                paymentId,
                "O10001",
                "M001",
                new Money(new BigDecimal("10.00"), Currency.getInstance("CNY")),
                Instant.EPOCH);
    }
}
