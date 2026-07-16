package io.paysre.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentTelemetryTest {

    private final Tracer tracer = mock(Tracer.class);
    private final SpanBuilder spanBuilder = mock(SpanBuilder.class);
    private final Span span = mock(Span.class);
    private final Scope scope = mock(Scope.class);
    private PaymentTelemetry telemetry;

    @BeforeEach
    void setUp() {
        when(tracer.spanBuilder("payment.channel.invoke")).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(span.makeCurrent()).thenReturn(scope);
        telemetry = new PaymentTelemetry(tracer);
    }

    @Test
    void addsPaymentAndChannelContextAndReturnsTheOperationResult() {
        var result = telemetry.inSpan(
                "payment.channel.invoke", "P10001", "CHANNEL_A", () -> "ok");

        assertThat(result).isEqualTo("ok");
        verify(span).setAttribute("payment.id", "P10001");
        verify(span).setAttribute("payment.channel", "CHANNEL_A");
        verify(scope).close();
        verify(span).end();
    }

    @Test
    void recordsAnExceptionAndMarksTheSpanAsErrorBeforeRethrowing() {
        var failure = new IllegalStateException("channel unavailable");

        assertThatThrownBy(() -> telemetry.inSpan(
                        "payment.channel.invoke",
                        "P10001",
                        "CHANNEL_A",
                        () -> {
                            throw failure;
                        }))
                .isSameAs(failure);

        verify(span).recordException(failure);
        verify(span).setStatus(StatusCode.ERROR);
        verify(span).end();
    }
}
