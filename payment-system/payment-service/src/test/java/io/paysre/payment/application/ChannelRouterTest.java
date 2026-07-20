package io.paysre.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelRouterTest {

    @Test
    void returnsTheConfiguredDefaultChannelWhenNoOverlayMatches() {
        var router = new ChannelRouter.Static("CHANNEL_A", Map.of());
        assertThat(router.selectChannel("MERCHANT-X")).isEqualTo("CHANNEL_A");
    }

    @Test
    void returnsTheOverlayChannelForAMatchingMerchant() {
        var router = new ChannelRouter.Static(
                "CHANNEL_A",
                Map.of("MERCHANT-VIP", "CHANNEL_B"));
        assertThat(router.selectChannel("MERCHANT-VIP")).isEqualTo("CHANNEL_B");
        assertThat(router.selectChannel("MERCHANT-OTHER")).isEqualTo("CHANNEL_A");
    }

    @Test
    void overlayTakesPrecedenceOverTheDefaultForAnyKey() {
        var router = new ChannelRouter.Static(
                "CHANNEL_A",
                Map.of("MERCHANT-1", "CHANNEL_B", "MERCHANT-2", "CHANNEL_C"));
        assertThat(router.selectChannel("MERCHANT-1")).isEqualTo("CHANNEL_B");
        assertThat(router.selectChannel("MERCHANT-2")).isEqualTo("CHANNEL_C");
    }
}