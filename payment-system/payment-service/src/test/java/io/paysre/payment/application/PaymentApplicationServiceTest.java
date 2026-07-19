package io.paysre.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.paysre.contracts.ChannelPaymentResponse;
import io.paysre.contracts.ChannelResult;
import io.paysre.contracts.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.paysre.payment.observability.PaymentMetrics;
import io.paysre.payment.observability.PaymentTelemetry;
import io.paysre.payment.domain.PaymentOrder;
import io.paysre.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PaymentApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T10:00:00Z");

    @Test
    void channelTimeoutSavesAnUnknownPaymentWithAnExplicitReason() {
        var repository = new InMemoryPaymentRepository();
        ChannelClient channel = request -> {
            throw new ChannelCallTimeoutException(request.paymentId());
        };
        var service = service(repository, channel);

        var payment = service.accept(command("IDEMPOTENCY-1"));

        assertThat(payment.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(lastEvent(payment).reasonCode()).isEqualTo("CHANNEL_TIMEOUT");
        assertThat(repository.findByMerchantAndIdempotencyKey("M001", "IDEMPOTENCY-1"))
                .containsSame(payment);
    }

    @Test
    void idempotentRetryReturnsTheExistingPaymentWithoutCallingTheChannelAgain() {
        var repository = new InMemoryPaymentRepository();
        var calls = new AtomicInteger();
        ChannelClient channel = request -> {
            calls.incrementAndGet();
            throw new ChannelCallTimeoutException(request.paymentId());
        };
        var service = service(repository, channel);

        var first = service.accept(command("IDEMPOTENCY-2"));
        var retry = service.accept(command("IDEMPOTENCY-2"));

        assertThat(retry).isSameAs(first);
        assertThat(calls).hasValue(1);
    }

    @Test
    void successfulChannelResponseMarksThePaymentSuccessful() {
        var repository = new InMemoryPaymentRepository();
        ChannelClient channel = request -> new ChannelPaymentResponse(
                request.requestId(),
                request.paymentId(),
                ChannelResult.SUCCESS,
                "00",
                NOW);
        var service = service(repository, channel);

        assertThat(service.accept(command("IDEMPOTENCY-3")).status())
                .isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void recordsTheUnknownBusinessOutcomeWhenTheChannelTimesOut() {
        var repository = new InMemoryPaymentRepository();
        var registry = new SimpleMeterRegistry();
        ChannelClient channel = request -> {
            throw new ChannelCallTimeoutException(request.paymentId());
        };
        var service = service(repository, channel, registry);

        service.accept(command("IDEMPOTENCY-4"));

        assertThat(registry.get("payment_attempt_outcome_total")
                        .tags("channel", "CHANNEL_A", "status", "UNKNOWN")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    private PaymentApplicationService service(
            PaymentRepository repository, ChannelClient channelClient) {
        return service(repository, channelClient, new SimpleMeterRegistry());
    }

    private PaymentApplicationService service(
            PaymentRepository repository,
            ChannelClient channelClient,
            SimpleMeterRegistry registry) {
        return new PaymentApplicationService(
                repository,
                channelClient,
                () -> "P10001",
                Clock.fixed(NOW, ZoneOffset.UTC),
                new PaymentMetrics(registry),
                new PaymentTelemetry(OpenTelemetry.noop().getTracer("test")),
                new ChannelReturnCodeMapping.Fixed(
                        java.util.Set.of("00"),
                        java.util.Set.of("51", "05", "96"),
                        io.paysre.contracts.ChannelResult.FAILED));
    }

    private AcceptPaymentCommand command(String idempotencyKey) {
        return new AcceptPaymentCommand(
                "O10001",
                "M001",
                idempotencyKey,
                new Money(new BigDecimal("10.00"), Currency.getInstance("CNY")));
    }

    private io.paysre.payment.domain.PaymentEvent lastEvent(PaymentOrder payment) {
        return payment.events().get(payment.events().size() - 1);
    }

    private static final class InMemoryPaymentRepository implements PaymentRepository {

        private final Map<String, PaymentOrder> payments = new HashMap<>();

        @Override
        public Optional<PaymentOrder> findByMerchantAndIdempotencyKey(
                String merchantId, String idempotencyKey) {
            return Optional.ofNullable(payments.get(merchantId + ":" + idempotencyKey));
        }

        @Override
        public Optional<PaymentOrder> findByPaymentId(String paymentId) {
            return payments.values().stream()
                    .filter(payment -> payment.paymentId().equals(paymentId))
                    .findFirst();
        }

        @Override
        public java.util.List<UnknownPaymentSummary> findUnknown(
                Instant from, Instant to, int size) {
            return java.util.List.of();
        }

        @Override
        public PaymentOrder save(PaymentOrder payment, String idempotencyKey) {
            payments.put(payment.merchantId() + ":" + idempotencyKey, payment);
            return payment;
        }
    }
}
