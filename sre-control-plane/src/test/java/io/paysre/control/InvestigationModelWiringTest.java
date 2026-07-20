package io.paysre.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.investigation.StubInvestigationModel;
import io.paysre.control.investigation.minimax.MiniMaxInvestigationModel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class InvestigationModelWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-17T02:00:00Z"), ZoneOffset.UTC);

    private final ControlPlaneApplication application = new ControlPlaneApplication();

    @Test
    void defaultsToTheDeterministicStubModel() {
        var model = application.investigationModel(
                "stub", "https://api.minimax.io/v1", "", "MiniMax-M2",
                1.0, 4096, Duration.ofSeconds(45),
                RestClient.builder(), MAPPER, CLOCK);

        assertThat(model).isInstanceOf(StubInvestigationModel.class);
    }

    @Test
    void buildsTheMiniMaxModelWhenSelectedAndKeyIsPresent() {
        var model = application.investigationModel(
                "minimax", "https://api.minimax.io/v1", "test-key", "MiniMax-M2",
                1.0, 4096, Duration.ofSeconds(45),
                RestClient.builder(), MAPPER, CLOCK);

        assertThat(model).isInstanceOf(MiniMaxInvestigationModel.class);
    }

    @Test
    void refusesToStartMiniMaxModeWithoutAnApiKey() {
        assertThatThrownBy(() -> application.investigationModel(
                "minimax", "https://api.minimax.io/v1", " ", "MiniMax-M2",
                1.0, 4096, Duration.ofSeconds(45),
                RestClient.builder(), MAPPER, CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MINIMAX_API_KEY");
    }

    @Test
    void rejectsUnsupportedModelModes() {
        assertThatThrownBy(() -> application.investigationModel(
                "gpt", "https://api.minimax.io/v1", "test-key", "MiniMax-M2",
                1.0, 4096, Duration.ofSeconds(45),
                RestClient.builder(), MAPPER, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported investigation model");
    }
}
