package io.paysre.channel;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelPaymentResponse;
import io.paysre.contracts.ChannelResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
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
        var response = new ChannelPaymentResponse(
                request.requestId(),
                request.paymentId(),
                ChannelResult.SUCCESS,
                "00",
                now);

        var rule = rules.findActive(request.channel(), now);
        if (rule.isPresent()
                && rule.get().type() == FaultType.TIMEOUT_BUT_SUCCESS
                && decider.applies(request.paymentId(), rule.get())) {
            finalStates.put(request.paymentId(), response);
            logFinalState(request, response, true);
            throw new ChannelTimeoutException(request.paymentId());
        }

        finalStates.put(request.paymentId(), response);
        logFinalState(request, response, false);
        return response;
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
}
