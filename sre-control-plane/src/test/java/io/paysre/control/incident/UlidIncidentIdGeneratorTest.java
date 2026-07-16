package io.paysre.control.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;

class UlidIncidentIdGeneratorTest {

    @Test
    void generatesPrefixedCrockfordBase32Ulids() {
        var generator = new UlidIncidentIdGenerator(Clock.systemUTC());

        var first = generator.nextIncidentId();
        var second = generator.nextIncidentId();

        assertThat(first).matches("INC-[0-7][0-9A-HJKMNP-TV-Z]{25}");
        assertThat(second).isNotEqualTo(first);
    }
}
