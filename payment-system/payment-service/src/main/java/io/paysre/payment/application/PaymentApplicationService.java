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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaymentApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentApplicationService.class);

    private final PaymentRepository repository;
    private final ChannelClient channelClient;
    private final PaymentIdGenerator ids;
    private final Clock clock;
    private final PaymentMetrics metrics;
    private final PaymentTelemetry telemetry;
    private final ChannelReturnCodeMapping returnCodeMapping;
    private final ChannelRouter router;

    public PaymentApplicationService(
            PaymentRepository repository,
            ChannelClient channelClient,
            PaymentIdGenerator ids,
            Clock clock,
            PaymentMetrics metrics,
            PaymentTelemetry telemetry,
            ChannelReturnCodeMapping returnCodeMapping,
            ChannelRouter router) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.channelClient = Objects.requireNonNull(channelClient, "channelClient");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.returnCodeMapping = Objects.requireNonNull(returnCodeMapping, "returnCodeMapping");
        this.router = Objects.requireNonNull(router, "router");
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
        payment.start(router.selectChannel(command.merchantId()), "route-v1", now);
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
            if (response.result() == ChannelResult.TIMEOUT) {
                payment.markUnknown("CHANNEL_TIMEOUT", clock.instant());
            } else {
                var mapped = returnCodeMapping.map(response.channelCode());
                switch (mapped.kind()) {
                    case MAPPED_SUCCESS -> payment.markSuccess(
                            response.channelCode(), clock.instant());
                    case MAPPED_FAILURE -> payment.markFailed(
                            response.channelCode(), clock.instant());
                    case UNMAPPED -> payment.markUnknown(
                            "CHANNEL_CODE_UNMAPPED", clock.instant());
                }
            }
        } catch (ChannelCallTimeoutException exception) {
            payment.markUnknown("CHANNEL_TIMEOUT", clock.instant());
        }

        var saved = repository.save(payment, command.idempotencyKey());
        metrics.recordTransition(
                payment.channel(), PaymentStatus.PROCESSING, payment.status());
        logTransition(payment);
        return saved;
    }

    private void logTransition(PaymentOrder payment) {
        var event = payment.events().get(payment.events().size() - 1);
        LOGGER.atInfo()
                .addKeyValue("event", "PAYMENT_STATE_CHANGED")
                .addKeyValue("paymentId", payment.paymentId())
                .addKeyValue("orderId", payment.orderId())
                .addKeyValue("channel", payment.channel())
                .addKeyValue("fromStatus", PaymentStatus.PROCESSING.name())
                .addKeyValue("toStatus", payment.status().name())
                .addKeyValue("reasonCode", event.reasonCode())
                .log("Payment state changed");
    }

    private ChannelPaymentRequest toChannelRequest(PaymentOrder payment) {
        return new ChannelPaymentRequest(
                UUID.randomUUID().toString(),
                payment.paymentId(),
                payment.channel(),
                payment.money());
    }
}
