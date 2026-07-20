package io.paysre.channel;

import java.time.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(proxyBeanMethods = false)
public class ChannelSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChannelSimulatorApplication.class, args);
    }

    @Bean
    Clock channelClock() {
        return Clock.systemUTC();
    }

    @Bean
    FaultRuleRepository faultRuleRepository() {
        return new InMemoryFaultRuleRepository();
    }

    @Bean
    FaultDecider faultDecider() {
        return new FaultDecider();
    }

    @Bean
    ChannelSimulationService channelSimulationService(
            FaultRuleRepository repository,
            FaultDecider decider,
            Clock clock,
            ChannelMetrics metrics,
            ChannelTelemetry telemetry) {
        return new ChannelSimulationService(repository, decider, clock, metrics, telemetry);
    }

    @Bean
    ChannelMetrics channelMetrics(MeterRegistry registry) {
        return new ChannelMetrics(registry);
    }

    @Bean
    ChannelTelemetry channelTelemetry(OpenTelemetry openTelemetry) {
        return new ChannelTelemetry(openTelemetry.getTracer("io.paysre.channel-simulator"));
    }
}
