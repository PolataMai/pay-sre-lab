package io.paysre.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.paysre.contracts.Money;
import io.paysre.payment.application.AcceptPaymentCommand;
import io.paysre.payment.application.ChannelCallTimeoutException;
import io.paysre.payment.application.ChannelClient;
import io.paysre.payment.application.ChannelReturnCodeMapping;
import io.paysre.payment.application.PaymentApplicationService;
import io.paysre.payment.application.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PaymentStructuredLoggingTest {

    @Test
    void logsTheStateTransitionWithoutTheIdempotencySecretOrPaymentPayload() {
        var repository = mock(PaymentRepository.class);
        when(repository.findByMerchantAndIdempotencyKey(any(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any(), eq("9999888877776666")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ChannelClient channel = request -> {
            throw new ChannelCallTimeoutException(request.paymentId());
        };
        var service = new PaymentApplicationService(
                repository,
                channel,
                () -> "PAY-1",
                Clock.fixed(Instant.parse("2026-07-16T10:00:00Z"), ZoneOffset.UTC),
                new PaymentMetrics(new SimpleMeterRegistry()),
                new PaymentTelemetry(OpenTelemetry.noop().getTracer("test")),
                new ChannelReturnCodeMapping.Fixed(
                        java.util.Set.of("00"),
                        java.util.Set.of("51", "05", "96")),
                new io.paysre.payment.application.ChannelRouter.Static(
                        "CHANNEL_A", java.util.Map.of()));
        var appender = capture(PaymentApplicationService.class);
        try {
            service.accept(new AcceptPaymentCommand(
                    "ORDER-1",
                    "MERCHANT-1",
                    "9999888877776666",
                    new Money(new BigDecimal("10.00"), Currency.getInstance("CNY"))));

            var event = appender.list.stream()
                    .filter(item -> item.getFormattedMessage().equals("Payment state changed"))
                    .findFirst()
                    .orElseThrow();
            Map<String, String> fields = event.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value)));
            assertThat(fields)
                    .containsEntry("event", "PAYMENT_STATE_CHANGED")
                    .containsEntry("paymentId", "PAY-1")
                    .containsEntry("channel", "CHANNEL_A")
                    .containsEntry("fromStatus", "PROCESSING")
                    .containsEntry("toStatus", "UNKNOWN")
                    .containsEntry("reasonCode", "CHANNEL_TIMEOUT")
                    .doesNotContainKeys("idempotencyKey", "request", "payload", "authorization", "signature");
            assertThat(event.toString()).doesNotContain("9999888877776666", "10.00");
        } finally {
            ((Logger) LoggerFactory.getLogger(PaymentApplicationService.class))
                    .detachAppender(appender);
        }
    }

    private ListAppender<ILoggingEvent> capture(Class<?> loggerType) {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(loggerType)).addAppender(appender);
        return appender;
    }
}
