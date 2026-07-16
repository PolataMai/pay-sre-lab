package io.paysre.payment.application;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelResult;
import io.paysre.payment.domain.PaymentOrder;
import io.paysre.payment.domain.PaymentStatus;
import io.paysre.payment.observability.PaymentMetrics;
import io.paysre.payment.observability.PaymentTelemetry;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class PaymentApplicationService {

    private final PaymentRepository repository;
    private final ChannelClient channelClient;
    private final PaymentIdGenerator ids;
    private final Clock clock;
    private final PaymentMetrics metrics;
    private final PaymentTelemetry telemetry;

    public PaymentApplicationService(
            PaymentRepository repository,
            ChannelClient channelClient,
            PaymentIdGenerator ids,
            Clock clock,
            PaymentMetrics metrics,
            PaymentTelemetry telemetry) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.channelClient = Objects.requireNonNull(channelClient, "channelClient");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public PaymentOrder accept(AcceptPaymentCommand command) {
        return repository.findByMerchantAndIdempotencyKey(
                        command.merchantId(), command.idempotencyKey())
                .orElseGet(() -> createAndInvoke(command));
    }

    private PaymentOrder createAndInvoke(AcceptPaymentCommand command) {
        var now = clock.instant();
        var payment = PaymentOrder.create(
                ids.nextPaymentId(),
                command.orderId(),
                command.merchantId(),
                command.money(),
                now);
        payment.start("CHANNEL_A", "route-v1", now);
        var inserted = repository.save(payment, command.idempotencyKey());
        if (inserted != payment) {
            return inserted;
        }
        metrics.recordTransition(payment.channel(), PaymentStatus.INIT, PaymentStatus.PROCESSING);

        try {
            var response = telemetry.inSpan(
                    "payment.channel.invoke",
                    payment.paymentId(),
                    payment.channel(),
                    () -> channelClient.pay(toChannelRequest(payment)));
            if (response.result() == ChannelResult.SUCCESS) {
                payment.markSuccess(response.channelCode(), clock.instant());
            } else if (response.result() == ChannelResult.FAILED) {
                payment.markFailed(response.channelCode(), clock.instant());
            } else {
                payment.markUnknown("CHANNEL_TIMEOUT", clock.instant());
            }
        } catch (ChannelCallTimeoutException exception) {
            payment.markUnknown("CHANNEL_TIMEOUT", clock.instant());
        }

        var saved = repository.save(payment, command.idempotencyKey());
        metrics.recordTransition(
                payment.channel(), PaymentStatus.PROCESSING, payment.status());
        return saved;
    }

    private ChannelPaymentRequest toChannelRequest(PaymentOrder payment) {
        return new ChannelPaymentRequest(
                UUID.randomUUID().toString(),
                payment.paymentId(),
                payment.channel(),
                payment.money());
    }
}
