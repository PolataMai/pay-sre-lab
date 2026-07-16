package io.paysre.channel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChannelSimulatorApplicationTest {

    @Autowired
    private ChannelSimulationService simulationService;

    @Autowired
    private FaultRuleRepository faultRuleRepository;

    @Test
    void wiresTheSimulatorCore() {
        assertThat(simulationService).isNotNull();
        assertThat(faultRuleRepository).isInstanceOf(InMemoryFaultRuleRepository.class);
    }
}
