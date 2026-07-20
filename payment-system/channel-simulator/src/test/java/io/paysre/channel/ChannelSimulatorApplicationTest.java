package io.paysre.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChannelSimulatorApplicationTest {

    @Autowired
    private ChannelSimulationService simulationService;

    @Autowired
    private FaultRuleRepository faultRuleRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private PrometheusMeterRegistry prometheusRegistry;

    @Autowired
    private OpenTelemetry openTelemetry;

    @Test
    void wiresTheSimulatorCore() {
        assertThat(simulationService).isNotNull();
        assertThat(faultRuleRepository).isInstanceOf(InMemoryFaultRuleRepository.class);
        assertThat(applicationContext.containsBean("healthEndpoint")).isTrue();
        assertThat(prometheusRegistry).isNotNull();
        assertThat(openTelemetry).isNotNull();
    }
}
