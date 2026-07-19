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
                Set.of("51", "05", "96"));

        assertThat(mapping.map("00").kind())
                .isEqualTo(ChannelReturnCodeMapping.ResultKind.MAPPED_SUCCESS);
        assertThat(mapping.map("00").channelResult()).isEqualTo(ChannelResult.SUCCESS);
        assertThat(mapping.map("51").kind())
                .isEqualTo(ChannelReturnCodeMapping.ResultKind.MAPPED_FAILURE);
        assertThat(mapping.map("05").kind())
                .isEqualTo(ChannelReturnCodeMapping.ResultKind.MAPPED_FAILURE);
        assertThat(mapping.map("96").kind())
                .isEqualTo(ChannelReturnCodeMapping.ResultKind.MAPPED_FAILURE);
    }

    @Test
    void unmappedCodesReturnUnmappedKindRatherThanGuessing() {
        var mapping = new ChannelReturnCodeMapping.Fixed(
                Set.of("00"),
                Set.of("51"));

        assertThat(mapping.map("???").kind())
                .isEqualTo(ChannelReturnCodeMapping.ResultKind.UNMAPPED);
        assertThat(mapping.map("E9").kind())
                .isEqualTo(ChannelReturnCodeMapping.ResultKind.UNMAPPED);
    }

    @Test
    void rejectsOverlappingSuccessAndFailureSets() {
        assertThatThrownBy(() -> new ChannelReturnCodeMapping.Fixed(
                Set.of("00", "51"),
                Set.of("51")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void emptyFailureSetStillMarksUnmappedForUnknownCodes() {
        var mapping = new ChannelReturnCodeMapping.Fixed(Set.of("00"), Set.of());

        assertThat(mapping.map("00").kind())
                .isEqualTo(ChannelReturnCodeMapping.ResultKind.MAPPED_SUCCESS);
        assertThat(mapping.map("51").kind())
                .isEqualTo(ChannelReturnCodeMapping.ResultKind.UNMAPPED);
    }
}