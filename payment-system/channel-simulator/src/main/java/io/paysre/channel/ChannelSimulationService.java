package io.paysre.channel;

import io.paysre.contracts.ChannelCallback;
import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelPaymentResponse;
import io.paysre.contracts.ChannelResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChannelSimulationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelSimulationService.class);

    private final FaultRuleRepository rules;
    private final FaultDecider decider;
    private final Clock clock;
    private final ChannelMetrics metrics;
    private final ChannelTelemetry telemetry;
    private final Map<String, ChannelPaymentResponse> finalStates = new ConcurrentHashMap<>();
    private final Map<String, ChannelCallback> callbacks = new ConcurrentHashMap<>();
    private final Set<String> duplicateCallbackKeys = ConcurrentHashMap.newKeySet();

    public ChannelSimulationService(
            FaultRuleRepository rules,
            FaultDecider decider,
            Clock clock,
            ChannelMetrics metrics,
            ChannelTelemetry telemetry) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.decider = Objects.requireNonNull(decider, "decider");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public ChannelPaymentResponse pay(ChannelPaymentRequest request) {
        Objects.requireNonNull(request, "request");
        Instant started = clock.instant();
        try {
            var response = telemetry.inPaymentSpan(
                    request.paymentId(), request.channel(), () -> process(request));
            metrics.recordRequest(request.channel(), "SUCCESS", elapsed(started));
            return response;
        } catch (ChannelTimeoutException exception) {
            metrics.recordRequest(request.channel(), "TIMEOUT", elapsed(started));
            throw exception;
        } catch (RuntimeException exception) {
            metrics.recordRequest(request.channel(), "ERROR", elapsed(started));
            throw exception;
        }
    }

    private ChannelPaymentResponse process(ChannelPaymentRequest request) {
        Instant now = Instant.now(clock);
        var fault = rules.findActive(request.channel(), now)
                .filter(rule -> rule.type() != FaultType.NONE)
                .filter(rule -> decider.applies(request.paymentId(), rule))
                .map(FaultRule::type)
                .orElse(FaultType.NONE);
        var outcome = outcomeFor(fault);
        var response = new ChannelPaymentResponse(
                request.requestId(),
                request.paymentId(),
                outcome.result(),
                outcome.channelCode(),
                now);

        finalStates.put(request.paymentId(), response);
        boolean responseLost = fault == FaultType.TIMEOUT_BUT_SUCCESS
                || fault == FaultType.TIMEOUT_BUT_FAILED;
        logFinalState(request, response, responseLost);
        if (responseLost) {
            throw new ChannelTimeoutException(request.paymentId());
        }
        return response;
    }

    private ChannelOutcome outcomeFor(FaultType fault) {
        return switch (fault) {
            case TIMEOUT_BUT_SUCCESS -> new ChannelOutcome(
                    ChannelResult.SUCCESS, "00");
            case TIMEOUT_BUT_FAILED -> new ChannelOutcome(
                    ChannelResult.FAILED, "51");
            case DECLINE_ALL -> new ChannelOutcome(
                    ChannelResult.FAILED, "05");
            case CALLBACK_LOST, CALLBACK_DUPLICATED, NONE -> new ChannelOutcome(
                    ChannelResult.SUCCESS, "00");
        };
    }

    private Duration elapsed(Instant started) {
        Duration elapsed = Duration.between(started, clock.instant());
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }

    private void logFinalState(
            ChannelPaymentRequest request,
            ChannelPaymentResponse response,
            boolean responseLost) {
        LOGGER.atInfo()
                .addKeyValue("event", "CHANNEL_FINAL_STATE_PERSISTED")
                .addKeyValue("paymentId", request.paymentId())
                .addKeyValue("channel", request.channel())
                .addKeyValue("result", response.result().name())
                .addKeyValue("channelCode", response.channelCode())
                .addKeyValue("responseLost", responseLost)
                .log("Channel final state persisted");
    }

    public ChannelPaymentResponse query(String paymentId) {
        var response = finalStates.get(paymentId);
        if (response == null) {
            throw new ChannelPaymentNotFoundException(paymentId);
        }
        return response;
    }

    public Optional<ChannelCallback> ingestCallback(ChannelCallback callback) {
        Objects.requireNonNull(callback, "callback");
        var activeFault = rules.findActive(callback.channel(), clock.instant())
                .map(FaultRule::type)
                .orElse(FaultType.NONE);
        if (activeFault == FaultType.CALLBACK_LOST) {
            LOGGER.atWarn()
                    .addKeyValue("event", "CHANNEL_CALLBACK_DROPPED")
                    .addKeyValue("paymentId", callback.paymentId())
                    .addKeyValue("channel", callback.channel())
                    .log("Channel callback dropped by fault rule");
            metrics.recordRequest(callback.channel(), "CALLBACK_DROPPED", Duration.ZERO);
            return Optional.empty();
        }
        String key = callback.paymentId() + ":" + callback.sequenceNumber();
        boolean duplicate;
        if (activeFault == FaultType.CALLBACK_DUPLICATED) {
            duplicate = !duplicateCallbackKeys.add(key);
            if (duplicate) {
                callbacks.put(callback.callbackId() + ":duplicate", callback);
                LOGGER.atWarn()
                        .addKeyValue("event", "CHANNEL_CALLBACK_DUPLICATED")
                        .addKeyValue("paymentId", callback.paymentId())
                        .addKeyValue("channel", callback.channel())
                        .log("Channel callback duplicated by fault rule");
                metrics.recordRequest(callback.channel(), "CALLBACK_DUPLICATED", Duration.ZERO);
                return Optional.of(callback);
            }
        }
        callbacks.put(callback.callbackId(), callback);
        metrics.recordRequest(callback.channel(), "CALLBACK_ACCEPTED", Duration.ZERO);
        LOGGER.atInfo()
                .addKeyValue("event", "CHANNEL_CALLBACK_ACCEPTED")
                .addKeyValue("paymentId", callback.paymentId())
                .addKeyValue("channel", callback.channel())
                .addKeyValue("sequenceNumber", callback.sequenceNumber())
                .log("Channel callback accepted");
        return Optional.of(callback);
    }

    private record ChannelOutcome(ChannelResult result, String channelCode) {
    }
}