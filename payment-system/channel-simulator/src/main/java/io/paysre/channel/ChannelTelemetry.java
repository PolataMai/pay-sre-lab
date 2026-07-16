package io.paysre.channel;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.Objects;
import java.util.function.Supplier;

public final class ChannelTelemetry {

    private final Tracer tracer;

    public ChannelTelemetry(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    public <T> T inPaymentSpan(
            String paymentId, String channel, Supplier<T> operation) {
        var span = tracer.spanBuilder("payment.channel.process").startSpan();
        span.setAttribute("payment.id", paymentId);
        span.setAttribute("payment.channel", channel);
        try (var ignored = span.makeCurrent()) {
            return operation.get();
        } catch (RuntimeException | Error exception) {
            span.recordException(exception);
            span.setStatus(StatusCode.ERROR);
            throw exception;
        } finally {
            span.end();
        }
    }
}
