package io.paysre.payment.observability;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.Objects;
import java.util.function.Supplier;

public final class PaymentTelemetry {

    private final Tracer tracer;

    public PaymentTelemetry(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    public <T> T inSpan(
            String spanName,
            String paymentId,
            String channel,
            Supplier<T> operation) {
        var span = tracer.spanBuilder(spanName).startSpan();
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
