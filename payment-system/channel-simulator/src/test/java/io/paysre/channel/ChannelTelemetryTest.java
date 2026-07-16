package io.paysre.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

class ChannelTelemetryTest {

    @Test
    void createsAChannelProcessingSpanWithPaymentCorrelation() {
        var tracer = mock(Tracer.class);
        var builder = mock(SpanBuilder.class);
        var span = mock(Span.class);
        var scope = mock(Scope.class);
        when(tracer.spanBuilder("payment.channel.process")).thenReturn(builder);
        when(builder.startSpan()).thenReturn(span);
        when(span.makeCurrent()).thenReturn(scope);
        var telemetry = new ChannelTelemetry(tracer);

        assertThat(telemetry.inPaymentSpan("PAY-1", "CHANNEL_A", () -> "ok"))
                .isEqualTo("ok");

        verify(span).setAttribute("payment.id", "PAY-1");
        verify(span).setAttribute("payment.channel", "CHANNEL_A");
        verify(scope).close();
        verify(span).end();
    }
}
