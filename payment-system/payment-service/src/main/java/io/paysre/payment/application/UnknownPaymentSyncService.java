package io.paysre.payment.application;

import io.paysre.contracts.ChannelPaymentResponse;
import io.paysre.contracts.ChannelResult;
import io.paysre.payment.domain.PaymentOrder;
import io.paysre.payment.domain.PaymentStatus;
import io.paysre.payment.observability.PaymentMetrics;
import io.paysre.payment.observability.PaymentTelemetry;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converges an UNKNOWN payment by asking the channel for its final state.
 * The payment only leaves UNKNOWN when the channel reports a definitive
 * SUCCESS or FAILED; a timeout or missing channel record keeps it UNKNOWN.
 */
public final class UnknownPaymentSyncService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(UnknownPaymentSyncService.class);

    private final PaymentRepository repository;
    private final ChannelStateQuery channelStateQuery;
    private final Clock clock;
    private final PaymentMetrics metrics;
    private final PaymentTelemetry telemetry;
    private final ChannelReturnCodeMapping returnCodeMapping;

    public UnknownPaymentSyncService(
            PaymentRepository repository,
            ChannelStateQuery channelStateQuery,
            Clock clock,
            PaymentMetrics metrics,
            PaymentTelemetry telemetry,
            ChannelReturnCodeMapping returnCodeMapping) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.channelStateQuery = Objects.requireNonNull(channelStateQuery, "channelStateQuery");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.returnCodeMapping = Objects.requireNonNull(returnCodeMapping, "returnCodeMapping");
    }

    public Optional<PaymentSyncResult> sync(String paymentId) {
        var found = repository.findByPaymentId(paymentId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var payment = found.get();
        var previous = payment.status();
        if (previous != PaymentStatus.UNKNOWN) {
            return Optional.of(new PaymentSyncResult(
                    paymentId, previous, previous, null, PaymentSyncOutcome.NOT_UNKNOWN));
        }

        ChannelPaymentResponse response;
        try {
            response = telemetry.inSpan(
                    "payment.channel.state-query",
                    paymentId,
                    payment.channel(),
                    () -> channelStateQuery.query(paymentId));
        } catch (ChannelCallTimeoutException | ChannelStateMissingException exception) {
            return Optional.of(stillUnknown(paymentId, null));
        }
        if (response.result() == ChannelResult.TIMEOUT) {
            return Optional.of(stillUnknown(paymentId, ChannelResult.TIMEOUT));
        }
        var mappedResult = returnCodeMapping.resultFor(response.channelCode());
        if (mappedResult == ChannelResult.SUCCESS) {
            payment.confirmUnknownSuccess(response.channelCode(), clock.instant());
        } else if (mappedResult == ChannelResult.FAILED) {
            payment.confirmUnknownFailure(response.channelCode(), clock.instant());
        } else {
            return Optional.of(stillUnknown(paymentId, mappedResult));
        }

        repository.save(payment, null);
        metrics.recordTransition(payment.channel(), previous, payment.status());
        logTransition(payment, previous);
        return Optional.of(new PaymentSyncResult(
                paymentId,
                previous,
                payment.status(),
                response.result(),
                PaymentSyncOutcome.SYNCED));
    }

    private PaymentSyncResult stillUnknown(String paymentId, ChannelResult channelResult) {
        return new PaymentSyncResult(
                paymentId,
                PaymentStatus.UNKNOWN,
                PaymentStatus.UNKNOWN,
                channelResult,
                PaymentSyncOutcome.STILL_UNKNOWN);
    }

    private void logTransition(PaymentOrder payment, PaymentStatus previous) {
        var event = payment.events().get(payment.events().size() - 1);
        LOGGER.atInfo()
                .addKeyValue("event", "PAYMENT_STATE_CHANGED")
                .addKeyValue("paymentId", payment.paymentId())
                .addKeyValue("orderId", payment.orderId())
                .addKeyValue("channel", payment.channel())
                .addKeyValue("fromStatus", previous.name())
                .addKeyValue("toStatus", payment.status().name())
                .addKeyValue("reasonCode", event.reasonCode())
                .log("Payment state changed");
    }
}
