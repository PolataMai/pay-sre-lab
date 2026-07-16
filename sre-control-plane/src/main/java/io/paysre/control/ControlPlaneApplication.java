package io.paysre.control;

import io.paysre.control.incident.IncidentApplicationService;
import io.paysre.control.incident.IncidentIdGenerator;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.incident.UlidIncidentIdGenerator;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(proxyBeanMethods = false)
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }

    @Bean
    Clock controlPlaneClock() {
        return Clock.systemUTC();
    }

    @Bean
    IncidentIdGenerator incidentIdGenerator(Clock clock) {
        return new UlidIncidentIdGenerator(clock);
    }

    @Bean
    IncidentApplicationService incidentApplicationService(
            IncidentRepository repository, IncidentIdGenerator ids) {
        return new IncidentApplicationService(repository, ids);
    }
}
