package io.paysre.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paysre.contracts.ChannelResult;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChannelReturnCodeMappingTest {

    @Test
    void classifiesConfiguredCodesBySetMembership() {
        var mapping = new ChannelReturnCodeMapping.Fixed(
                Set.of("00"),
                Set.of("51", "05", "96"),
                ChannelResult.FAILED);

        assertThat(mapping.resultFor("00")).isEqualTo(ChannelResult.SUCCESS);
        assertThat(mapping.resultFor("51")).isEqualTo(ChannelResult.FAILED);
        assertThat(mapping.resultFor("05")).isEqualTo(ChannelResult.FAILED);
        assertThat(mapping.resultFor("96")).isEqualTo(ChannelResult.FAILED);
        assertThat(mapping.resultFor("ZZ")).isEqualTo(ChannelResult.FAILED);
    }

    @Test
    void honoursFallbackForUnknownCodes() {
        var mapping = new ChannelReturnCodeMapping.Fixed(
                Set.of("00"),
                Set.of(),
                ChannelResult.TIMEOUT);

        assertThat(mapping.resultFor("00")).isEqualTo(ChannelResult.SUCCESS);
        assertThat(mapping.resultFor("??")).isEqualTo(ChannelResult.TIMEOUT);
    }

    @Test
    void rejectsOverlappingSuccessAndFailureSets() {
        assertThatThrownBy(() -> new ChannelReturnCodeMapping.Fixed(
                Set.of("00", "51"),
                Set.of("51"),
                ChannelResult.FAILED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }
}