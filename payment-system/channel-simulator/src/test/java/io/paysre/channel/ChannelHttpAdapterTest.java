package io.paysre.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChannelHttpAdapterTest {

    @Test
    void mapsChannelTimeoutToAStableGatewayTimeoutProblem() {
        var service = new ChannelSimulationService(
                new InMemoryFaultRuleRepository(),
                new FaultDecider(),
                Clock.systemUTC(),
                new ChannelMetrics(new SimpleMeterRegistry()),
                new ChannelTelemetry(OpenTelemetry.noop().getTracer("test")));
        var controller = new ChannelPaymentController(service);

        var problem = controller.timeout(new ChannelTimeoutException("PAY-1"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value());
        assertThat(problem.getProperties()).containsEntry("code", "CHANNEL_TIMEOUT");
    }

    @Test
    void adminEndpointRejectsAPathAndPayloadChannelMismatch() {
        var controller = new FaultAdminController(new InMemoryFaultRuleRepository());
        var rule = new FaultRule(
                "CHANNEL_A",
                FaultType.TIMEOUT_BUT_SUCCESS,
                BigDecimal.ONE,
                Instant.EPOCH,
                Instant.parse("2099-01-01T00:00:00Z"),
                20260716L);

        assertThatThrownBy(() -> controller.replace("CHANNEL_B", rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("path channel must match rule channel");
    }
}
