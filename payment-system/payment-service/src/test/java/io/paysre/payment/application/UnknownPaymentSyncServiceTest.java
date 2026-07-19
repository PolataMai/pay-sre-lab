package io.paysre.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.paysre.contracts.ChannelPaymentResponse;
import io.paysre.contracts.ChannelResult;
import io.paysre.contracts.Money;
import io.paysre.payment.domain.PaymentOrder;
import io.paysre.payment.domain.PaymentStatus;
import io.paysre.payment.observability.PaymentMetrics;
import io.paysre.payment.observability.PaymentTelemetry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UnknownPaymentSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");

    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final ChannelStateQuery channel = mock(ChannelStateQuery.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UnknownPaymentSyncService service = new UnknownPaymentSyncService(
            repository,
            channel,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new PaymentMetrics(registry),
            new PaymentTelemetry(OpenTelemetry.noop().getTracer("test")),
            new ChannelReturnCodeMapping.Fixed(
                    java.util.Set.of("00"),
                    java.util.Set.of("51", "05", "96"),
                    io.paysre.contracts.ChannelResult.FAILED));

    @Test
    void convergesAnUnknownPaymentTheChannelReportsAsSuccessful() {
        var payment = unknownPayment("P1");
        when(repository.findByPaymentId("P1")).thenReturn(Optional.of(payment));
        when(channel.query("P1")).thenReturn(response("P1", ChannelResult.SUCCESS, "00"));

        var result = service.sync("P1").orElseThrow();

        assertThat(result.outcome()).isEqualTo(PaymentSyncOutcome.SYNCED);
        assertThat(result.previousStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(result.currentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCESS);
        verify(repository).save(payment, null);
        assertThat(registry.counter(
                        "payment_state_transition_total",
                        "channel", "CHANNEL_A",
                        "from", "UNKNOWN",
                        "to", "SUCCESS").count())
                .isEqualTo(1.0);
    }

    @Test
    void convergesAnUnknownPaymentTheChannelReportsAsFailed() {
        var payment = unknownPayment("P2");
        when(repository.findByPaymentId("P2")).thenReturn(Optional.of(payment));
        when(channel.query("P2")).thenReturn(response("P2", ChannelResult.FAILED, "51"));

        var result = service.sync("P2").orElseThrow();

        assertThat(result.outcome()).isEqualTo(PaymentSyncOutcome.SYNCED);
        assertThat(result.currentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        verify(repository).save(payment, null);
    }

    @Test
    void keepsThePaymentUnknownWhenTheChannelQueryTimesOut() {
        var payment = unknownPayment("P3");
        when(repository.findByPaymentId("P3")).thenReturn(Optional.of(payment));
        when(channel.query("P3")).thenThrow(new ChannelCallTimeoutException("P3"));

        var result = service.sync("P3").orElseThrow();

        assertThat(result.outcome()).isEqualTo(PaymentSyncOutcome.STILL_UNKNOWN);
        assertThat(payment.status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(repository, never()).save(any(), isNull());
    }

    @Test
    void keepsThePaymentUnknownWhenTheChannelHasNoRecord() {
        var payment = unknownPayment("P4");
        when(repository.findByPaymentId("P4")).thenReturn(Optional.of(payment));
        when(channel.query("P4")).thenThrow(new ChannelStateMissingException("P4"));

        var result = service.sync("P4").orElseThrow();

        assertThat(result.outcome()).isEqualTo(PaymentSyncOutcome.STILL_UNKNOWN);
        assertThat(payment.status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void keepsThePaymentUnknownWhenTheChannelReportsTimeoutAsAResult() {
        var payment = unknownPayment("P5");
        when(repository.findByPaymentId("P5")).thenReturn(Optional.of(payment));
        when(channel.query("P5")).thenReturn(response("P5", ChannelResult.TIMEOUT, "TO"));

        var result = service.sync("P5").orElseThrow();

        assertThat(result.outcome()).isEqualTo(PaymentSyncOutcome.STILL_UNKNOWN);
        assertThat(result.channelResult()).isEqualTo(ChannelResult.TIMEOUT);
        assertThat(payment.status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void isIdempotentForPaymentsAlreadyInAFinalState() {
        var payment = unknownPayment("P6");
        payment.confirmUnknownSuccess("00", NOW);
        when(repository.findByPaymentId("P6")).thenReturn(Optional.of(payment));

        var result = service.sync("P6").orElseThrow();

        assertThat(result.outcome()).isEqualTo(PaymentSyncOutcome.NOT_UNKNOWN);
        assertThat(result.currentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(channel, never()).query(any());
    }

    @Test
    void reportsMissingPaymentsToTheCaller() {
        when(repository.findByPaymentId("MISSING")).thenReturn(Optional.empty());

        assertThat(service.sync("MISSING")).isEmpty();
    }

    private PaymentOrder unknownPayment(String paymentId) {
        var payment = PaymentOrder.create(
                paymentId,
                "O-" + paymentId,
                "M001",
                new Money(new BigDecimal("10.00"), Currency.getInstance("CNY")),
                NOW.minusSeconds(60));
        payment.start("CHANNEL_A", "route-v1", NOW.minusSeconds(59));
        payment.markUnknown("CHANNEL_TIMEOUT", NOW.minusSeconds(58));
        return payment;
    }

    private ChannelPaymentResponse response(
            String paymentId, ChannelResult result, String channelCode) {
        return new ChannelPaymentResponse("REQ-" + paymentId, paymentId, result, channelCode, NOW);
    }
}
